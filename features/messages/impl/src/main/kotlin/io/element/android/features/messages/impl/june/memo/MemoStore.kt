/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Where the memo data comes from: the memo bot room (HTTP) or [FakeMemoBackend]. */
internal interface MemoBackend {
    /** The current index, [MemoIndex.EMPTY] when the room has none yet, null on a transient error. */
    suspend fun fetchIndex(): MemoIndex?

    /** Sends [op] with [requestId] and suspends until the matching answer arrives (the store applies the timeout). */
    suspend fun call(op: MemoOp, requestId: String): MemoResponse
}

internal sealed interface MemoResult {
    data class Ok(val response: MemoResponse) : MemoResult
    data class Failed(val reason: String, val timedOut: Boolean = false) : MemoResult
}

internal const val MEMO_PAGE_SIZE = 30
internal const val MEMO_PREFETCH_DISTANCE = 5
internal const val MEMO_TIMEOUT_MILLIS = 15_000L

/** One folder tile list: folder + AND tags + sort. */
internal data class MemoListKey(val folder: String, val tags: List<String> = emptyList(), val sort: String? = null) {
    companion object {
        fun of(folder: String, tags: Collection<String>, sort: String? = null) = MemoListKey(folder, tags.sorted(), sort)
    }
}

/** A loaded tile list, kept so going back restores the items, the cursor and the scroll position without asking again. */
internal data class MemoListState(
    val items: List<MemoItem> = emptyList(),
    val nextCursor: String? = null,
    val endReached: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val pages: Int = 0,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** Folder count in the index when the first page arrived ("새 메모 N ↑" baseline). */
    val baseline: Int? = null,
) {
    val loaded: Boolean get() = pages > 0
}

/** True when the last visible tile is within [MEMO_PREFETCH_DISTANCE] of the end and more can be loaded. */
internal fun memoShouldLoadMore(state: MemoListState, lastVisibleIndex: Int): Boolean =
    state.loaded && !state.endReached && !state.loading && state.error == null &&
        lastVisibleIndex >= state.items.size - MEMO_PREFETCH_DISTANCE

/**
 * Element June memo bot data layer (요구 D): index, request/answer matching with a 15 s timeout,
 * tile list cache per folder + tags, tags and notes caches.
 */
