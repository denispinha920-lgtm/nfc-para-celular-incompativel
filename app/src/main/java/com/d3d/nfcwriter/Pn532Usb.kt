package com.d3d.nfcwriter

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

class Pn532Usb(private val port: UsbSerialPort) {

    companion object {
        private val PREAMBLE = byteArrayOf(0x00)
        private val START_CODE = byteArrayOf(0x00, 0xFF.toByte())
        private val POSTAMBLE = byteArrayOf(0x00)
        private const val HOST_TO_PN532 = 0xD4.toByte()

        private const val CMD_GET_FIRMWARE_VERSION = 0x02.toByte()
        private const val CMD_SAM_CONFIGURATION = 0x14.toByte()
        private const val CMD_IN_LIST_PASSIVE_TARGET = 0x4A.toByte()
        private const val CMD_IN_DATA_EXCHANGE = 0x40.toByte()

        private const val MF_WRITE_ULTRALIGHT = 0xA2.toByte()

        private val ACK_FRAME = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00)
    }

    private val readTimeoutMs = 1500
    private val writeTimeoutMs = 1500

    fun wakeUp() {
        val preamble = byteArrayOf(
            0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        port.write(preamble, writeTimeoutMs)
        Thread.sleep(100)
        val trash = ByteArray(64)
        try {
            port.read(trash, 50)
        } catch (_: Exception) {
        }
    }

    private fun sendCommand(cmd: Byte, params: ByteArray = ByteArray(0)) {
        val body = byteArrayOf(HOST_TO_PN532, cmd) + params
        val length = body.size
        val lcs = ((0x100 - (length and 0xFF)) and 0xFF).toByte()

        var dcsAcc = 0
        for (b in body) dcsAcc += (b.toInt() and 0xFF)
        val dcs = ((0x100 - (dcsAcc and 0xFF)) and 0xFF).toByte()

        val frame = PREAMBLE + START_CODE +
                byteArrayOf(length.toByte(), lcs) +
                body +
                byteArrayOf(dcs) +
                POSTAMBLE

        port.write(frame, writeTimeoutMs)
    }

    private fun readAck() {
        val buf = ByteArray(6)
        val read = readExact(buf, buf.size)
        if (read < 6 || !buf.contentEquals(ACK_FRAME)) {
            throw IOException("PN532 não respondeu com ACK válido (confira fiação/alimentação)")
        }
    }

    private fun readExact(dst: ByteArray, n: Int): Int {
        var offset = 0
        val tmp = ByteArray(256)
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (offset < n && System.currentTimeMillis() < deadline) {
            val read = port.read(tmp, readTimeoutMs)
            if (read > 0) {
                val toCopy = minOf(read, n - offset)
                System.arraycopy(tmp, 0, dst, offset, toCopy)
                offset += toCopy
            }
        }
        return offset
    }

    private fun readResponse(): ByteArray {
        val header = ByteArray(6)
        readExact(header, 6)
        val len = header[3].toInt() and 0xFF
        val dataLen = (len - 1).coerceAtLeast(0)
        val data = ByteArray(dataLen)
        readExact(data, dataLen)
        val tail = ByteArray(2)
        readExact(tail, 2)
        return data
    }

    @Throws(IOException::class)
    fun getFirmwareVersion(): String {
        sendCommand(CMD_GET_FIRMWARE_VERSION)
        readAck()
        val resp = readResponse()
        if (resp.isEmpty()) throw IOException("Sem resposta do PN532 (verifique fiação e alimentação)")
        val ic = resp.getOrNull(1)?.toInt() ?: -1
        val ver = resp.getOrNull(2)?.toInt() ?: -1
        val rev = resp.getOrNull(3)?.toInt() ?: -1
        return "IC=0x${ic.toString(16)} Firmware=$ver.$rev"
    }

    fun samConfiguration() {
        sendCommand(CMD_SAM_CONFIGURATION, byteArrayOf(0x01, 0x14, 0x01))
        readAck()
        readResponse()
    }

    fun readUid(): String? {
        sendCommand(CMD_IN_LIST_PASSIVE_TARGET, byteArrayOf(0x01, 0x00))
        readAck()
        val resp = readResponse()
        if (resp.size < 8) return null
        val nbTg = resp[1].toInt()
        if (nbTg == 0) return null
        val uidLen = resp[6].toInt() and 0xFF
        if (resp.size < 7 + uidLen) return null
        val uid = resp.copyOfRange(7, 7 + uidLen)
        return uid.joinToString(":") { String.format("%02X", it) }
    }

    private fun writePage(page: Int, data: ByteArray) {
        require(data.size == 4) { "Página precisa ter exatamente 4 bytes" }
        val params = byteArrayOf(0x01, MF_WRITE_ULTRALIGHT, page.toByte()) + data
        sendCommand(CMD_IN_DATA_EXCHANGE, params)
        readAck()
        val resp = readResponse()
        val status = resp.getOrNull(1)?.toInt() ?: -1
        if (status != 0) throw IOException("Falha ao escrever página $page (status=0x${status.toString(16)})")
    }

    fun writeUrl(url: String) {
        val ndefMessage = buildNdefUriMessage(url)
        val tlv = byteArrayOf(0x03, ndefMessage.size.toByte()) + ndefMessage + byteArrayOf(0xFE.toByte())
        val padded = if (tlv.size % 4 == 0) tlv else tlv + ByteArray(4 - (tlv.size % 4))

        val maxPages = 36
        val pagesNeeded = padded.size / 4
        if (pagesNeeded > maxPages) {
            throw IOException("Link muito longo para essa tag (precisa de $pagesNeeded páginas, cabe $maxPages). Use um link mais curto.")
        }

        var page = 4
        var offset = 0
        while (offset < padded.size) {
            val chunk = padded.copyOfRange(offset, offset + 4)
            writePage(page, chunk)
            page++
            offset += 4
        }
    }

    private fun buildNdefUriMessage(url: String): ByteArray {
        val useAbbrev = url.startsWith("http://www.")
        val prefixCode: Byte
        val uriField: String
        if (useAbbrev) {
            prefixCode = 0x01
            uriField = url.removePrefix("http://www.")
        } else if (url.startsWith("https://")) {
            prefixCode = 0x04
            uriField = url.removePrefix("https://")
        } else {
            prefixCode = 0x00
            uriField = url
        }

        val uriBytes = uriField.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(prefixCode) + uriBytes

        val flagsAndTnf = 0xD1.toByte()
        val typeLength = 0x01.toByte()
        val payloadLength = payload.size.toByte()
        val type = byteArrayOf('U'.code.toByte())

        return byteArrayOf(flagsAndTnf, typeLength, payloadLength) + type + payload
    }

    fun close() {
        try {
            port.close()
        } catch (_: IOException) {
        }
    }
}
