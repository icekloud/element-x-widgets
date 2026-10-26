/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.ClosedChannelException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Element June: how the files of the voice of the app are copied to the phone, and the one line that says why the voice could not
 * be loaded, shown in the settings.
 */
class JuneVoiceLoadTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a native library that cannot be loaded is reported, not thrown through`() {
        val error = assertThrows(JuneVoiceLoadException::class.java) {
            juneVoiceStep(JuneVoiceLoadStep.Environment, null) { throw UnsatisfiedLinkError("dlopen failed: library \"libsamsungtts.so\" not found") }
        }
        assertThat(error.failure.reason).isEqualTo("환경 생성 · UnsatisfiedLinkError: dlopen failed: library \"libsamsungtts.so\" not found")
    }

    @Test
    fun `an out of memory error while copying is reported as memory`() {
        val error = assertThrows(JuneVoiceLoadException::class.java) {
            juneVoiceStep(JuneVoiceLoadStep.Copy, "regular.ivc.smt") { throw OutOfMemoryError("Failed to allocate") }
        }
        assertThat(error.failure.reason).isEqualTo("메모리 regular.ivc.smt · OutOfMemoryError: Failed to allocate")
    }

    @Test
    fun `a cancellation and other errors are not swallowed`() {
        assertThrows(CancellationException::class.java) {
            juneVoiceStep(JuneVoiceLoadStep.Copy, "cfg.smt") { throw CancellationException("stopped") }
        }
        assertThrows(StackOverflowError::class.java) {
            juneVoiceStep(JuneVoiceLoadStep.Copy, "cfg.smt") { throw StackOverflowError() }
        }
        assertThat(juneVoiceIsRecoverable(OutOfMemoryError())).isTrue()
        assertThat(juneVoiceIsRecoverable(NoClassDefFoundError("com/sun/jna/Native"))).isTrue()
        assertThat(juneVoiceIsRecoverable(IllegalStateException())).isTrue()
        assertThat(juneVoiceIsRecoverable(StackOverflowError())).isFalse()
        assertThat(juneVoiceIsRecoverable(CancellationException())).isFalse()
    }

    @Test
    fun `a step already reported keeps its own step`() {
        val error = assertThrows(JuneVoiceLoadException::class.java) {
            juneVoiceStep(JuneVoiceLoadStep.Environment, "ko_KR_l08") {
                juneVoiceStep(JuneVoiceLoadStep.Copy, "cfg.smt") { throw IllegalArgumentException("broken") }
            }
        }
        assertThat(error.failure.step).isEqualTo(JuneVoiceLoadStep.Copy)
        assertThat(error.failure.file).isEqualTo("cfg.smt")
    }

    @Test
    fun `the reason never shows a path, an address nor a user id`() {
        val message = "open failed: ENOENT /data/user/0/io.element.android.x/files/samsung_tts/ko_KR_l08/cfg.smt for @someone:example.org " +
            "see https://example.org/x?y=1"
        val text = juneVoiceErrorText(IOException(message))
        assertThat(text).doesNotContain("/data")
        assertThat(text).doesNotContain("files/")
        assertThat(text).doesNotContain("example.org")
        assertThat(text).doesNotContain("@someone")
        assertThat(text).startsWith("IOException: open failed: ENOENT <경로>")
    }

    @Test
    fun `the error part of the reason is at most 80 characters and on one line`() {
        val text = juneVoiceErrorText(IllegalStateException("a".repeat(200) + "\nsecond line"))
        assertThat(text.length).isEqualTo(JUNE_VOICE_REASON_MAX_LENGTH)
        assertThat(text).endsWith("…")
        assertThat(text).doesNotContain("\n")
        assertThat(juneVoiceErrorText(IllegalStateException("one\n  two"))).isEqualTo("IllegalStateException: one two")
    }

    @Test
    fun `an error without a message gives its name only`() {
        assertThat(juneVoiceErrorText(ClosedChannelException())).isEqualTo("ClosedChannelException")
        assertThat(juneVoiceErrorText(IOException("  "))).isEqualTo("IOException")
    }

    @Test
    fun `a copy is written under a temporary name and renamed once complete`() {
        val target = folder.root.resolve("regular.ivc.smt")
        var seenPartial = false
        val result = juneVoiceCopy(target, expectedLength = 5, usableSpace = { Long.MAX_VALUE }) {
            object : InputStream() {
                private val data = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
                override fun read(): Int {
                    seenPartial = seenPartial || (folder.root.resolve("regular.ivc.smt$JUNE_VOICE_PARTIAL_SUFFIX").exists() && !target.exists())
                    return data.read()
                }
            }
        }
        assertThat(result).isEqualTo(target)
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(seenPartial).isTrue()
        assertThat(folder.root.resolve("regular.ivc.smt$JUNE_VOICE_PARTIAL_SUFFIX").exists()).isFalse()
    }

    @Test
    fun `a copy cut in the middle is not kept`() {
        val target = folder.root.resolve("regular.ivc.smt")
        assertThrows(IOException::class.java) {
            juneVoiceCopy(target, expectedLength = 10, usableSpace = { Long.MAX_VALUE }) {
                object : InputStream() {
                    private var count = 0
                    override fun read(): Int = if (count++ < 3) 7 else throw IOException("read interrupted")
                }
            }
        }
        assertThat(target.exists()).isFalse()
        assertThat(folder.root.resolve("regular.ivc.smt$JUNE_VOICE_PARTIAL_SUFFIX").exists()).isFalse()
    }

    @Test
    fun `a copy shorter than the length of the asset is not kept`() {
        val target = folder.root.resolve("regular.ivc.smt")
        val error = assertThrows(IOException::class.java) {
            juneVoiceCopy(target, expectedLength = 10, usableSpace = { Long.MAX_VALUE }) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        }
        assertThat(error.message).isEqualTo("Copied 3 of 10 bytes")
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a complete copy of the right length is used again without reading the asset`() {
        val target = folder.root.resolve("regular.ivc.smt").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = juneVoiceCopy(target, expectedLength = 3, usableSpace = { 0 }) { error("must not be read") }
        assertThat(result.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a copy of the wrong length is made again`() {
        val target = folder.root.resolve("regular.ivc.smt").apply { writeBytes(byteArrayOf(9)) }
        juneVoiceCopy(target, expectedLength = 3, usableSpace = { Long.MAX_VALUE }) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `nothing is copied when there is not enough room`() {
        val target = folder.root.resolve("regular.ivc.smt")
        val size = 256L * 1024 * 1024
        val error = assertThrows(JuneVoiceStorageException::class.java) {
            juneVoiceCopy(target, expectedLength = size, usableSpace = { size }) { error("must not be read") }
        }
        assertThat(error.neededBytes).isEqualTo(size + JUNE_VOICE_COPY_MARGIN_BYTES)
        assertThat(juneVoiceErrorText(error)).isEqualTo("저장공간 부족(필요 356MB · 남음 256MB)")
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a copy of unknown size asks for room for the biggest file`() {
        assertThat(juneVoiceCopyNeededBytes(null)).isEqualTo(JUNE_VOICE_UNKNOWN_COPY_BYTES + JUNE_VOICE_COPY_MARGIN_BYTES)
        assertThat(juneVoiceCopyNeededBytes(-1)).isEqualTo(JUNE_VOICE_UNKNOWN_COPY_BYTES + JUNE_VOICE_COPY_MARGIN_BYTES)
        assertThat(juneVoiceCopyNeededBytes(10)).isEqualTo(10 + JUNE_VOICE_COPY_MARGIN_BYTES)
    }
}
