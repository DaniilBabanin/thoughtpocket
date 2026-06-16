package com.thoughtpocket.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
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

    // ---------- decode → 16 kHz mono float ----------

    /** Decode [uri] to 16 kHz mono float [-1,1]; empty on unsupported/failure. */
    fun decode(context: Context, uri: Uri): FloatArray = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ex.selectTrack(i); fmt = f; break }
            }
            val f = fmt ?: return FloatArray(0)
            val mime = f.getString(MediaFormat.KEY_MIME)!!
            // ponytail: whole file decoded into RAM — fine for a one-off import (whisper needs the full array anyway).
            if (mime == "audio/raw") readRaw(ex, f) else decodeCompressed(ex, f, mime)
        } finally {
            ex.release()
        }
    }.getOrElse { FloatArray(0) }

    /** Raw PCM track (WAV) — read sample bytes straight from the extractor, no codec. */
    private fun readRaw(ex: MediaExtractor, fmt: MediaFormat): FloatArray {
        val bytes = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(1 shl 16)
        while (true) {
            val n = ex.readSampleData(buf, 0); if (n < 0) break
            val arr = ByteArray(n); buf.get(arr, 0, n); bytes.write(arr); buf.clear(); ex.advance()
        }
        return toMono16k(bytes.toByteArray(), fmt.rate(), fmt.channels(), fmt.encoding())
    }

    /** Compressed track (mp3/m4a/aac/ogg/opus/flac…) — MediaCodec decode to PCM. */
    private fun decodeCompressed(ex: MediaExtractor, inFmt: MediaFormat, mime: String): FloatArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inFmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val bytes = ByteArrayOutputStream()
        var rate = inFmt.rate(); var channels = inFmt.channels(); var enc = inFmt.encoding()
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
                        val arr = ByteArray(info.size); ob.get(arr); ob.clear(); bytes.write(arr)
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
        return toMono16k(bytes.toByteArray(), rate, channels, enc)
    }

    private fun MediaFormat.rate() = if (containsKey(MediaFormat.KEY_SAMPLE_RATE)) getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
    private fun MediaFormat.channels() = if (containsKey(MediaFormat.KEY_CHANNEL_COUNT)) getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
    private fun MediaFormat.encoding() =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) getInteger(MediaFormat.KEY_PCM_ENCODING) else 2 // 2 = ENCODING_PCM_16BIT

    /** Interleaved PCM bytes → mono, then linearly resampled to [RATE]. Handles 16-bit and float PCM. */
    private fun toMono16k(data: ByteArray, rate: Int, channels: Int, encoding: Int): FloatArray {
        if (data.isEmpty() || channels < 1) return FloatArray(0)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val isFloat = encoding == 4 // ENCODING_PCM_FLOAT
        val total = if (isFloat) data.size / 4 else data.size / 2
        val frames = total / channels
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += if (isFloat) bb.float else bb.short.toFloat() / 32768f
            mono[i] = s / channels
        }
        return if (rate == RATE) mono else resample(mono, rate, RATE)
    }

    /** Linear-interpolation resample [input] from [from] Hz to [to] Hz (adequate for speech). */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (input.isEmpty() || from <= 0) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio; val j = pos.toInt(); val frac = (pos - j).toFloat()
            out[i] = if (j + 1 < input.size) input[j] * (1 - frac) + input[j + 1] * frac else input[j]
        }
        return out
    }

    /** Write 16 kHz mono float as little-endian int16 to [file] (so it feeds the normal transcribe queue). */
    fun writePcm(file: File, samples: FloatArray) {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        file.writeBytes(bb.array())
    }
}
