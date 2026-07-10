package tun.proxy.service

import android.util.Log

/**
 * Handles DNS-over-UDP packets for the fake DNS server.
 *
 * For A queries  – returns a synthesised response with a fake IPv4 address
 *                  allocated from [FakeDnsDatabase].
 * For AAAA queries – returns NOERROR with 0 answers so the resolver falls
 *                    back to A, giving us an IPv4 fake address to track.
 * Everything else is left for the caller to forward normally (returns null).
 */
class FakeDnsPacketHandler(private val db: FakeDnsDatabase) {

    companion object {
        private const val TAG = "FakeDns"
        private const val TYPE_A    = 1
        private const val TYPE_AAAA = 28
        private const val TTL_SECS  = 60  // short TTL so clients retry quickly
    }

    /**
     * Process a raw DNS UDP payload.
     * @return The raw bytes of the DNS response, or null if this packet is not
     *         handled (caller should forward it to a real resolver).
     */
    fun handleQuery(data: ByteArray): ByteArray? {
        if (data.size < 12) return null

        val flags = hi8(data, 2).shl(8) or hi8(data, 3)
        // Must be a standard query (QR=0, OPCODE=0)
        if (flags and 0x8000 != 0) return null
        if (flags and 0x7800 != 0) return null

        val qdcount = hi8(data, 4).shl(8) or hi8(data, 5)
        if (qdcount == 0) return null

        // Parse QNAME starting at offset 12
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < data.size) {
            val labelLen = hi8(data, offset)
            if (labelLen == 0) { offset++; break }
            // Guard against malformed data
            if (offset + 1 + labelLen > data.size) return null
            labels.add(String(data, offset + 1, labelLen, Charsets.UTF_8))
            offset += 1 + labelLen
        }
        if (offset + 4 > data.size) return null

        val qtype  = hi8(data, offset).shl(8) or hi8(data, offset + 1)
        // qclass at offset+2,3 – we don't need to read it for our purposes
        offset += 4  // skip QTYPE + QCLASS

        val hostname = labels.joinToString(".")
        val id       = hi8(data, 0).shl(8) or hi8(data, 1)
        val questionSection = data.copyOfRange(12, offset)

        return when (qtype) {
            TYPE_A -> {
                val fakeIp = db.getOrAllocate(hostname)
                Log.d(TAG, "DNS A [$hostname] → $fakeIp")
                buildAResponse(id, flags, questionSection, fakeIp)
            }
            TYPE_AAAA -> {
                // Return NOERROR / 0 answers → client falls back to A
                buildNoAnswerResponse(id, flags, questionSection)
            }
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hi8(buf: ByteArray, idx: Int): Int = buf[idx].toInt() and 0xFF

    /**
     * Builds a DNS A response:
     *   - One question (mirrored from the query)
     *   - One answer with RDATA = 4-byte fake IPv4
     */
    private fun buildAResponse(
        id: Int, queryFlags: Int,
        question: ByteArray,
        ip: String
    ): ByteArray {
        val ipBytes = ip.split(".").map { it.toInt().and(0xFF).toByte() }.toByteArray()
        // Header(12) + question + answer(16)
        val resp = ByteArray(12 + question.size + 16)
        writeU16(resp, 0, id)
        // QR=1 (response), AA=1 (authoritative), RD mirrored, RA=1
        writeU16(resp, 2, 0x8400 or (queryFlags and 0x0100))
        writeU16(resp, 4, 1)  // QDCOUNT
        writeU16(resp, 6, 1)  // ANCOUNT
        // NSCOUNT=0, ARCOUNT=0 already zero
        question.copyInto(resp, 12)
        val a = 12 + question.size
        // NAME: pointer to offset 12 (0xC00C)
        resp[a]   = 0xC0.toByte(); resp[a + 1] = 0x0C
        writeU16(resp, a + 2, 1)            // TYPE A
        writeU16(resp, a + 4, 1)            // CLASS IN
        writeU32(resp, a + 6, TTL_SECS)     // TTL
        writeU16(resp, a + 10, 4)           // RDLENGTH
        ipBytes.copyInto(resp, a + 12)
        return resp
    }

    /**
     * Builds a DNS response with NOERROR and 0 answer records.
     * Used for AAAA and unsupported qtypes to avoid NXDOMAIN caching.
     */
    private fun buildNoAnswerResponse(
        id: Int, queryFlags: Int,
        question: ByteArray
    ): ByteArray {
        val resp = ByteArray(12 + question.size)
        writeU16(resp, 0, id)
        writeU16(resp, 2, 0x8400 or (queryFlags and 0x0100))
        writeU16(resp, 4, 1)  // QDCOUNT=1, ANCOUNT stays 0
        question.copyInto(resp, 12)
        return resp
    }

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value shr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value shr 24).toByte()
        buf[offset + 1] = (value shr 16).toByte()
        buf[offset + 2] = (value shr  8).toByte()
        buf[offset + 3] = value.toByte()
    }
}
