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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.d3d.nfcwriter.USB_PERMISSION"

    private lateinit var statusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnRead: Button
    private lateinit var btnWrite: Button

    private var pn532: Pn532Usb? = null
    private lateinit var usbManager: UsbManager

    private val pn532Dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + pn532Dispatcher)

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

        setButtonsEnabledDuringOperation(connected = false)

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

    private fun setButtonsEnabledDuringOperation(connected: Boolean) {
        runOnUiThread {
            btnRead.isEnabled = connected
            btnWrite.isEnabled = connected
        }
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

        setButtonsEnabledDuringOperation(connected = false)
        btnConnect.isEnabled = false

        scope.launch {
            try {
                port.open(connection)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                val device532 = Pn532Usb(port)
                pn532 = device532

                setStatus("Conectado. Verificando módulo...")

                // Tenta até 3 vezes: módulos baratos às vezes "engasgam" no
                // primeiro pacote logo após ligar.
                var fw: String? = null
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        setStatus("Conectado. Verificando módulo (tentativa $attempt/3)...")
                        device532.wakeUp()
                        fw = device532.getFirmwareVersion()
                        break
                    } catch (e: Exception) {
                        lastError = e
                        delay(400)
                    }
                }

                if (fw == null) {
                    throw lastError ?: Exception("Falha desconhecida ao verificar o módulo")
                }

                device532.samConfiguration()
                setStatus("Módulo OK ($fw). Aproxime uma tag e toque em Ler ou Gravar.")
                setButtonsEnabledDuringOperation(connected = true)
            } catch (e: Exception) {
                setStatus("Erro ao falar com o PN532: ${e.message}")
                setButtonsEnabledDuringOperation(connected = false)
            } finally {
                runOnUiThread { btnConnect.isEnabled = true }
            }
        }
    }

    private fun readTag() {
        val p = pn532 ?: run { setStatus("Conecte o módulo primeiro."); return }
        setButtonsEnabledDuringOperation(connected = false)
        setStatus("Aproxime a tag...")
        scope.launch {
            try {
                val uid = p.readUid()
                setStatus(if (uid != null) "Tag detectada. UID: $uid" else "Nenhuma tag detectada (timeout).")
            } catch (e: Exception) {
                setStatus("Erro na leitura: ${e.message}")
            } finally {
                setButtonsEnabledDuringOperation(connected = true)
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
        setButtonsEnabledDuringOperation(connected = false)
        setStatus("Aproxime a tag para gravar...")
        scope.launch {
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
            } finally {
                setButtonsEnabledDuringOperation(connected = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pn532?.close()
        pn532Dispatcher.close()
        unregisterReceiver(usbReceiver)
    }
}
