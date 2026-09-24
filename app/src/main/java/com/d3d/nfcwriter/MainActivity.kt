package com.d3d.nfcwriter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela única: conectar no módulo PN532 via USB-OTG, ler UID de uma tag
 * e gravar uma URL nela em formato NDEF.
 *
 * Este é um ponto de partida funcional, não um app finalizado: trate erros de
 * conexão/serial com calma na hora de testar com hardware real, cada
 * variação de módulo PN532 (fabricante, firmware) pode se comportar
 * ligeiramente diferente.
 */
class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.d3d.nfcwriter.USB_PERMISSION"

    private lateinit var statusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnRead: Button
    private lateinit var btnWrite: Button

    private var pn532: Pn532Usb? = null
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        openDevice(device)
                    } else {
                        setStatus("Permissão USB negada pelo usuário.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        urlInput = findViewById(R.id.urlInput)
        btnConnect = findViewById(R.id.btnConnect)
        btnRead = findViewById(R.id.btnRead)
        btnWrite = findViewById(R.id.btnWrite)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnConnect.setOnClickListener { connectToDevice() }
        btnRead.setOnClickListener { readTag() }
        btnWrite.setOnClickListener { writeTag() }

        setStatus("Desconectado. Plugue o módulo PN532 via cabo OTG e toque em Conectar.")
    }

    private fun setStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    private fun connectToDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            setStatus("Nenhum dispositivo serial USB encontrado. Confira o cabo OTG e o módulo.")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            setStatus("Aguardando permissão de acesso ao USB...")
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            setStatus("Dispositivo conectado, mas não reconhecido como porta serial.")
            return
        }
        val connection = usbManager.openDevice(device) ?: run {
            setStatus("Não foi possível abrir o dispositivo USB.")
            return
        }
        val port: UsbSerialPort = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            pn532 = Pn532Usb(port)
            setStatus("Conectado. Verificando módulo...")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val fw = pn532?.getFirmwareVersion()
                    pn532?.samConfiguration()
                    setStatus("Módulo OK ($fw). Aproxime uma tag e toque em Ler ou Gravar.")
                } catch (e: Exception) {
                    setStatus("Erro ao falar com o PN532: ${e.message}")
                }
            }
        } catch (e: Exception) {
            setStatus("Erro ao abrir a porta serial: ${e.message}")
        }
    }

    private fun readTag() {
        val p = pn532 ?: run { setStatus("Conecte o módulo primeiro."); return }
        setStatus("Aproxime a tag...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uid = p.readUid()
                withContext(Dispatchers.Main) {
                    setStatus(if (uid != null) "Tag detectada. UID: $uid" else "Nenhuma tag detectada (timeout).")
                }
            } catch (e: Exception) {
                setStatus("Erro na leitura: ${e.message}")
            }
        }
    }

    private fun writeTag() {
        val p = pn532 ?: run { setStatus("Conecte o módulo primeiro."); return }
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            setStatus("Digite a URL que quer gravar na tag.")
            return
        }
        setStatus("Aproxime a tag para gravar...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uid = p.readUid()
                if (uid == null) {
                    setStatus("Nenhuma tag detectada. Aproxime a tag e tente de novo.")
                    return@launch
                }
                p.writeUrl(url)
                setStatus("Gravado com sucesso na tag $uid:\n$url")
            } catch (e: Exception) {
                setStatus("Erro ao gravar: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pn532?.close()
        unregisterReceiver(usbReceiver)
    }
}
