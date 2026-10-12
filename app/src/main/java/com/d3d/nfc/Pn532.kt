package com.d3d.nfc

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

class NfcException(message: String) : Exception(message)
class TagException(val status: Int) : Exception("Erro da tag ($status)")

class TagInfo(val uid: ByteArray, val sak: Int, val atqa: Int) {
    val uidHex: String get() = uid.joinToString(" ") { "%02X".format(it) }
    val isType2: Boolean get() = sak == 0x00
}

fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

object Pn532 {
    private var port: UsbSerialPort? = null
    private var conn: UsbDeviceConnection? = null
    private var lastUse = 0L
    private val WAKE = bytes(0x55, 0x55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Volatile var connected = false
    @Volatile var firmware = ""
    @Volatile var log = ""
    @Volatile private var lastRx = ByteArray(0)

    private fun hex(b: ByteArray) = if (b.isEmpty()) "(nada)" else b.joinToString(" ") { "%02X".format(it) }
    private fun addLog(s: String) { log = (log + s + "\n").takeLast(1500) }

    fun findDevice(ctx: Context): UsbDevice? {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val prober = UsbSerialProber.getDefaultProber()
        return um.deviceList.values.firstOrNull { prober.probeDevice(it) != null }
    }

    @Synchronized
    fun close() {
        connected = false
        try { port?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        port = null
        conn = null
    }

    @Synchronized
    fun open(ctx: Context, device: UsbDevice) {
        close()
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw NfcException("Adaptador USB não reconhecido")
        val c = um.openDevice(device) ?: throw NfcException("Sem permissão para acessar o USB")
        try {
            val p = driver.ports[0]
            p.open(c)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true) } catch (_: Exception) {}
            try { p.setRTS(true) } catch (_: Exception) {}
            port = p
            conn = c
        } catch (e: Exception) {
            try { c.close() } catch (_: Exception) {}
            throw NfcException("Não foi possível abrir a porta serial")
        }
        log = ""
        var ok = false
        for (lines in listOf(true, false)) {
            try { port?.setDTR(lines) } catch (_: Exception) {}
            try { port?.setRTS(lines) } catch (_: Exception) {}
            Thread.sleep(300)
            try {
                lastUse = 0
                val fw = exchange(bytes(0x02))
                firmware = if (fw.size >= 3) "${fw[1].toInt() and 0xFF}.${fw[2].toInt() and 0xFF}" else "?"
                exchange(bytes(0x14, 0x01, 0x14, 0x01))
                exchange(bytes(0x32, 0x05, 0xFF, 0x01, 0x02))
                ok = true
                break
            } catch (e: Exception) {
                addLog("DTR/RTS=$lines falhou: ${e.message}")
            }
        }
        if (!ok) {
            val l = log
            close()
            throw NfcException("DIAG: o PN532 não respondeu.\n$l")
        }
        connected = true
    }

