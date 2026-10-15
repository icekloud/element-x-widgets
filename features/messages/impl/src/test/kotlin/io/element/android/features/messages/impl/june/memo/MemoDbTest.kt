/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MemoDbTest : RobolectricTest() {
    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private val t0 = 1_760_000_000_000L

    private fun note(
        id: String,
        folder: String = "여행",
        title: String = "제목 $id",
        body: String = "본문 $id",
        created: Long = t0,
        rev: MemoRev = MemoRev(created, "A", 1),
        tags: List<String> = emptyList(),
        deleted: Int = MEMO_DELETED_NONE,
    ) = MemoNoteRecord(
        id = id,
        folder = folder,
        title = title,
        body = body,
        kind = "텍스트",
        createdMs = created,
        revs = MemoRevs.all(rev),
        tags = tags,
        deleted = deleted
    )

    @Test
    fun `a new database has the built-in folders and nothing to upload`() {
        val db = db()
        val paths = db.folders().map { it.path }
        assertEquals(MEMO_BUILTIN_FOLDERS.map { it.first }, paths)
        assertEquals(0, db.outboxCount())
        assertTrue(db.folderCounts().isEmpty())
    }

    @Test
    fun `note round trip keeps every field, tags and attachments in order`() {
        val db = db()
        val n = note("20261012-100000-aaaa", tags = listOf("빵집", "교토")).copy(
            kind = "사진",
            body = "# 교토\n\n**빵집** [지도](https://x.y)",
            pinned = true,
            linkJson = """{"url":"https://x.y"}""",
            headerJson = """{"확신":0.4}""",
            originEvent = "\$ev",
            dirty = true,
            revs = MemoRevs(MemoRev(t0, "A", 1), MemoRev(t0 + 5_000, "B", 2), MemoRev(t0, "A", 1), MemoRev(t0, "A", 1)),
            attachments = listOf(
                MemoLocalAttachment("a1", localPath = "/data/1.jpg", mime = "image/jpeg", bytes = 10),
                MemoLocalAttachment("a2", mxc = "mxc://hs/2", state = MEMO_ATTACH_DONE),
            ),
        )
        db.putNote(n)
        val back = db.note(n.id)
        assertEquals(n, back)
        assertEquals(MemoRev(t0 + 5_000, "B", 2), back!!.modified)
        assertEquals("교토\n\n빵집 지도 https://x.y", back.bodyPlain)
        assertNull(db.note("nope"))
    }

    @Test
    fun `a version 1 database migrated to version 2 keeps its attachments and stores their next try and reason`() {
        // The database of an older APK: version 1 tables, one waiting attachment written before 앱5 (no tries, no retry time).
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MEMO_SCHEMA_V1.forEach { driver.execute(null, it, 0) }
        driver.execute(
            null,
            "INSERT INTO attachment(id, note_id, idx, local_path, state) VALUES ('a1', '20261012-100000-aaaa', 0, '/p/1.jpg', '$MEMO_ATTACH_LOCAL')",
            0,
        )
        MemoDb.Schema.migrate(driver, 1, 2)
        val db = MemoDb(driver)
        val old = db.pendingAttachments().single().second
        assertEquals(0, old.tries)
        assertEquals(0L, old.retryAtMs)
        assertNull(old.error)
        // The retry time and the reason live in the version 1 columns retry_at_ms / err: no new column was needed.
        db.updateAttachment("a1", null, null, MEMO_ATTACH_LOCAL, 1, retryAtMs = t0 + 5_000)
        assertEquals(t0 + 5_000, db.pendingAttachments().single().second.retryAtMs)
        db.updateAttachment("a1", null, null, MEMO_ATTACH_FAILED, 2, error = MEMO_ATTACH_ERR_READ)
        assertEquals(MEMO_ATTACH_ERR_READ, db.failedAttachmentReason())
        db.retryFailedAttachments()
        val back = db.pendingAttachments().single().second
        assertEquals(MemoLocalAttachment("a1", localPath = "/p/1.jpg"), back)
        // A waiting attachment is held back by a network error of another one, never moved forward.
        db.holdAttachments(t0 + 60_000)
        db.holdAttachments(t0 + 1_000)
        assertEquals(t0 + 60_000, db.pendingAttachments().single().second.retryAtMs)
    }

    @Test
    fun `list pages of 30 with a keyset cursor, no duplicates when a newer note arrives`() {
        val db = db()
        repeat(65) { i -> db.putNote(note("20261001-0000%02d-n%03d".format(i % 60, i), created = t0 + i * 1000L)) }
        val p1 = db.list("여행", emptyList(), null, null)
        assertEquals(30, p1.items.size)
        assertEquals("20261001-000004-n064", p1.items.first().id)
        assertNotNull(p1.nextCursor)
        db.putNote(note("20261012-000000-new1", created = t0 + 999_000))
        val p2 = db.list("여행", emptyList(), null, p1.nextCursor)
        val p3 = db.list("여행", emptyList(), null, p2.nextCursor)
        assertEquals(30, p2.items.size)
        assertEquals(5, p3.items.size)
        assertNull(p3.nextCursor)
        val ids = (p1.items + p2.items + p3.items).map { it.id }
        assertEquals(65, ids.toSet().size)
        assertFalse("20261012-000000-new1" in ids)
    }

    @Test
    fun `tags are AND, trash and other folders stay out of a folder list`() {
        val db = db()
        db.putNote(note("20261012-000001-aaaa", tags = listOf("빵집", "교토")))
        db.putNote(note("20261012-000002-bbbb", tags = listOf("빵집")))
        db.putNote(note("20261012-000003-cccc", tags = listOf("빵집", "교토"), deleted = MEMO_DELETED_TRASH))
        db.putNote(note("20261012-000004-dddd", folder = "아이디어", tags = listOf("빵집", "교토")))
        assertEquals(listOf("20261012-000001-aaaa"), db.list("여행", listOf("교토", "빵집"), null, null).items.map { it.id })
        assertEquals(2, db.list("여행", listOf("빵집"), null, null).items.size)
        assertEquals(listOf("20261012-000003-cccc"), db.list(MEMO_TRASH, emptyList(), null, null).items.map { it.id })
        val counts = db.folderCounts().associateBy { it.path }
        assertEquals(2, counts["여행"]?.count)
        assertEquals(1, counts[MEMO_TRASH]?.count)
        assertEquals(1, counts["아이디어"]?.count)
    }

    @Test
    fun `search uses LIKE on title, body, tags and folder, two letters are enough, hidden folders on request`() {
        val db = db()
        db.putNote(note("20261012-000001-aaaa", title = "교토 빵집", body = "가와라마치 100%"))
        db.putNote(note("20261012-000002-bbbb", title = "환전", body = "시내 환전소", tags = listOf("교토")))
        db.putNote(note("20261012-000003-cccc", folder = MEMO_INBOX, title = "교토 영상"))
        db.putNote(note("20261012-000004-dddd", title = "교토 숙소", deleted = MEMO_DELETED_TRASH))
        val r = db.search(MemoSearchQuery(text = "교토"), t0, null)
        assertEquals(setOf("20261012-000001-aaaa", "20261012-000002-bbbb"), r.items.map { it.id }.toSet())
        val first = r.items.first { it.id == "20261012-000001-aaaa" }
        assertEquals("제목", first.matchField)
        assertEquals("<mark>교토</mark> 빵집", first.snippet)
        assertEquals("태그", r.items.first { it.id == "20261012-000002-bbbb" }.matchField)
        // % and _ are plain characters, not LIKE wildcards
        assertEquals(listOf("20261012-000001-aaaa"), db.search(MemoSearchQuery(text = "100%"), t0, null).items.map { it.id })
        assertTrue(db.search(MemoSearchQuery(text = "0_"), t0, null).items.isEmpty())
        assertEquals(4, db.search(MemoSearchQuery(text = "교토", includeHidden = true), t0, null).items.size)
        // tag only: no snippet
        val tagOnly = db.search(MemoSearchQuery(tags = listOf("교토")), t0, null)
        assertEquals(listOf("20261012-000002-bbbb"), tagOnly.items.map { it.id })
        assertEquals("", tagOnly.items.single().snippet)
        assertNull(tagOnly.items.single().matchField)
    }

    @Test
    fun `search filters on kind, folder with subfolders, period and pinned`() {
        val db = db()
        db.putNote(note("20261012-000001-aaaa", folder = "여행/교토", created = t0).copy(kind = "사진", pinned = true))
        db.putNote(note("20261012-000002-bbbb", folder = "여행", created = t0 - 10 * 86_400_000L))
        db.putNote(note("20261012-000003-cccc", folder = "여행가방", created = t0))
        val trip = db.search(MemoSearchQuery(folder = "여행"), t0, null).items.map { it.id }.toSet()
        assertEquals(setOf("20261012-000001-aaaa", "20261012-000002-bbbb"), trip)
        assertEquals(listOf("20261012-000001-aaaa"), db.search(MemoSearchQuery(kinds = setOf("사진")), t0, null).items.map { it.id })
        assertEquals(2, db.search(MemoSearchQuery(period = MemoPeriod.Days7), t0, null).items.size)
        assertEquals(1, db.search(MemoSearchQuery(pinned = true), t0, null).items.size)
    }

    @Test
    fun `tag counts come from live notes`() {
        val db = db()
        db.putNote(note("20261012-000001-aaaa", tags = listOf("빵집", "교토")))
        db.putNote(note("20261012-000002-bbbb", tags = listOf("빵집")))
        db.putNote(note("20261012-000003-cccc", tags = listOf("교토"), deleted = MEMO_DELETED_TRASH))
        db.putTag(MemoTag("할일", 0, "행동", "사용자", listOf("todo")))
        assertEquals(listOf("빵집" to 2, "교토" to 1, "할일" to 0), db.tags().map { it.name to it.count })
        assertEquals(listOf("todo"), db.tags().last().synonyms)
    }

    @Test
    fun `outbox keeps one entry per note, the newest payload wins`() {
        val db = db()
        db.transaction {
            db.enqueue(MEMO_OUTBOX_NOTE, "n1", "{\"v\":1}", t0)
            db.enqueue(MEMO_OUTBOX_NOTE, "n2", "{\"v\":1}", t0 + 1)
            db.enqueue(MEMO_OUTBOX_NOTE, "n1", "{\"v\":2}", t0 + 2)
        }
        val rows = db.outbox()
        assertEquals(listOf("n1", "n2"), rows.map { it.target })
        assertEquals("{\"v\":2}", rows.first().payload)
        assertEquals(t0 + 2, rows.first().updatedMs)
    }

    @Test
    fun `change stamps of this device always grow`() {
        val db = db()
        val a = db.nextRev("DEV", t0)
        val b = db.nextRev("DEV", t0)
        assertTrue(b > a)
        assertEquals(2L, b.seq)
    }

    @Test
    fun `a write that fails leaves nothing behind`() {
        val db = db()
        val failure = assertThrows(IllegalStateException::class.java) {
            db.transaction {
                db.writeNote(note("20261012-000001-aaaa"))
                db.enqueue(MEMO_OUTBOX_NOTE, "20261012-000001-aaaa", "{}", t0)
                error("disk full")
            }
        }
        assertEquals("disk full", failure.message)
        assertNull(db.note("20261012-000001-aaaa"))
        assertEquals(0, db.outboxCount())
    }

    // ---------------------------------------------------------------- merge (설계 §c, 이벤트규약 v2 §5)

    @Test
    fun `stamps compare like the server, seconds then sequence then device`() {
        assertTrue(MemoRev(t0 + 1000, "A", 0) > MemoRev(t0 + 999, "Z", 99))
        // same second: the sequence decides, the milliseconds do not
        assertTrue(MemoRev(t0, "A", 2) > MemoRev(t0 + 500, "A", 1))
        assertTrue(MemoRev(t0, "B", 1) > MemoRev(t0, "A", 1))
        assertEquals(0, MemoRev(t0, "A", 1).compareTo(MemoRev(t0 + 10, "A", 1)))
    }

    @Test
    fun `bundles merge separately, a tag edit and a folder move are both kept`() {
        val base = note("n", tags = listOf("빵집"))
        val phoneA = base.copy(body = "고친 본문", revs = base.revs.with(MemoBundle.Body, MemoRev(t0 + 60_000, "A", 2)))
        val phoneB = base.copy(
            folder = "여행/교토",
            tags = listOf("빵집", "교토"),
            revs = base.revs.with(MemoBundle.Class, MemoRev(t0 + 30_000, "B", 1)),
        )
        val m = mergeMemoNote(phoneA, phoneB)
        assertEquals("고친 본문", m.body)
        assertEquals("여행/교토", m.folder)
        assertEquals(listOf("빵집", "교토"), m.tags)
        assertEquals(MemoRev(t0 + 60_000, "A", 2), m.modified)
        assertEquals(m, mergeMemoNote(phoneB, phoneA))
    }

    @Test
    fun `the attachment list of the newer stamp replaces the other one, photos not uploaded yet stay`() {
        // 앱10 (규약 추가 결정 3): was a union, so a photo removed on the phone came back.
        val base = note("n")
        val a = base.copy(
            attachments = listOf(
                MemoLocalAttachment("1", mxc = "mxc://x/1"),
                MemoLocalAttachment("2", mxc = "mxc://x/2"),
                MemoLocalAttachment("L", localPath = "/p/new.jpg"),
            ),
        )
        val b = base.copy(
            attachments = listOf(MemoLocalAttachment("9", mxc = "mxc://x/2"), MemoLocalAttachment("3", mxc = "mxc://x/3")),
            revs = base.revs.with(MemoBundle.Attach, MemoRev(t0 + 1000, "B", 5)),
        )
        val m = mergeMemoNote(a, b)
        assertEquals(listOf("mxc://x/2", "mxc://x/3", null), m.attachments.map { it.mxc })
        assertEquals("/p/new.jpg", m.attachments.last().localPath)
        assertEquals(m, mergeMemoNote(b, a))
    }

    @Test
    fun `same attachment stamp but a longer list - what a union server added back is dropped`() {
        // A server before 서버 5차 adds lists up under the stamp of the phone: the photo removed there stays gone.
        val stamp = MemoRev(t0 + 2000, "A", 7)
        val base = note("n")
        val phone = base.copy(attachments = listOf(MemoLocalAttachment("1", mxc = "mxc://x/1")), revs = base.revs.with(MemoBundle.Attach, stamp))
        val server = phone.copy(attachments = listOf(MemoLocalAttachment("1", mxc = "mxc://x/1"), MemoLocalAttachment("2", mxc = "mxc://x/2")))
        assertEquals(listOf("mxc://x/1"), mergeMemoNote(phone, server).attachments.map { it.mxc })
        assertEquals(mergeMemoNote(phone, server), mergeMemoNote(server, phone))
    }

    @Test
    fun `trash is a normal change, delete vs edit is last writer wins`() {
        val base = note("n")
        val trashed = base.copy(deleted = MEMO_DELETED_TRASH, revs = base.revs.with(MemoBundle.Mark, MemoRev(t0 + 10_000, "A", 2)))
        val edited = base.copy(body = "나중 수정", revs = base.revs.with(MemoBundle.Body, MemoRev(t0 + 20_000, "B", 2)))
        val m = mergeMemoNote(trashed, edited)
        assertEquals(MEMO_DELETED_TRASH, m.deleted)
        assertEquals("나중 수정", m.body)
        // tombstone: a later edit brings the note back (관리자 결정 3), an older one does not
        val tomb = base.tombstone(MemoRev(t0 + 15_000, "A", 3))
        assertEquals("나중 수정", mergeMemoNote(tomb, edited).body)
        assertEquals(MEMO_DELETED_NONE, mergeMemoNote(edited, tomb).deleted)
        val older = base.copy(body = "예전 수정", revs = base.revs.with(MemoBundle.Body, MemoRev(t0 + 5_000, "B", 1)))
        assertEquals(MEMO_DELETED_TOMBSTONE, mergeMemoNote(tomb, older).deleted)
        assertEquals("", mergeMemoNote(older, tomb).body)
        // same stamp: the tombstone stays (like the server: grave >= change)
        val same = base.copy(revs = MemoRevs.all(MemoRev(t0 + 15_000, "A", 3)))
        assertEquals(MEMO_DELETED_TOMBSTONE, mergeMemoNote(same, tomb).deleted)
    }

    @Test
    fun `merge is deterministic whatever the order, and idempotent`() {
        val rnd = Random(7)
        val folders = listOf("여행", "아이디어", MEMO_INBOX)
        fun randomRev() = MemoRev(t0 + rnd.nextLong(0, 4) * 1000 + rnd.nextLong(0, 999), listOf("A", "B")[rnd.nextInt(2)], rnd.nextLong(0, 3))
        fun random(): MemoNoteRecord {
            val n = note(
                "n",
                folder = folders[rnd.nextInt(folders.size)],
                body = "본문" + rnd.nextInt(3),
                tags = listOf("빵집", "교토", "할일").shuffled(rnd).take(rnd.nextInt(3)),
                deleted = listOf(0, 0, 1, 2)[rnd.nextInt(4)],
            ).copy(
                pinned = rnd.nextBoolean(),
                attachments = (1..rnd.nextInt(3)).map { MemoLocalAttachment("a$it", mxc = "mxc://x/${rnd.nextInt(4)}") }.distinctBy { it.key },
                revs = MemoRevs(randomRev(), randomRev(), randomRev(), randomRev()),
            )
            return if (n.deleted == MEMO_DELETED_TOMBSTONE) n.tombstone(n.revs.mark) else n
        }
        repeat(2_000) {
            val a = random()
            val b = random()
            val c = random()
            assertEquals(mergeMemoNote(a, b), mergeMemoNote(b, a))
            assertEquals(a, mergeMemoNote(a, a))
            val abc = mergeMemoNote(mergeMemoNote(a, b), c)
            assertTrue(
                memoSameNote(abc, mergeMemoNote(c, mergeMemoNote(b, a))) || abc.attachments.toSet() == mergeMemoNote(c, mergeMemoNote(b, a)).attachments.toSet()
            )
        }
    }

    // ---------------------------------------------------------------- server formulas

    @Test
    fun `content hash, segment and segment hash match the server formulas`() {
        // Values computed with the server code (june_memo_index.content_hash, june_memo_sync.bucket / segment_hash).
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", memoContentHash("", emptyList()))
        assertEquals(MEMO_HASH_SAMPLE, memoContentHash("교토 빵집", listOf("mxc://hs/a", "mxc://hs/b")))
        assertEquals(MEMO_SEGMENT_SAMPLE, memoSegment("20261012-113012-a1b2"))
        val line = memoSegmentLine("20261012-113012-a1b2", MemoRev(MEMO_SAMPLE_MS, "ABCDEF", 7))
        assertEquals("20261012-113012-a1b2:2026-10-12T11:31:00+09:00|ABCDEF|7", line)
        assertEquals(MEMO_SEGMENT_HASH_SAMPLE, memoSegmentHash(listOf(line, "20261012-090000-zzzz:2026-10-12T09:00:00+09:00|서버|0")))
    }

    @Test
    fun `protocol times are KST with seconds, same as a date formatter`() {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT+09:00")
        val rnd = Random(3)
        val samples =
            listOf(0L, -1L, MEMO_SAMPLE_MS, 951_782_400_000L, 4_107_542_399_999L) + List(500) { rnd.nextLong(-2_000_000_000_000L, 4_000_000_000_000L) }
        samples.forEach { ms -> assertEquals("$ms", f.format(java.util.Date(ms)), memoIso(ms)) }
        assertEquals("2026-10-12T11:31:00+09:00", memoIso(MEMO_SAMPLE_MS))
    }

    @Test
    fun `segment summary covers every note once`() {
        val db = db()
        repeat(100) { i -> db.putNote(note("20261012-0000%02d-s%03d".format(i % 60, i))) }
        val summary = db.segmentSummary()
        assertEquals(100, summary.values.sumOf { it.second })
        assertTrue(summary.keys.all { it in 0 until MEMO_SEGMENTS })
    }

    @Test
    fun `a phone note id has the protocol format`() {
        val id = memoNewId(MEMO_SAMPLE_MS) { 0 }
        assertEquals("20261012-113100-aaaa", id)
        assertTrue(Regex("^\\d{8}-\\d{6}-[a-z0-9]{4}$").matches(memoNewId(t0)))
    }

    // ---------------------------------------------------------------- budget (설계 §k-5, 2x margin)

    @Test
    fun `1000 notes - search, first page, one save and the segment hashes stay within the budget`() {
        val db = db()
        db.transaction {
            repeat(1_000) { i ->
                db.writeNote(
                    note(
                        "20261001-%06d-p%03d".format(i, i % 1000),
                        folder = listOf("여행", "아이디어")[i % 2],
                        body = "메모 본문 $i 가와라마치",
                        tags = listOf("t${i % 7}"),
                    )
                )
            }
        }
        // Best of 5 after a warm-up run: the cost of the work, not of a GC pause or the JIT on a busy test machine.
        fun ms(f: () -> Unit): Long {
            f()
            return (1..5).minOf {
                val start = System.nanoTime()
                f()
                (System.nanoTime() - start) / 1_000_000
            }
        }
        val search = ms { db.search(MemoSearchQuery(text = "라마"), t0, null) }
        val first = ms { db.list("여행", emptyList(), null, null) }
        var k = 0
        val save = ms {
            db.transaction {
                db.writeNote(note("20261012-999999-x%03d".format(k++)))
                db.enqueue(MEMO_OUTBOX_NOTE, "x$k", "{}", t0)
            }
        }
        val segments = ms { db.segmentSummary() }
        assertTrue("search $search ms", search <= 200)
        assertTrue("first page $first ms", first <= 50)
        assertTrue("save $save ms", save <= 20)
        assertTrue("segments $segments ms", segments <= 50)
    }
}

// 2026-10-12T11:31:00+09:00, and the server values for the samples (scratch script 서버식_기댓값.py, same formulas as the server).
private const val MEMO_SAMPLE_MS = 1_791_772_260_000L
private const val MEMO_HASH_SAMPLE = "229ca9f3616f8aa680b1cae1ba527230d0ff6f03"
private const val MEMO_SEGMENT_SAMPLE = 7
private const val MEMO_SEGMENT_HASH_SAMPLE = "d755f2f06dea"
