/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory memo server following 이벤트규약 v1: used by the unit tests, the previews and to try the
 * screens before the server module exists. Not used for a real room unless the memo room is set to the fake one.
 */
internal class FakeMemoBackend(
    notes: List<SampleNote> = sampleNotes(),
    folders: List<MemoFolder> = sampleFolders(),
    proposals: List<MemoProposal> = sampleProposals(),
    tags: List<MemoTag> = sampleTags(),
    private val latencyMillis: Long = 0,
) : MemoBackend {
    data class SampleNote(
        val item: MemoItem,
        val body: String = item.title,
        val attachments: List<String> = emptyList(),
    )

    val notes = notes.toMutableList()
    val folders = folders.toMutableList()
    val proposals = proposals.toMutableList()
    val tags = tags.toMutableList()

    /** Every op received, in order (tests check what the screens sent). */
    val received = mutableListOf<JSONObject>()

    /** When true, [call] never answers (timeout tests); [fetchIndex] fails while [offline]. */
    var silent = false
    var offline = false

    /** Op names that answer `app.june.memo.error`. */
    val failing = mutableSetOf<String>()

    override suspend fun fetchIndex(): MemoIndex? {
        if (latencyMillis > 0) delay(latencyMillis)
        if (offline) return null
        val counted = folders.map { f ->
            val direct = notes.filter { it.item.folder == f.path }
            f.copy(
                count = direct.size,
                total = notes.count { it.item.folder == f.path || it.item.folder.startsWith(f.path + "/") },
                pinnedCount = direct.count { it.item.pinned },
                recent = direct.sortedByDescending { it.item.date }.take(3).map { it.item.title },
            )
        }
        return MemoIndex(counted, proposals.filter { it.status == "대기" || it.status == "재검토" })
    }

    override suspend fun call(op: MemoOp, requestId: String): MemoResponse {
        received += op.toJson(requestId)
        if (latencyMillis > 0) delay(latencyMillis)
        if (silent || offline) awaitCancellation()
        if (op.op in failing) return MemoResponse.Error(requestId, op.op, "가짜 오류: ${op.op}")
        val a = op.args
        return when (op.op) {
            "list" -> list(requestId, a["folder"] as String, strings(a["tags"]), a["cursor"] as? String)
            "get" -> get(requestId, a["id"] as String)
            "search" -> search(requestId, a["q"] as? String ?: "", a["filters"] as? Map<*, *> ?: emptyMap<String, Any>(), a["cursor"] as? String)
            "tag_list" -> {
                val p = a["prefix"] as? String ?: ""
                MemoResponse.Tags(requestId, tags.filter { p.isEmpty() || it.name.contains(p) }.sortedByDescending { it.count })
            }
            else -> change(requestId, op)
        }
    }

    private fun strings(v: Any?): List<String> = (v as? List<*>)?.map { it.toString() } ?: emptyList()

    private fun find(op: MemoOp): Int {
        val id = op.args["id"] as? String
        val event = op.args["이벤트"] as? String
        return notes.indexOfFirst { if (id != null) it.item.id == id else event != null && it.item.id == "ev:$event" }
    }

    private fun list(rid: String, folder: String, tags: List<String>, cursor: String?): MemoResponse {
        val all = notes.map { it.item }
            .filter { it.folder == folder && tags.all { t -> t in it.tags } }
            .sortedByDescending { it.date }
        val start = cursor?.toIntOrNull() ?: 0
        val page = all.drop(start).take(MEMO_PAGE_SIZE)
        val next = if (start + page.size < all.size) (start + page.size).toString() else null
        return MemoResponse.List(rid, MemoPage(page, next))
    }

    private fun get(rid: String, id: String): MemoResponse {
        val n = notes.firstOrNull { it.item.id == id } ?: return MemoResponse.Error(rid, "get", "노트를 찾지 못했습니다")
        val header = JSONObject()
            .put("id", n.item.id)
            .put("생성", n.item.date)
            .put("폴더", n.item.folder)
            .put("태그", JSONArray(n.item.tags))
            .put("고정", n.item.pinned)
            .put("고정제안", n.item.pinSuggested)
        n.item.scheduleState?.let { header.put("일정상태", it) }
        return MemoResponse.Note(rid, MemoNote(n.item.id, header, n.body, n.attachments.map { MemoAttachment(it) }, truncated = false))
    }

    private fun search(rid: String, q: String, filters: Map<*, *>, cursor: String?): MemoResponse {
        val words = q.split(' ').filter { it.isNotBlank() }
        val tagWords = words.filter { it.startsWith("#") }.map { it.removePrefix("#") }
        val textWords = words.filter { !it.startsWith("#") }
        val tags = strings(filters["tags"]) + tagWords
        val kinds = strings(filters["종류"])
        val folder = filters["폴더"] as? String
        val include = strings(filters["포함"])
        val found = notes.filter { n ->
            val it = n.item
            (it.folder != MEMO_INBOX || MEMO_INBOX in include) && (it.folder != MEMO_TRASH || MEMO_TRASH in include) &&
                tags.all { t -> t in it.tags } &&
                (kinds.isEmpty() || it.kind in kinds) &&
                (folder == null || it.folder == folder || it.folder.startsWith("$folder/")) &&
                (filters["고정"] != true || it.pinned) &&
                (filters["일정연결"] != true || it.hasSchedule) &&
                textWords.all { w -> it.title.contains(w) || n.body.contains(w) }
        }.sortedByDescending { it.item.date }
        val start = cursor?.toIntOrNull() ?: 0
        val page = found.drop(start).take(50).map { n ->
            val w = textWords.firstOrNull()
            val snippet = if (w == null) "" else n.body.replace(w, "<mark>$w</mark>").take(80)
            n.item.copy(snippet = snippet, matchField = w?.let { "본문" })
        }
        return MemoResponse.Search(rid, MemoPage(page, if (start + page.size < found.size) (start + page.size).toString() else null))
    }

    private fun change(rid: String, op: MemoOp): MemoResponse {
        val a = op.args
        fun ok(id: String? = a["id"] as? String, path: String? = null) = MemoResponse.Ok(rid, op.op, id, path)
        fun err(reason: String) = MemoResponse.Error(rid, op.op, reason)
        fun update(f: (MemoItem) -> MemoItem): MemoResponse {
            val i = find(op)
            if (i < 0) return err("노트를 찾지 못했습니다")
            notes[i] = notes[i].copy(item = f(notes[i].item))
            return ok(notes[i].item.id)
        }
        return when (op.op) {
            "move" -> {
                val to = a["folder"] as String
                if (folders.none { it.path == to }) err("폴더가 없습니다: $to") else update { it.copy(folder = to) }
            }
            "edit" -> update { item ->
                item.copy(tags = strings(a["태그"]).takeIf { a["태그"] != null } ?: item.tags, title = a["제목"] as? String ?: item.title)
            }.also {
                val body = a["본문"] as? String
                val i = find(op)
                if (body != null && i >= 0) notes[i] = notes[i].copy(body = body)
            }
            "pin" -> update { it.copy(pinned = true, pinSuggested = false) }
            "unpin" -> update { it.copy(pinned = false) }
            "dismiss_pin" -> update { it.copy(pinSuggested = false) }
            "trash" -> update { it.copy(folder = MEMO_TRASH) }
            "restore" -> update { it.copy(folder = MEMO_INBOX) }
            "mkdir" -> {
                val path = (a["path"] as String).trim('/')
                if (folders.any { it.path == path }) {
                    err("이미 있는 폴더입니다")
                } else {
                    folders += MemoFolder(path, a["icon"] as? String ?: "📁", 0, source = "사용자")
                    ok(null, path)
                }
            }
            "rename_dir" -> {
                val from = a["path"] as String
                val new = a["new"] as String
                val parent = from.substringBeforeLast('/', "")
                val to = if ('/' in new || parent.isEmpty()) new else "$parent/$new"
                val i = folders.indexOfFirst { it.path == from }
                if (i < 0) {
                    err("폴더가 없습니다")
                } else {
                    folders[i] = folders[i].copy(path = to)
                    notes.replaceAll { n -> if (n.item.folder == from) n.copy(item = n.item.copy(folder = to)) else n }
                    ok(null, to)
                }
            }
            "rmdir" -> {
                val path = a["path"] as String
                notes.replaceAll { n -> if (n.item.folder == path) n.copy(item = n.item.copy(folder = MEMO_TRASH)) else n }
                folders.removeAll { it.path == path }
                ok(null, path)
            }
            "tag_create" -> {
                tags += MemoTag(a["name"] as String, 0, a["axis"] as? String, "사용자", emptyList())
                ok(null)
            }
            "tag_rename" -> {
                val old = a["old"] as String
                val new = a["new"] as String
                tags.replaceAll { if (it.name == old) it.copy(name = new) else it }
                notes.replaceAll { n -> n.copy(item = n.item.copy(tags = n.item.tags.map { if (it == old) new else it })) }
                ok(null)
            }
            "tag_merge" -> {
                val targets = strings(a["targets"])
                val into = a["into"] as String
                tags.removeAll { it.name in targets && it.name != into }
                notes.replaceAll { n -> n.copy(item = n.item.copy(tags = n.item.tags.map { if (it in targets) into else it }.distinct())) }
                ok(null)
            }
            "tag_delete" -> {
                val name = a["name"] as String
                tags.removeAll { it.name == name }
                notes.replaceAll { n -> n.copy(item = n.item.copy(tags = n.item.tags - name)) }
                ok(null)
            }
            "accept" -> {
                val id = a["id"] as String
                val p = proposals.firstOrNull { it.id == id } ?: return err("제안을 찾지 못했습니다")
                proposals.remove(p)
                if (p.isFolderCreate) {
                    val path = a["folder"] as? String ?: p.folder ?: return err("폴더 이름이 없습니다")
                    if (folders.none { it.path == path }) folders += MemoFolder(path, "📁", 0, source = "사용자")
                    ok(id, path)
                } else {
                    ok(id)
                }
            }
            "dismiss" -> {
                proposals.removeAll { it.id == a["id"] }
                ok()
            }
            "defer" -> {
                val id = a["id"] as String
                val i = proposals.indexOfFirst { it.id == id }
                if (i < 0) {
                    err("제안을 찾지 못했습니다")
                } else {
                    if (a["날짜"] != null) proposals[i] = proposals[i].copy(status = "재검토", date = a["날짜"] as String) else proposals.removeAt(i)
                    ok()
                }
            }
            else -> err("모르는 op: ${op.op}")
        }
    }

    companion object {
        fun sampleFolders() = listOf(
            MemoFolder(MEMO_INBOX, "📥", 0, unfiledCount = 2),
            MemoFolder("여행", "✈️", 0),
            MemoFolder("여행/교토", "✈️", 0),
            MemoFolder("쇼핑·구매", "🛒", 0),
            MemoFolder("아이디어", "💡", 0),
            MemoFolder("집·생활", "🏠", 0),
            MemoFolder("일·공부", "📚", 0),
            MemoFolder("사람", "👤", 0),
            MemoFolder("링크보관", "🔗", 0),
            MemoFolder("반려동물", "🐾", 0, source = "사용자"),
            MemoFolder(MEMO_ARCHIVE, "📦", 0),
            MemoFolder(MEMO_TRASH, "🗑", 0),
        )

        fun sampleProposals() = listOf(
            MemoProposal(
                id = "20261011-143012-a1b2",
                kind = "일정추가",
                summary = "10/5(일) 10:30 — 교토 ○○베이커리",
                status = "대기",
                noteId = "20261011-143012-a1b2",
                date = "2026-10-05",
                time = "10:30",
            ),
            MemoProposal(id = "p-folder-1", kind = "폴더생성", summary = "영상 5건 → '영상 나중에' 폴더를 만들까요?", status = "대기", folder = "영상"),
        )

        fun sampleTags() = listOf(
            MemoTag("교토", 8, "장소", "봇", listOf("Kyoto")),
            MemoTag("오사카", 5, "장소", "봇", emptyList()),
            MemoTag("빵집", 3, "주제", "봇", listOf("베이커리")),
            MemoTag("카레", 2, "주제", "봇", emptyList()),
            MemoTag("화장수", 1, "주제", "봇", emptyList()),
            MemoTag("방문후보", 6, "행동", "봇", emptyList()),
            MemoTag("구매후보", 2, "행동", "봇", emptyList()),
            MemoTag("할일", 4, "행동", "사용자", emptyList()),
            MemoTag("이동", 3, "주제", "봇", emptyList()),
            MemoTag("이자카야", 1, "주제", "봇", emptyList()),
            MemoTag("이번주", 2, "시간", "봇", emptyList()),
            MemoTag("10월5일", 1, "시간", "봇", emptyList()),
        )

        private fun item(
            id: String,
            kind: String,
            title: String,
            date: String,
            folder: String,
            tags: List<String>,
            preview: MemoPreview?,
            pinned: Boolean = false,
            pinSuggested: Boolean = false,
            schedule: String? = null,
        ) = MemoItem(id, kind, title, preview, date, tags, folder, pinned, pinSuggested, schedule)

        fun sampleNotes(): List<SampleNote> {
            val base = listOf(
                SampleNote(
                    item(
                        "20261011-143012-a1b2",
                        "링크",
                        "○○베이커리",
                        "2026-10-11T14:30:12+09:00",
                        "여행",
                        listOf("빵집", "교토", "10월5일", "방문후보"),
                        MemoPreview.Link("https://tabelog.com/kyoto/A2601/", "교토 ○○베이커리 — 타베로그 3.72", null, "tabelog"),
                        schedule = "제안있음",
                    ),
                    body = "# ○○베이커리\n\n교토 ○○베이커리 — 타베로그 3.72\n\n## 링크 요약\n가와라마치에서 도보 8분. 오전 7시 개점.",
                ),
                SampleNote(
                    item(
                        "20261011-101500-c3d4",
                        "텍스트",
                        "환전 10만엔 더",
                        "2026-10-11T10:15:00+09:00",
                        "여행",
                        listOf("환전", "할일", "준비"),
                        MemoPreview.Text("환전 10만엔 더. 공항보다 시내 환전소가 수수료 낮음. 가와라마치 ○○환전소 10~19시…"),
                        schedule = "요청됨",
                    ),
                    body = "환전 10만엔 더. 공항보다 시내 환전소가 수수료 낮음. 가와라마치 ○○환전소 10~19시.",
                ),
                SampleNote(
                    item(
                        "20261002-180000-e5f6",
                        "음성",
                        "가와라마치 역 출구",
                        "2026-10-02T18:00:00+09:00",
                        "여행",
                        listOf("이동", "교토", "길안내"),
                        MemoPreview.Text("🎤 가와라마치 역 북쪽 출구로 나가면 바로 상점가, 왼쪽으로…"),
                    ),
                    body = "## 음성 전사\n가와라마치 역 북쪽 출구로 나가면 바로 상점가, 왼쪽으로 3분.",
                ),
                SampleNote(
                    item(
                        "20260930-090000-g7h8",
                        "텍스트",
                        "숙소 체크인 15:00",
                        "2026-09-30T09:00:00+09:00",
                        "여행",
                        listOf("교토", "숙소"),
                        MemoPreview.Text("체크인 15:00 · 가와라마치 도보 6분"),
                        pinned = true,
                    ),
                    body = "체크인 15:00 · 가와라마치 도보 6분",
                ),
                SampleNote(
                    item(
                        "20261002-120000-i9j0",
                        "사진",
                        "카레집 카운터",
                        "2026-10-02T12:00:00+09:00",
                        "여행",
                        listOf("카레", "오사카", "방문완료"),
                        MemoPreview.Photo("mxc://example/curry", 3),
                        pinSuggested = true,
                    ),
                    attachments = listOf("mxc://example/curry", "mxc://example/curry2", "mxc://example/curry3"),
                ),
                SampleNote(
                    item(
                        "20261011-120000-k1l2",
                        "사진",
                        "하다라보 화장수 라벨",
                        "2026-10-11T12:00:00+09:00",
                        "쇼핑·구매",
                        listOf("화장수", "사진", "구매후보"),
                        MemoPreview.Photo("mxc://example/label", 2),
                    ),
                ),
                SampleNote(
                    item(
                        "20261011-130000-m3n4",
                        "링크",
                        "그 영상 나중에",
                        "2026-10-11T13:00:00+09:00",
                        MEMO_INBOX,
                        listOf("영상"),
                        MemoPreview.Link("https://youtu.be/x", null, null, "YouTube"),
                    ),
                ),
            )
            // A long folder to try the infinite scroll (30 + 30 + 5).
            val many = (1..65).map { n ->
                SampleNote(
                    item(
                        "20260901-0000%02d-zz%02d".format(n % 60, n),
                        "텍스트",
                        "링크 모음 $n",
                        "2026-09-%02dT08:00:00+09:00".format(30 - n % 29),
                        "링크보관",
                        listOf("모음", if (n % 2 == 0) "짝수" else "홀수"),
                        MemoPreview.Text("저장한 링크 $n 번"),
                    ),
                )
            }
            return base + many
        }
    }
}