    /** Teste de eco: exige TXD ligado no RXD do CH340, sem o PN532. Retorna "OK" ou o que foi recebido. */
    @Synchronized
    fun loopback(ctx: Context, device: UsbDevice): String {
        close()
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw NfcException("Adaptador USB não reconhecido")
        val c = um.openDevice(device) ?: throw NfcException("Sem permissão para acessar o USB")
        val p = driver.ports[0]
        try {
            p.open(c)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true) } catch (_: Exception) {}
            try { p.setRTS(true) } catch (_: Exception) {}
            Thread.sleep(200)
            val b = ByteArray(256)
            var k = 0
            while (k++ < 20 && p.read(b, 10) > 0) { }
            val msg = "D3D-TESTE".toByteArray()
            p.write(msg, 1000)
            var got = ByteArray(0)
            val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end && got.size < msg.size) {
                val n = p.read(b, 100)
                if (n > 0) got += b.copyOf(n)
            }
            return if (got.contentEquals(msg)) "OK" else "recebido: ${hex(got)}"
        } finally {
            try { p.close() } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
    }

    fun reconnect(ctx: Context): Boolean {
        val d = findDevice(ctx) ?: return false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(d)) return false
        return try { open(ctx, d); true } catch (_: Exception) { false }
    }

    private fun frame(cmd: ByteArray): ByteArray {
        val len = cmd.size + 1
        val body = ByteArray(len)
        body[0] = 0xD4.toByte()
        cmd.copyInto(body, 1)
        var sum = 0
        for (x in body) sum += x.toInt() and 0xFF
        return bytes(0, 0, 0xFF, len, (0x100 - len) and 0xFF) + body + bytes((0x100 - sum) and 0xFF, 0)
    }

    private fun drain(p: UsbSerialPort) {
        val b = ByteArray(256)
        var k = 0
        while (k++ < 20 && p.read(b, 5) > 0) { }
    }

    private fun wake(p: UsbSerialPort) {
        p.write(WAKE, 1000)
        Thread.sleep(200)
        drain(p)
    }

    private class Parsed(val ack: Boolean, val data: ByteArray?)

    private fun parse(buf: ByteArray, code: Int): Parsed {
        var ack = false
        var i = 0
        while (i + 4 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && (buf[i + 2].toInt() and 0xFF) == 0xFF) {
                val len = buf[i + 3].toInt() and 0xFF
                val lcs = buf[i + 4].toInt() and 0xFF
                if (len == 0 && lcs == 0xFF) { ack = true; i += 6; continue }
                if (len >= 1 && ((len + lcs) and 0xFF) == 0) {
                    if (buf.size < i + 5 + len + 1) break
                    val data = buf.copyOfRange(i + 5, i + 5 + len)
                    if ((data[0].toInt() and 0xFF) == 0xD5) {
                        val c = data[1].toInt() and 0xFF
                        if (c == 0x7F) throw NfcException("O módulo recusou o comando")
                        if (c == code) return Parsed(true, data.copyOfRange(2, data.size))
                    }
                    i += 5 + len + 2
                    continue
                }
            }
            i++
        }
        return Parsed(ack, null)
    }

    private fun readResponse(p: UsbSerialPort, code: Int, timeoutMs: Long): ByteArray {
        val start = System.currentTimeMillis()
        var buf = ByteArray(0)
        lastRx = buf
        val chunk = ByteArray(256)
        while (System.currentTimeMillis() - start < timeoutMs) {
            val n = p.read(chunk, 40)
            if (n > 0) buf += chunk.copyOf(n)
            lastRx = buf
            val res = parse(buf, code)
            val d = res.data
            if (d != null) return d
            if (!res.ack && System.currentTimeMillis() - start > 400) throw NfcException("PN532 não respondeu (sem ACK)")
        }
        throw NfcException("PN532 não respondeu a tempo")
    }

    @Synchronized
    fun exchange(cmd: ByteArray, timeoutMs: Long = 1200, attempts: Int = 3): ByteArray {
        var last: Exception? = null
        for (n in 0 until attempts) {
            val p = port ?: throw NfcException("Módulo desconectado")
            val fr = frame(cmd)
            try {
                if (n > 0 || System.currentTimeMillis() - lastUse > 1500) wake(p)
                drain(p)
                p.write(fr, 1000)
                val r = readResponse(p, (cmd[0].toInt() and 0xFF) + 1, timeoutMs)
                lastUse = System.currentTimeMillis()
                return r
            } catch (e: IOException) {
                connected = false
                addLog("USB: ${e.message}")
                throw NfcException("Falha USB: ${e.message}")
            } catch (e: NfcException) {
                last = e
                addLog("tentativa ${n + 1}: ${e.message} | enviado ${hex(fr)} | recebido ${hex(lastRx)}")
            }
        }
        throw last ?: NfcException("Sem resposta do módulo")
    }

    /** Procura uma tag no campo. Retorna null se não houver nenhuma. */
    fun poll(): TagInfo? {
        val r = exchange(bytes(0x4A, 0x01, 0x00), 1500)
        if (r.size < 6 || r[0].toInt() == 0) return null
        val atqa = ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF)
        val sak = r[4].toInt() and 0xFF
        val ul = r[5].toInt() and 0xFF
        if (r.size < 6 + ul) return null
        return TagInfo(r.copyOfRange(6, 6 + ul), sak, atqa)
    }

    fun waitTag(timeoutMs: Long): TagInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val t = poll()
            if (t != null) return t
            Thread.sleep(50)
        }
        return null
    }

    private fun dataExchange(payload: ByteArray): ByteArray {
        val r = exchange(bytes(0x40, 0x01) + payload, 1500)
        if (r.isEmpty()) throw TagException(-1)
        val st = r[0].toInt() and 0xFF
        if (st != 0) throw TagException(st)
        return r.copyOfRange(1, r.size)
    }

    private fun readPages(page: Int): ByteArray {
        val d = dataExchange(bytes(0x30, page))
        if (d.size < 16) throw TagException(-2)
        return d
    }

    private fun writePage(page: Int, data4: ByteArray) {
        dataExchange(bytes(0xA2, page) + data4)
    }

    /** Lê o link gravado (NDEF) na tag que está selecionada. Retorna null se a tag não tiver link. */
    fun readUrl(): String? {
        var data = ByteArray(0)
        var page = 4
        while (page <= 36) {
            data += readPages(page)
            val r = Ndef.parse(data)
            if (r.state == 1) return r.url
            if (r.state == 2) return null
            page += 4
        }
        return null
    }

    fun writeUrl(url: String, tag: TagInfo) {
        if (!tag.isType2) throw NfcException("Tag não suportada. Use NTAG213, NTAG215 ou NTAG216.")
        val cc = readPages(3)
        var capacity = (cc[2].toInt() and 0xFF) * 8
        if ((cc[0].toInt() and 0xFF) != 0xE1) {
            writePage(3, bytes(0xE1, 0x10, 0x06, 0x00))
            capacity = 48
        }
        if (capacity == 0) capacity = 48
        val msg = Ndef.build(url)
        if (msg.size > capacity) throw NfcException("Link muito longo para essa tag (cabem cerca de ${capacity - 8} caracteres).")
        var page = 4
        var i = 0
        while (i < msg.size) {
            writePage(page, msg.copyOfRange(i, i + 4))
            i += 4
            page++
        }
    }

    fun writeAndVerify(url: String, tag: TagInfo) {
        writeUrl(url, tag)
        val back = readUrl()
        if (back == null || !back.equals(url, ignoreCase = true)) {
            throw NfcException("A gravação não conferiu. Tente novamente.")
        }
    }
}