internal class MemoStore(
    private val backend: MemoBackend,
    private val timeoutMillis: Long = MEMO_TIMEOUT_MILLIS,
    private val newRequestId: () -> String = { "app-" + UUID.randomUUID().toString().replace("-", "").take(20) },
) {
    private val _index = MutableStateFlow(MemoIndex.EMPTY)
    val index: StateFlow<MemoIndex> = _index.asStateFlow()

    private val _indexError = MutableStateFlow(false)
    val indexError: StateFlow<Boolean> = _indexError.asStateFlow()

    private val _lists = MutableStateFlow<Map<MemoListKey, MemoListState>>(emptyMap())
    val lists: StateFlow<Map<MemoListKey, MemoListState>> = _lists.asStateFlow()

    private val _tags = MutableStateFlow<List<MemoTag>>(emptyList())
    val tags: StateFlow<List<MemoTag>> = _tags.asStateFlow()

    private val notes = mutableMapOf<String, MemoNote>()

    fun list(key: MemoListKey): MemoListState = _lists.value[key] ?: MemoListState()

    private fun updateList(key: MemoListKey, f: (MemoListState) -> MemoListState) {
        _lists.update { all -> all + (key to f(all[key] ?: MemoListState())) }
    }

    /** Reads the index once; keeps the last good one on a transient error. */
    suspend fun refreshIndex(): Boolean {
        val fresh = backend.fetchIndex()
        if (fresh == null) {
            _indexError.value = true
            return false
        }
        _indexError.value = false
        _index.value = fresh
        return true
    }

    /** Sends [op], waits at most 15 s for its answer. `app.june.memo.error` and the timeout both give [MemoResult.Failed]. */
    suspend fun request(op: MemoOp): MemoResult {
        val rid = newRequestId()
        return try {
            val response = withTimeout(timeoutMillis) { backend.call(op, rid) }
            when (response) {
                is MemoResponse.Error -> MemoResult.Failed(response.reason)
                else -> {
                    if (op.isChange) applyChange(op)
                    MemoResult.Ok(response)
                }
            }
        } catch (e: TimeoutCancellationException) {
            MemoResult.Failed("메모봇이 15초 안에 답하지 않았습니다", timedOut = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MemoResult.Failed("요청을 보내지 못했습니다 (${e.javaClass.simpleName})")
        }
    }

    /** First page of [key]. Does nothing when it is already loaded (back navigation restores it), unless [force]. */
    suspend fun loadFirst(key: MemoListKey, force: Boolean = false) {
        val current = list(key)
        if (current.loading || (current.loaded && !force && current.error == null)) return
        updateList(key) { it.copy(loading = true, error = null) }
        when (val r = request(MemoOp.list(key.folder, key.tags, key.sort, null))) {
            is MemoResult.Ok -> {
                val page = (r.response as? MemoResponse.List)?.page ?: MemoPage(emptyList(), null)
                val base = _index.value.folder(key.folder)?.count
                updateList(key) {
                    MemoListState(
                        items = page.items,
                        nextCursor = page.nextCursor,
                        endReached = page.nextCursor == null,
                        pages = 1,
                        baseline = base,
                    )
                }
            }
            is MemoResult.Failed -> updateList(key) { it.copy(loading = false, error = r.reason) }
        }
    }

    /** Next page of [key] (infinite scroll). */
    suspend fun loadMore(key: MemoListKey) {
        val current = list(key)
        if (!current.loaded || current.endReached || current.loading) return
        updateList(key) { it.copy(loading = true, error = null) }
        when (val r = request(MemoOp.list(key.folder, key.tags, key.sort, current.nextCursor))) {
            is MemoResult.Ok -> {
                val page = (r.response as? MemoResponse.List)?.page ?: MemoPage(emptyList(), null)
                updateList(key) { s ->
                    val known = s.items.map { it.id }.toSet()
                    s.copy(
                        items = s.items + page.items.filter { it.id !in known },
                        nextCursor = page.nextCursor,
                        endReached = page.nextCursor == null,
                        loading = false,
                        pages = s.pages + 1,
                    )
                }
            }
            is MemoResult.Failed -> updateList(key) { it.copy(loading = false, error = r.reason) }
        }
    }

    /** Retry after an error: the first page if nothing is loaded, else the next page. */
    suspend fun retry(key: MemoListKey) {
        val s = list(key)
        updateList(key) { it.copy(error = null) }
        if (s.loaded) loadMore(key) else loadFirst(key, force = true)
    }

    fun saveScroll(key: MemoListKey, index: Int, offset: Int) {
        if (_lists.value[key] == null) return
        updateList(key) { it.copy(scrollIndex = index, scrollOffset = offset) }
    }

    /** "새 메모 N ↑": notes added to the folder since the list was loaded (from the index count). */
    fun newCount(key: MemoListKey): Int {
        val base = list(key).baseline ?: return 0
        val now = _index.value.folder(key.folder)?.count ?: return 0
        return (now - base).coerceAtLeast(0)
    }

    /** "새 메모 N ↑" tapped: drop the cached pages and load the top again. */
    suspend fun reloadTop(key: MemoListKey) {
        _lists.update { it - key }
        loadFirst(key, force = true)
    }

    suspend fun loadTags(prefix: String = ""): List<MemoTag> {
        val r = request(MemoOp.tagList(prefix))
        val tags = ((r as? MemoResult.Ok)?.response as? MemoResponse.Tags)?.tags ?: return _tags.value
        if (prefix.isEmpty()) _tags.value = tags
        return tags
    }

    fun cachedNote(id: String): MemoNote? = notes[id]

    suspend fun loadNote(id: String): MemoResult {
        val r = request(MemoOp.get(id))
        ((r as? MemoResult.Ok)?.response as? MemoResponse.Note)?.note?.let { notes[id] = it }
        return r
    }

    /** Typed `#tag` words go to the tag filter, the rest is the free text `q`. */
    suspend fun search(query: MemoSearchQuery, cursor: String? = null): MemoResult {
        val q = query.normalized()
        return request(MemoOp.search(q.copy(text = q.text.trim()), cursor))
    }

    /** Keeps the cached lists in line with a change the server accepted (the counts follow with the next index). */
    private fun applyChange(op: MemoOp) {
        val id = op.args["id"] as? String
        fun matches(item: MemoItem) = id != null && item.id == id
        when (op.op) {
            "move" -> {
                val to = op.args["folder"] as? String ?: return
                _lists.update { all ->
                    all.mapValues { (key, s) ->
                        if (key.folder == to) s else s.copy(items = s.items.filterNot(::matches))
                    }
                }
                notes.remove(id)
            }
            "trash" -> {
                _lists.update { all -> all.mapValues { (_, s) -> s.copy(items = s.items.filterNot(::matches)) } }
                notes.remove(id)
            }
            "pin", "unpin", "dismiss_pin" -> {
                val pinned = op.op == "pin"
                _lists.update { all ->
                    all.mapValues { (_, s) ->
                        s.copy(items = s.items.map { if (matches(it)) it.copy(pinned = if (op.op == "dismiss_pin") it.pinned else pinned, pinSuggested = false) else it })
                    }
                }
                notes.remove(id)
            }
            "edit" -> {
                @Suppress("UNCHECKED_CAST")
                val tags = op.args["태그"] as? List<String>
                val title = op.args["제목"] as? String
                _lists.update { all ->
                    all.mapValues { (_, s) ->
                        s.copy(items = s.items.map { if (matches(it)) it.copy(tags = tags ?: it.tags, title = title ?: it.title) else it })
                    }
                }
                notes.remove(id)
            }
            "tag_rename", "tag_merge", "tag_delete", "tag_create" -> _tags.value = emptyList()
        }
    }
}
