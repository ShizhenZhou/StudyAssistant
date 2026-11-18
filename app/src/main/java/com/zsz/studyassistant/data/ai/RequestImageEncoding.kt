package com.zsz.studyassistant.data

import java.security.MessageDigest
import java.util.Base64

/** Bounded, memory-only derived content. Call from the request preparation dispatcher. */
internal object RequestImageEncoding {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val entries = LinkedHashMap<String, String>(8, .75f, true)
    private var bytes = 0

    @Synchronized fun encode(source: ByteArray): String {
        val key = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        entries[key]?.let { return it }
        val encoded = Base64.getEncoder().encodeToString(source)
        val weight = encoded.length.toLong() * 2
        if (weight <= MAX_BYTES) {
            while (entries.isNotEmpty() && (bytes + weight > MAX_BYTES || entries.size >= 8)) {
                bytes -= entries.remove(entries.keys.first())!!.length * 2
            }
            entries[key] = encoded
            bytes += weight.toInt()
        }
        return encoded
    }
}
