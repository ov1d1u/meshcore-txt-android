package io.meshcore.teletext

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class TeletextProtocolTest {
    @Test fun sharedFixtures() {
        val input = javaClass.getResourceAsStream("/protocol.tsv") ?: error("missing fixtures")
        input.bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") }.forEach { line ->
                val parts = line.split('\t')
                val response = TeletextProtocol.parse(parts[0]) ?: error("could not parse $line")
                assertEquals(parts[2], response.id)
                when (parts[1]) {
                    "D" -> {
                        assertTrue(response is TeletextProtocol.Data)
                        val data = response as TeletextProtocol.Data
                        val count = data.count?.let { "/$it" } ?: ""
                        assertEquals(parts[3], "${data.sequence}$count:${data.text}")
                    }
                    "E" -> {
                        assertTrue(response is TeletextProtocol.End)
                        val end = response as TeletextProtocol.End
                        assertEquals(parts[3], "${end.count}:${"%08X".format(end.crc32)}")
                    }
                    "X" -> assertEquals(parts[3], (response as TeletextProtocol.Error).code)
                }
            }
        }
    }

    @Test fun requestsAndMalformedFrames() {
        assertEquals("T1I12AB", TeletextProtocol.request("12ab"))
        assertEquals("T1P12AB100", TeletextProtocol.request("12ab", 100))
        assertEquals("T1P12AB999", TeletextProtocol.request("12ab", 999))
        assertEquals("T1R12AB.Z", TeletextProtocol.retryChunk("12ab", 35))
        assertNull(TeletextProtocol.parse("T1DABCD.0:old"))
        assertNull(TeletextProtocol.parse("T1DABCD.1.2:wrong"))
        assertNull(TeletextProtocol.parse("T1EABCD.1.NOTHEX!!"))
        assertNull(TeletextProtocol.parse("T1DABCD.0:" + "x".repeat(100)))
    }

    private fun compressedFrame(raw: ByteArray, suffix: ByteArray = byteArrayOf()): String {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(raw) }
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(output.toByteArray() + suffix)
        return "T1ZABCD.0.1:$payload"
    }

    @Test fun compressedFramesRejectMalformedOrOversizedPayloads() {
        val valid = compressedFrame("valid".toByteArray())
        assertEquals("valid", (TeletextProtocol.parse(valid) as TeletextProtocol.Data).text)
        assertNull(TeletextProtocol.parse("T1ZABCD.0.1:"))
        assertNull(TeletextProtocol.parse("T1ZABCD.0.1:!"))
        assertNull(TeletextProtocol.parse("T1ZABCD.0.1:A"))
        assertNull(TeletextProtocol.parse(valid.dropLast(3)))
        assertNull(TeletextProtocol.parse(compressedFrame("valid".toByteArray(), "trailing".toByteArray())))
        assertNull(TeletextProtocol.parse(compressedFrame("x".repeat(1025).toByteArray())))
        assertNull(TeletextProtocol.parse(compressedFrame(byteArrayOf(0xff.toByte()))))
        assertEquals("x".repeat(1024),
            (TeletextProtocol.parse(compressedFrame("x".repeat(1024).toByteArray()))
                as TeletextProtocol.Data).text)
    }

    @Test fun mixedFramesCompleteAfterMissingCompressedChunkArrives() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            val first = "Hello Hello Hello Hello Hello Hello Hello!"
            val last = " plain ending"
            val crc = CRC32().apply { update((first + last).toByteArray()) }.value
            TransferAssembler(directory, "ABCD").use { transfer ->
                val plain = TeletextProtocol.parse("T1DABCD.1:$last")!!
                assertTrue(transfer.accept(plain) is TransferAssembler.Result.Progress)
                assertTrue(transfer.accept(TeletextProtocol.End("ABCD", 2, crc))
                    is TransferAssembler.Result.Progress)
                assertEquals(0L, transfer.firstMissingChunk())
                val compressed = TeletextProtocol.parse(
                    "T1ZABCD.0.2:eJzzSM3JyVfwIEwqAgA7Tw6O")!!
                val result = transfer.accept(compressed)
                assertTrue(result is TransferAssembler.Result.Complete)
                val file = (result as TransferAssembler.Result.Complete).file
                assertEquals(first + last, file.readText())
                file.delete()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun outOfOrderDuplicateAndCompletion() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            val raw = "Héllo 🌍".toByteArray()
            val crc = CRC32().apply { update(raw) }.value
            TransferAssembler(directory, "ABCD").use { transfer ->
                assertEquals(TransferAssembler.Result.Ignored,
                    transfer.accept(TeletextProtocol.Data("BEEF", 0, "wrong")))
                assertTrue(transfer.accept(TeletextProtocol.Data("ABCD", 1, " 🌍")) is TransferAssembler.Result.Progress)
                assertTrue(transfer.accept(TeletextProtocol.Data("ABCD", 1, " 🌍")) is TransferAssembler.Result.Progress)
                assertTrue(transfer.accept(TeletextProtocol.End("ABCD", 2, crc)) is TransferAssembler.Result.Progress)
                assertEquals(0L, transfer.firstMissingChunk())
                val result = transfer.accept(TeletextProtocol.Data("ABCD", 0, "Héllo", 2))
                assertTrue(result is TransferAssembler.Result.Complete)
                val file = (result as TransferAssembler.Result.Complete).file
                assertArrayEquals(raw, file.readBytes())
                file.delete()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingEndConflictAndChecksumFailure() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            TransferAssembler(directory, "ABCD").use { transfer ->
                assertTrue(transfer.accept(TeletextProtocol.Data("ABCD", 0, "x")) is TransferAssembler.Result.Progress)
                assertTrue(transfer.accept(TeletextProtocol.Data("ABCD", 0, "y")) is TransferAssembler.Result.Failure)
            }
            TransferAssembler(directory, "ABCD").use { transfer ->
                transfer.accept(TeletextProtocol.Data("ABCD", 0, "x"))
                assertTrue(transfer.accept(TeletextProtocol.End("ABCD", 1, 0)) is TransferAssembler.Result.Failure)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun firstChunkAnnouncesTotalAndMissingChunkCanBeIdentified() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            TransferAssembler(directory, "ABCD").use { transfer ->
                val first = transfer.accept(TeletextProtocol.Data("ABCD", 0, "first", 3))
                assertEquals(3L, (first as TransferAssembler.Result.Progress).total)
                assertEquals(1L, transfer.firstMissingChunk())
                transfer.accept(TeletextProtocol.Data("ABCD", 2, "third"))
                assertEquals(1L, transfer.firstMissingChunk())
                assertTrue(transfer.accept(TeletextProtocol.End("ABCD", 2, 0))
                    is TransferAssembler.Result.Failure)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun emptyPage() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            TransferAssembler(directory, "0001").use { transfer ->
                val result = transfer.accept(TeletextProtocol.End("0001", 0, 0))
                assertTrue(result is TransferAssembler.Result.Complete)
                (result as TransferAssembler.Result.Complete).file.delete()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun wholeRequestRetryIgnoresOldChunks() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            TransferAssembler(directory, "0001").use { first ->
                assertTrue(first.accept(TeletextProtocol.Data("0001", 0, "partial")) is TransferAssembler.Result.Progress)
            }
            TransferAssembler(directory, "0002").use { retry ->
                assertEquals(TransferAssembler.Result.Ignored,
                    retry.accept(TeletextProtocol.End("0001", 1, 0)))
                val crc = CRC32().apply { update("complete".toByteArray()) }.value
                retry.accept(TeletextProtocol.Data("0002", 0, "complete"))
                val result = retry.accept(TeletextProtocol.End("0002", 1, crc))
                assertTrue(result is TransferAssembler.Result.Complete)
                (result as TransferAssembler.Result.Complete).file.delete()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun largePageReassemblesFromDisk() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            val text = "# Large page\n" + "line 🌍\n".repeat(6000)
            val bytes = text.toByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            TransferAssembler(directory, "BEEF").use { transfer ->
                var sequence = 0L
                for (line in text.split('\n')) {
                    if (line.isEmpty()) continue
                    val result = transfer.accept(TeletextProtocol.Data("BEEF", sequence++, "$line\n"))
                    assertTrue(result is TransferAssembler.Result.Progress)
                }
                val result = transfer.accept(TeletextProtocol.End("BEEF", sequence, crc))
                assertTrue(result is TransferAssembler.Result.Complete)
                val file = (result as TransferAssembler.Result.Complete).file
                assertArrayEquals(bytes, file.readBytes())
                file.delete()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun previewShowsArrivedChunksAndReplacesMissingGap() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            val fullText = "first\nsecond\nthird\nfourth\n"
            val crc = CRC32().apply { update(fullText.toByteArray()) }.value
            TransferAssembler(directory, "ABCD").use { transfer ->
                transfer.accept(TeletextProtocol.Data("ABCD", 0, "first\n"))
                assertEquals("first\n", transfer.preview(0)?.text)

                transfer.accept(TeletextProtocol.Data("ABCD", 2, "third\n"))
                assertEquals("first\n${MISSING_CHUNK_MARKER}\nthird\n", transfer.preview(0)?.text)

                transfer.accept(TeletextProtocol.End("ABCD", 4, crc))
                assertEquals("first\n${MISSING_CHUNK_MARKER}\nthird\n${MISSING_CHUNK_MARKER}\n",
                    transfer.preview(0)?.text)

                transfer.accept(TeletextProtocol.Data("ABCD", 1, "second\n"))
                assertEquals("first\nsecond\nthird\n${MISSING_CHUNK_MARKER}\n",
                    transfer.preview(0)?.text)

                val complete = transfer.accept(TeletextProtocol.Data("ABCD", 3, "fourth\n"))
                assertTrue(complete is TransferAssembler.Result.Complete)
                val file = (complete as TransferAssembler.Result.Complete).file
                assertEquals(fullText, file.readText())
                file.delete()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun previewBoundsLargePagesToSections() {
        val directory = Files.createTempDirectory("teletext-test").toFile()
        try {
            TransferAssembler(directory, "ABCD").use { transfer ->
                repeat(33) { sequence ->
                    transfer.accept(TeletextProtocol.Data("ABCD", sequence.toLong(), "x"))
                }
                assertEquals(32, transfer.preview(0)?.text?.length)
                val tail = transfer.preview(1)
                assertEquals(2, tail?.count)
                assertEquals(1, tail?.index)
                assertEquals("x", tail?.text)
            }
        } finally { directory.deleteRecursively() }
    }
}
