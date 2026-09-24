package com.d3d.nfcwriter

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Implementação mínima do protocolo PN532 (modo HSU / USB-CDC).
 *
 * Referência do protocolo: "PN532 User Manual" (NXP, UM0701-02), capítulo 6 e 7.
 * Este arquivo cobre apenas o necessário para:
 *  - Ler o UID de uma tag (InListPassiveTarget)
 *  - Escrever páginas de 4 bytes em tags NTAG21x / Mifare Ultralight (InDataExchange + WRITE)
 *
 * Não é uma implementação completa do PN532 (não cobre Mifare Classic com autenticação,
 * P2P, modo cartão, etc). Serve como ponto de partida.
 */
class Pn532Usb(private val port: UsbSerialPort) {

    companion object {
        private val PREAMBLE = byteArrayOf(0x00)
        private val START_CODE = byteArrayOf(0x00, 0xFF.toByte())
        private val POSTAMBLE = byteArrayOf(0x00)
        private const val HOST_TO_PN532 = 0xD4.toByte()
        private const val PN532_TO_HOST = 0xD5.toByte()

        // Comandos PN532 usados aqui
        private const val CMD_GET_FIRMWARE_VERSION = 0x02.toByte()
        private const val CMD_SAM_CONFIGURATION = 0x14.toByte()
        private const val CMD_IN_LIST_PASSIVE_TARGET = 0x4A.toByte()
        private const val CMD_IN_DATA_EXCHANGE = 0x40.toByte()

        // Comandos Mifare Ultralight / NTAG (executados via InDataExchange)
        private const val MF_READ = 0x30.toByte()
        private const val MF_WRITE_ULTRALIGHT = 0xA2.toByte() // grava 4 bytes por página
    }

    private val readTimeoutMs = 1500
    private val writeTimeoutMs = 1500

    /** Monta um frame de comando no formato do PN532 e envia pela serial. */
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

    /** Lê e descarta o frame de ACK (00 00 FF 00 FF 00) que o PN532 manda após receber um comando. */
    private fun readAck() {
        val buf = ByteArray(6)
        readExact(buf, buf.size)
    }

    /** Lê `n` bytes da serial, bloqueando em pequenas leituras até completar. */
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

    /**
     * Lê a resposta de um comando (depois do ACK) e devolve só os bytes de dados
     * (sem preamble/start/len/TFI/postamble), ou seja, [cmd_resposta, payload...].
     */
    private fun readResponse(): ByteArray {
        // cabeçalho fixo: 00 00 FF LEN LCS TFI  -> 6 bytes
        val header = ByteArray(6)
        readExact(header, 6)
        val len = header[3].toInt() and 0xFF // inclui o TFI
        val dataLen = len - 1 // remove TFI já lido
        val data = ByteArray(dataLen)
        readExact(data, dataLen)
        val tail = ByteArray(2) // DCS + postamble
        readExact(tail, 2)
        return data
    }

    /** Confirma se o módulo responde e está vivo. Lança exceção se não conseguir falar com ele. */
    @Throws(IOException::class)
    fun getFirmwareVersion(): String {
        sendCommand(CMD_GET_FIRMWARE_VERSION)
        readAck()
        val resp = readResponse()
        // resp[0] = 0x03 (resposta do comando 0x02), resp[1..4] = IC, Ver, Rev, Support
        if (resp.isEmpty()) throw IOException("Sem resposta do PN532")
        val ic = resp.getOrNull(1)?.toInt() ?: -1
        val ver = resp.getOrNull(2)?.toInt() ?: -1
        val rev = resp.getOrNull(3)?.toInt() ?: -1
        return "IC=0x${ic.toString(16)} Firmware=$ver.$rev"
    }

    /** Configuração inicial recomendada (modo "normal", sem timeout automático). */
    fun samConfiguration() {
        sendCommand(CMD_SAM_CONFIGURATION, byteArrayOf(0x01, 0x14, 0x01))
        readAck()
        readResponse()
    }

    /**
     * Aproxima uma tag ISO14443A e devolve o UID em hexadecimal, ou null se nada foi
     * detectado dentro do timeout.
     */
    fun readUid(): String? {
        // InListPassiveTarget: 1 alvo, tipo 106 kbps type A
        sendCommand(CMD_IN_LIST_PASSIVE_TARGET, byteArrayOf(0x01, 0x00))
        readAck()
        val resp = readResponse()
        // resp[0]=0x4B, resp[1]=NbTg, resp[2]=Tg, resp[3..4]=SENS_RES, resp[5]=SEL_RES,
        // resp[6]=UID length, resp[7..]=UID
        if (resp.size < 8) return null
        val nbTg = resp[1].toInt()
        if (nbTg == 0) return null
        val uidLen = resp[6].toInt() and 0xFF
        if (resp.size < 7 + uidLen) return null
        val uid = resp.copyOfRange(7, 7 + uidLen)
        return uid.joinToString(":") { String.format("%02X", it) }
    }

    /**
     * Escreve uma página de 4 bytes em uma tag Mifare Ultralight / NTAG21x.
     * @param page número da página (NTAG213: dados úteis começam na página 4)
     */
    private fun writePage(page: Int, data: ByteArray) {
        require(data.size == 4) { "Página precisa ter exatamente 4 bytes" }
        val params = byteArrayOf(0x01, MF_WRITE_ULTRALIGHT, page.toByte()) + data
        sendCommand(CMD_IN_DATA_EXCHANGE, params)
        readAck()
        val resp = readResponse()
        val status = resp.getOrNull(1)?.toInt() ?: -1
        if (status != 0) throw IOException("Falha ao escrever página $page (status=0x${status.toString(16)})")
    }

    /**
     * Monta um NDEF Message com um único registro de URI e grava a partir da página 4
     * (padrão em NTAG213/215/216). Sobrescreve o conteúdo NDEF existente na tag.
     */
    fun writeUrl(url: String) {
        val ndefMessage = buildNdefUriMessage(url)
        // TLV: 0x03 (tipo NDEF), tamanho, payload, 0xFE (terminador)
        val tlv = byteArrayOf(0x03, ndefMessage.size.toByte()) + ndefMessage + byteArrayOf(0xFE.toByte())

        // preenche até múltiplo de 4 bytes com 0x00
        val padded = if (tlv.size % 4 == 0) tlv else tlv + ByteArray(4 - (tlv.size % 4))

        var page = 4
        var offset = 0
        while (offset < padded.size) {
            val chunk = padded.copyOfRange(offset, offset + 4)
            writePage(page, chunk)
            page++
            offset += 4
        }
    }

    /** Constrói o payload NDEF de um único registro do tipo URI (RTD_URI). */
    private fun buildNdefUriMessage(url: String): ByteArray {
        // Prefixo abreviado 0x01 = "http://www." — trocamos por 0x00 (sem abreviação)
        // se a URL não começar com esse padrão, pra evitar cortar a URL errado.
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

        // Cabeçalho do registro NDEF: MB=1, ME=1, SR=1 (short record), TNF=0x01 (Well Known)
        val flagsAndTnf = 0xD1.toByte() // 1101 0001
        val typeLength = 0x01.toByte() // "U"
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
