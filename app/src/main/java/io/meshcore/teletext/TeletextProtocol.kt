package io.meshcore.teletext

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater

internal const val MISSING_CHUNK_MARKER = '\uFFFC'

object TeletextProtocol {
    const val MAX_FRAME_BYTES = 100
    private const val MAX_DECOMPRESSED_CHUNK_BYTES = 1024
    private val idPattern = Regex("[0-9A-Fa-f]{4}")
    private val dataPattern = Regex("^T1([DZ])([0-9A-Fa-f]{4})\\.([0-9A-Za-z]+)(?:\\.([0-9A-Za-z]+))?:")
    private val base64UrlPattern = Regex("[A-Za-z0-9_-]+")
    private val endPattern = Regex("^T1E([0-9A-Fa-f]{4})\\.([0-9A-Za-z]+)\\.([0-9A-Fa-f]{8})$")
    private val errorPattern = Regex("^T1X([0-9A-Fa-f]{4})\\.(BAD|NF|BUSY|IO)$")

    sealed interface Response { val id: String }
    data class Data(override val id: String, val sequence: Long, val text: String,
                    val count: Long? = null) : Response
    data class End(override val id: String, val count: Long, val crc32: Long) : Response
    data class Error(override val id: String, val code: String) : Response

    fun request(id: String, page: Int? = null): String {
        require(idPattern.matches(id))
        return if (page == null) "T1I${id.uppercase()}" else {
            require(page in 100..999)
            "T1P${id.uppercase()}$page"
        }
    }

    fun cancel(id: String): String {
        require(idPattern.matches(id))
        return "T1C${id.uppercase()}"
    }

    fun retryChunk(id: String, sequence: Long): String {
        require(idPattern.matches(id) && sequence >= 0)
        return "T1R${id.uppercase()}.${sequence.toString(36).uppercase()}"
    }

    fun parse(frame: String): Response? {
        if (frame.toByteArray(StandardCharsets.UTF_8).size > MAX_FRAME_BYTES) return null
        dataPattern.find(frame)?.let { match ->
            val sequence = match.groupValues[3].toLongOrNull(36) ?: return null
            val count = match.groups[4]?.value?.toLongOrNull(36)
            if (match.groups[4] != null && count == null) return null
            if ((sequence == 0L) != (count != null) || count == 0L) return null
            val payload = frame.substring(match.range.last + 1)
            val text = if (match.groupValues[1] == "Z") decompress(payload) ?: return null else payload
            return Data(match.groupValues[2].uppercase(), sequence, text, count)
        }
        endPattern.matchEntire(frame)?.let { match ->
            val count = match.groupValues[2].toLongOrNull(36) ?: return null
            val crc = match.groupValues[3].toLongOrNull(16) ?: return null
            return End(match.groupValues[1].uppercase(), count, crc)
        }
        errorPattern.matchEntire(frame)?.let { match ->
            return Error(match.groupValues[1].uppercase(), match.groupValues[2])
        }
        return null
    }

    private fun decompress(payload: String): String? {
        if (!base64UrlPattern.matches(payload)) return null
        return try {
            val compressed = Base64.getUrlDecoder().decode(payload)
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(compressed) != payload)
                return null
            val inflater = Inflater()
            val raw = ByteArray(MAX_DECOMPRESSED_CHUNK_BYTES + 1)
            val length = try {
                inflater.setInput(compressed)
                var used = 0
                while (!inflater.finished()) {
                    if (used == raw.size) return null
                    val count = inflater.inflate(raw, used, raw.size - used)
                    if (count == 0) return null
                    used += count
                }
                if (used == 0 || used > MAX_DECOMPRESSED_CHUNK_BYTES || inflater.remaining != 0)
                    return null
                used
            } finally {
                inflater.end()
            }
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw, 0, length)).toString()
        } catch (_: Exception) {
            null
        }
    }
}

class TransferAssembler(private val directory: File, val id: String) : AutoCloseable {
    companion object {
        private const val PREVIEW_CHUNKS_PER_SECTION = 32L
    }

    sealed interface Result {
        data class Progress(val chunks: Int, val bytes: Long, val total: Long?) : Result
        data class Complete(val file: File) : Result
        data class Failure(val reason: String) : Result
        data object Ignored : Result
    }

    data class PreviewSection(val text: String, val index: Int, val count: Int)

    private val spoolFile = File.createTempFile("teletext-spool-", ".tmp", directory)
    private val spool = RandomAccessFile(spoolFile, "rw")
    private val offsets = HashMap<Long, Pair<Long, Int>>()
    private var expected: TeletextProtocol.End? = null
    private var announcedCount: Long? = null
    private var bytesReceived = 0L
    private var maxSequenceSeen = -1L
    private var closed = false

