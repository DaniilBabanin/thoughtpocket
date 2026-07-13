package com.thoughtpocket.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio interchange with user-chosen Storage Access Framework (SAF) folders — all platform APIs,
 * no extra dependency:
 *  - [writeWav]: save a recording's int16 PCM as a playable 16 kHz mono WAV in a picked folder.
 *  - [listAudio] / [openInput]: enumerate + read audio files from a picked import folder.
 *  - [decode]: turn any audio file (wav/mp3/m4a/aac/ogg/opus) into the 16 kHz mono float
 *    [com.thoughtpocket.WhisperEngine] wants, via MediaExtractor (+ MediaCodec for compressed),
 *    down-mixed and linearly resampled.
 */
object AudioFiles {
    const val RATE = 16_000

    private val AUDIO_EXTS = setOf("wav", "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "3gp", "amr", "mp4")

    // ---------- WAV (save folder) ----------

    /** 44-byte canonical PCM WAV header for mono 16-bit @ [RATE], with [dataLen] bytes of samples. */
    private fun wavHeader(dataLen: Int): ByteArray {
        val ch = 1; val bits = 16; val byteRate = RATE * ch * bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(ch.toShort())
            putInt(RATE); putInt(byteRate); putShort((ch * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataLen)
        }.array()
    }

    /** Stream [pcmFile] (raw little-endian int16 @ [RATE] mono) into [out] as a WAV. */
    fun writeWav(out: OutputStream, pcmFile: File) {
        out.write(wavHeader(pcmFile.length().toInt()))
        pcmFile.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    // ---------- SAF folder ops ----------

    /** Create [name] in the picked tree [treeUri] and return its OutputStream (null on failure). */
    fun createInTree(context: Context, treeUri: Uri, mime: String, name: String): OutputStream? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: return null
        context.contentResolver.openOutputStream(doc)
    }.getOrNull()

    /** Audio files directly under [treeUri], as (uri, displayName, lastModifiedMillis). */
    fun listAudio(context: Context, treeUri: Uri): List<Triple<Uri, String, Long>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Triple<Uri, String, Long>>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS) {
                        out.add(Triple(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)), name, c.getLong(3)))
                    }
                }
            }
        }
        return out
    }

    // ---------- decode → 16 kHz mono int16 PCM file (streaming) ----------

    /**
     * Decode [uri] into [dest] as 16 kHz mono little-endian int16 PCM — the
     * transcribe queue's native format. Fully streaming: chunk → downmix →
     * resample → file, so RAM stays bounded whatever the input size (the old
     * whole-file FloatArray path OOM'd on a ~300 MB hi-res WAV, on-device
     * 2026-07-13). Returns samples written; 0 = unsupported/failure.
     */
    fun decodeToFile(context: Context, uri: Uri, dest: File): Long = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ex.selectTrack(i); fmt = f; break }
            }
            val f = fmt ?: return 0L
            val mime = f.getString(MediaFormat.KEY_MIME)!!
            var written = 0L
            dest.outputStream().buffered(1 shl 16).use { out ->
                val emit: (Float) -> Unit = { s ->
                    val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                    out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                    written++
                }
                if (mime == "audio/raw") streamRaw(ex, f, emit) else streamCompressed(ex, f, mime, emit)
            }
            written
        } finally {
            ex.release()
        }
    }.getOrElse { 0L }

    /** Raw PCM track (WAV) — feed extractor chunks straight to the converter, no codec. */
    private fun streamRaw(ex: MediaExtractor, fmt: MediaFormat, emit: (Float) -> Unit) {
        val conv = ChunkedPcmTo16k(fmt.rate(), fmt.channels(), fmt.encoding(), emit)
        val buf = ByteBuffer.allocate(1 shl 16)
        val arr = ByteArray(1 shl 16)
        while (true) {
            val n = ex.readSampleData(buf, 0); if (n < 0) break
            buf.get(arr, 0, n); conv.feed(arr, 0, n); buf.clear(); ex.advance()
        }
    }

    /** Compressed track (mp3/m4a/aac/ogg/opus/flac…) — MediaCodec decode, converted chunk-wise. */
    private fun streamCompressed(ex: MediaExtractor, inFmt: MediaFormat, mime: String, emit: (Float) -> Unit) {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inFmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var conv: ChunkedPcmTo16k? = null
        var rate = inFmt.rate(); var channels = inFmt.channels(); var enc = inFmt.encoding()
        var chunk = ByteArray(1 shl 16)
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!
                        if (info.size > chunk.size) chunk = ByteArray(info.size)
                        ob.get(chunk, 0, info.size); ob.clear()
                        // Codec output format is authoritative once data flows.
                        if (conv == null) conv = ChunkedPcmTo16k(rate, channels, enc, emit)
                        conv.feed(chunk, 0, info.size)
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                        rate = it.rate(); channels = it.channels(); enc = it.encoding()
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
        }
    }

    private fun MediaFormat.rate() = if (containsKey(MediaFormat.KEY_SAMPLE_RATE)) getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
    private fun MediaFormat.channels() = if (containsKey(MediaFormat.KEY_CHANNEL_COUNT)) getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
    private fun MediaFormat.encoding() =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) getInteger(MediaFormat.KEY_PCM_ENCODING) else 2 // 2 = ENCODING_PCM_16BIT

    /**
     * Incremental PCM converter: interleaved little-endian bytes in (any chunk
     * boundaries — partial frames carry over), 16 kHz mono float samples out
     * via [onSample]. Handles 16-bit, float, 24-bit packed and 32-bit int PCM
     * (AudioFormat encodings 2/4/21/22 — hi-res WAVs report 21/22 and used to
     * decode as garbage when read pairwise as shorts). Resampling is streaming
     * linear interpolation (adequate for speech). Internal for the JVM test.
     */
    internal class ChunkedPcmTo16k(
        rate: Int,
        private val channels: Int,
        private val encoding: Int,
        private val onSample: (Float) -> Unit,
    ) {
        private val bytesPerSample = when (encoding) {
            4, 22 -> 4 // ENCODING_PCM_FLOAT / ENCODING_PCM_32BIT
            21 -> 3    // ENCODING_PCM_24BIT_PACKED
            else -> 2  // ENCODING_PCM_16BIT
        }
        private val frameBytes = bytesPerSample * channels
        private val carry = ByteArray(frameBytes)
        private var carryLen = 0
        private val passthrough = rate == RATE
        private val ratio = rate.toDouble() / RATE
        private var inIdx = -1L
        private var last = 0f
        private var outIdx = 0L

        fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
            if (channels < 1) return
            var i = off
            val end = off + len
            if (carryLen > 0) {
                while (carryLen < frameBytes && i < end) carry[carryLen++] = data[i++]
                if (carryLen == frameBytes) { frame(carry, 0); carryLen = 0 } else return
            }
            while (end - i >= frameBytes) { frame(data, i); i += frameBytes }
            while (i < end) carry[carryLen++] = data[i++]
        }

        private fun frame(b: ByteArray, off: Int) {
            var s = 0f
            var p = off
            repeat(channels) { s += sampleAt(b, p); p += bytesPerSample }
            push(s / channels)
        }

        // The last byte's toInt() sign-extends, giving each width its sign bit.
        private fun sampleAt(b: ByteArray, p: Int): Float = when (encoding) {
            4 -> Float.fromBits(
                (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                    ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
            )
            21 -> ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                (b[p + 2].toInt() shl 16)) / 8388608f
            22 -> ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)) / 2147483648f
            else -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)) / 32768f
        }

        /** Streaming linear resampler: emits every output position that falls before this input sample. */
        private fun push(s: Float) {
            if (passthrough) { onSample(s); return }
            inIdx++
            if (inIdx == 0L) { last = s; return }
            while (true) {
                val p = outIdx * ratio
                if (p >= inIdx) break
                val frac = (p - (inIdx - 1)).toFloat()
                onSample(last * (1 - frac) + s * frac)
                outIdx++
            }
            last = s
        }
    }
}
