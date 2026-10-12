package com.d3d.nfc

object Ndef {
    private val PREFIXES = arrayOf("", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:")

    /** state: 0 = precisa de mais dados, 1 = link encontrado, 2 = sem link */
    class Result(val state: Int, val url: String? = null)

    fun parse(d: ByteArray): Result {
        var i = 0
        while (i < d.size) {
            val t = d[i].toInt() and 0xFF
            if (t == 0x00) { i++; continue }
            if (t != 0x03) return Result(2)
            if (i + 1 >= d.size) return Result(0)
            var len = d[i + 1].toInt() and 0xFF
            var hdr = 2
            if (len == 0xFF) {
                if (i + 3 >= d.size) return Result(0)
                len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
                hdr = 4
            }
            if (len == 0) return Result(2)
            val s = i + hdr
            if (s + len > d.size) return if (s + len > 144) Result(2) else Result(0)
            val flags = d[s].toInt() and 0xFF
            val typeLen = d[s + 1].toInt() and 0xFF
            if ((flags and 0x10) == 0) return Result(2)
            val payloadLen = d[s + 2].toInt() and 0xFF
            var q = s + 3
            var idLen = 0
            if ((flags and 0x08) != 0) {
                idLen = d[q].toInt() and 0xFF
                q++
            }
            val typeStart = q
            val payloadStart = typeStart + typeLen + idLen
            if (typeLen != 1 || d[typeStart].toInt() != 0x55) return Result(2)
            if (payloadLen < 1 || payloadStart + payloadLen > d.size) return Result(2)
            val code = d[payloadStart].toInt() and 0xFF
            val rest = String(d, payloadStart + 1, payloadLen - 1, Charsets.UTF_8)
            val prefix = if (code < PREFIXES.size) PREFIXES[code] else ""
            return Result(1, prefix + rest)
        }
        return Result(0)
    }

    /** Monta a mensagem NDEF (registro de URL) dentro do formato TLV das tags tipo 2, alinhada em blocos de 4 bytes. */
    fun build(url: String): ByteArray {
        var code = 0
        var rest = url
        val table = listOf(0x02 to "https://www.", 0x01 to "http://www.", 0x04 to "https://", 0x03 to "http://")
        for ((c, p) in table) {
            if (url.startsWith(p, ignoreCase = true)) {
                code = c
                rest = url.substring(p.length)
                break
            }
        }
        val pl = byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8)
        if (pl.size > 250) throw NfcException("Link muito longo para gravar na tag")
        val rec = bytes(0xD1, 0x01, pl.size, 0x55) + pl
        val tlv = if (rec.size < 255) {
            bytes(0x03, rec.size) + rec + bytes(0xFE)
        } else {
            bytes(0x03, 0xFF, rec.size shr 8, rec.size and 0xFF) + rec + bytes(0xFE)
        }
        val padded = ByteArray((tlv.size + 3) / 4 * 4)
        tlv.copyInto(padded)
        return padded
    }
}
