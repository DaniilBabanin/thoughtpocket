package com.soundscript.audio

/** Convert the first [len] 16-bit PCM samples to float in [-1f, 1f] (whisper's input format). */
fun shortsToFloat(src: ShortArray, len: Int): FloatArray {
    val out = FloatArray(len)
    for (i in 0 until len) out[i] = src[i] / 32768f
    return out
}