    fun accept(response: TeletextProtocol.Response): Result {
        if (closed || response.id != id) return Result.Ignored
        return try {
            when (response) {
                is TeletextProtocol.Error -> Result.Failure(response.code)
                is TeletextProtocol.End -> {
                    if (expected != null && expected != response) return Result.Failure("Conflicting completion")
                    if (announcedCount != null && announcedCount != response.count)
                        return Result.Failure("Conflicting chunk count")
                    expected = response
                    maybeComplete()
                }
                is TeletextProtocol.Data -> {
                    val raw = response.text.toByteArray(StandardCharsets.UTF_8)
                    if (response.sequence < 0 || response.sequence > 10_000_000L) {
                        return Result.Failure("Unexpected chunk number")
                    }
                    if (response.sequence == 0L && response.count != null) {
                        if (response.count < 1 || (announcedCount != null && announcedCount != response.count)
                            || (expected != null && expected!!.count != response.count))
                            return Result.Failure("Conflicting chunk count")
                        announcedCount = response.count
                    }
                    if (announcedCount != null && response.sequence >= announcedCount!!)
                        return Result.Failure("Chunk beyond announced count")
                    if (expected != null && response.sequence >= expected!!.count) {
                        return Result.Failure("Chunk beyond completion count")
                    }
                    val previous = offsets[response.sequence]
                    if (previous != null) {
                        val existing = ByteArray(previous.second)
                        spool.seek(previous.first)
                        spool.readFully(existing)
                        if (!existing.contentEquals(raw)) return Result.Failure("Conflicting duplicate chunk")
                    } else {
                        val offset = spool.length()
                        spool.seek(offset)
                        spool.write(raw)
                        offsets[response.sequence] = offset to raw.size
                        bytesReceived += raw.size
                        maxSequenceSeen = maxOf(maxSequenceSeen, response.sequence)
                    }
                    maybeComplete()
                }
            }
        } catch (exception: Exception) {
            Result.Failure(exception.message ?: "Storage error")
        }
    }

    fun preview(sectionIndex: Int): PreviewSection? {
        if (closed) return null
        // The end frame can reveal a missing tail; one marker represents its entire gap.
        val lastVisible = if ((expected?.count ?: 0L) > maxSequenceSeen + 1L)
            maxSequenceSeen + 1L else maxSequenceSeen
        if (lastVisible < 0) return null
        val count = (lastVisible / PREVIEW_CHUNKS_PER_SECTION + 1L).toInt()
        val index = sectionIndex.coerceIn(0, count - 1)
        val first = index.toLong() * PREVIEW_CHUNKS_PER_SECTION
        val last = minOf(lastVisible, first + PREVIEW_CHUNKS_PER_SECTION - 1L)
        val text = StringBuilder()
        var inGap = false
        for (sequence in first..last) {
            val location = offsets[sequence]
            if (location == null) {
                if (!inGap) {
                    if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                    text.append(MISSING_CHUNK_MARKER).append('\n')
                }
                inGap = true
            } else {
                val raw = ByteArray(location.second)
                spool.seek(location.first)
                spool.readFully(raw)
                text.append(String(raw, StandardCharsets.UTF_8))
                inGap = false
            }
        }
        return PreviewSection(text.toString(), index, count)
    }

    val hasEnd: Boolean get() = expected != null
    val receivedChunks: Int get() = offsets.size
    val totalChunks: Long? get() = expected?.count ?: announcedCount

    fun firstMissingChunk(): Long? {
        val total = expected?.count ?: announcedCount ?: return null
        var sequence = 0L
        while (sequence < total) {
            if (!offsets.containsKey(sequence)) return sequence
            sequence++
        }
        return null
    }

    private fun maybeComplete(): Result {
        val end = expected ?: return Result.Progress(offsets.size, bytesReceived, announcedCount)
        if (end.count < offsets.size.toLong()) return Result.Failure("Completion count too small")
        if (end.count != offsets.size.toLong()) return Result.Progress(offsets.size, bytesReceived, end.count)
        var sequence = 0L
        while (sequence < end.count) {
            if (!offsets.containsKey(sequence)) return Result.Progress(offsets.size, bytesReceived, end.count)
            sequence++
        }
        val complete = File.createTempFile("teletext-page-", ".md", directory)
        val crc = CRC32()
        try {
            FileOutputStream(complete).use { output ->
                sequence = 0L
                while (sequence < end.count) {
                    val (offset, size) = offsets.getValue(sequence)
                    val raw = ByteArray(size)
                    spool.seek(offset)
                    spool.readFully(raw)
                    crc.update(raw)
                    output.write(raw)
                    sequence++
                }
            }
            if (crc.value != end.crc32) {
                complete.delete()
                return Result.Failure("Checksum mismatch")
            }
            close()
            return Result.Complete(complete)
        } catch (exception: Exception) {
            complete.delete()
            throw exception
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            spool.close()
            spoolFile.delete()
        }
    }
}
