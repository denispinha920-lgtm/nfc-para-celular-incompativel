package com.d3d.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val blue = Color.parseColor("#0D47A1")
    private val green = Color.parseColor("#1B8A3B")
    private val red = Color.parseColor("#C62828")
    private val actionPerm = "com.d3d.nfc.USB_PERMISSION"
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val connecting = AtomicBoolean(false)
    private lateinit var prefs: SharedPreferences

    private var statusBox: TextView? = null
    private var linkInput: EditText? = null
    private var nameInput: EditText? = null
    private var directCheck: CheckBox? = null
    private var lastStatus = "Conecte o cabo OTG com o adaptador USB. A conexão é automática."
    private var lastColor = blue

    private var inDemo = false
    @Volatile private var demoRunning = false
    private var demoRoot: FrameLayout? = null
    private var dot: View? = null
    private var hint: TextView? = null
    private var banner: LinearLayout? = null
    private var bannerHide: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionPerm -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connect(true)
                    } else {
                        setStatus("Permissão USB negada. Toque em CONECTAR MÓDULO e aceite.", red)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Pn532.close()
                    setStatus("Módulo desconectado. Reconecte o cabo.", red)
                }
            }
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nfc", MODE_PRIVATE)
        window.statusBarColor = blue
        val f = IntentFilter().apply {
            addAction(actionPerm)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        showMain()
        connect(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        connect(false)
    }

    override fun onResume() {
        super.onResume()
        if (inDemo) startDemoLoop() else if (!Pn532.connected) connect(false)
    }

    override fun onPause() {
        super.onPause()
        demoRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        demoRunning = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        Pn532.close()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inDemo) showMain() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- helpers de UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radius: Int, strokeW: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (strokeW > 0) setStroke(dp(strokeW), stroke)
        }

    private fun makeButton(label: String, fill: Int, textColor: Int, stroke: Int): Button =
        Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            letterSpacing = 0.08f
            stateListAnimator = null
            background = rounded(fill, stroke, 12, 2)
            layoutParams = LinearLayout.LayoutParams(match, dp(56)).apply { topMargin = dp(16) }
        }

    private fun setStatus(msg: String, color: Int = blue) {
        lastStatus = msg
        lastColor = color
        ui.post {
            statusBox?.text = msg
            statusBox?.background = rounded(Color.WHITE, color, 12, 2)
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is TagException -> "A tag saiu do alcance ou está protegida contra gravação. Tente de novo."
        is NfcException -> {
            val m = e.message ?: ""
            if (m.startsWith("DIAG")) {
                m
            } else if (m.contains("ACK") || m.contains("a tempo") || m.contains("desconectado")) {
                "O módulo não respondeu. Confira o cabo OTG e toque em CONECTAR MÓDULO."
            } else m
        }
        else -> "Algo deu errado. Tente novamente."
    }

    private fun normalizeUrl(s: String): String? {
        var u = s.trim()
        if (u.isEmpty() || u.contains(" ")) return null
        if (!u.contains("://")) u = "https://$u"
        if (!u.contains(".")) return null
        return u
    }

    // ---------------------------------------------------------------- tela principal

    private fun showMain() {
        inDemo = false
        demoRunning = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(blue)
            setPadding(dp(20), dp(40), dp(20), dp(28))
        }
        header.addView(TextView(this).apply {
            text = "Gravador NFC"
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "PN532 via cabo OTG"
            setTextColor(Color.parseColor("#DDE6F5"))
            textSize = 16f
        })
        root.addView(header, LinearLayout.LayoutParams(match, wrap))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }

        val btnConnect = makeButton("CONECTAR MÓDULO", blue, Color.WHITE, blue)
        btnConnect.setOnClickListener { connect(true) }
        body.addView(btnConnect)

        val status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) }
        }
        statusBox = status
        body.addView(status)

        val btnRead = makeButton("LER TAG (MOSTRAR UID)", Color.WHITE, blue, blue)
        btnRead.setOnClickListener { readTag() }
        body.addView(btnRead)

        val btnLoop = makeButton("TESTE LOOPBACK (CH340)", Color.WHITE, Color.parseColor("#555555"), Color.parseColor("#9E9E9E"))
        btnLoop.setOnClickListener { loopbackTest() }
        body.addView(btnLoop)

        body.addView(divider())

        body.addView(label("Link para gravar na tag"))
        val link = EditText(this).apply {
            hint = "https://g.page/r/..."
            setText(prefs.getString("link", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, Color.parseColor("#9E9E9E"), 8, 1)
            layoutParams = LinearLayout.LayoutParams(match, dp(60)).apply { topMargin = dp(10) }
        }
        linkInput = link
        body.addView(link)

        val btnWrite = makeButton("GRAVAR NA TAG", Color.BLACK, Color.WHITE, Color.BLACK)
        btnWrite.setOnClickListener { writeTag() }
        body.addView(btnWrite)

        body.addView(divider())

        body.addView(label("Demonstração para o cliente"))
        val name = EditText(this).apply {
            hint = "Nome da empresa (aparece na notificação)"
            setText(prefs.getString("name", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, Color.parseColor("#9E9E9E"), 8, 1)
            layoutParams = LinearLayout.LayoutParams(match, dp(60)).apply { topMargin = dp(10) }
        }
        nameInput = name
        body.addView(name)

        val direct = CheckBox(this).apply {
            text = "Abrir o link direto (sem notificação)"
            isChecked = prefs.getBoolean("direct", false)
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) }
        }
        directCheck = direct
        body.addView(direct)

        val btnDemo = makeButton("INICIAR DEMONSTRAÇÃO", green, Color.WHITE, green)
        btnDemo.setOnClickListener { startDemo() }
        body.addView(btnDemo)

        root.addView(body, LinearLayout.LayoutParams(match, wrap))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        setStatus(lastStatus, lastColor)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#222222"))
        layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply {
            topMargin = dp(28)
            bottomMargin = dp(8)
        }
    }

    private fun label(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 17f
        setTextColor(Color.BLACK)
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) }
    }

    // ---------------------------------------------------------------- conexão USB

    private fun connect(manual: Boolean) {
        if (Pn532.connected) {
            if (manual) setStatus("Módulo conectado (firmware ${Pn532.firmware}). Pronto para usar.", green)
            return
        }
        val dev = Pn532.findDevice(this)
        if (dev == null) {
            if (manual) setStatus("Nenhum adaptador USB encontrado. Confira o cabo OTG.", red)
            return
        }
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(dev)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(actionPerm).setPackage(packageName), flags)
            um.requestPermission(dev, pi)
            return
        }
        if (!connecting.compareAndSet(false, true)) return
        setStatus("Conectando ao módulo…")
        io.execute {
            try {
                Pn532.open(applicationContext, dev)
                setStatus("Módulo conectado (firmware ${Pn532.firmware}). Pronto para usar.", green)
            } catch (e: Exception) {
                setStatus(friendly(e), red)
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun loopbackTest() {
        val dev = Pn532.findDevice(this)
        if (dev == null) {
            setStatus("Nenhum adaptador USB encontrado. Confira o cabo OTG.", red)
            return
        }
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(dev)) {
            connect(true)
            return
        }
        setStatus("Testando loopback… (TXD ligado no RXD do CH340, sem o PN532)")
        io.execute {
            try {
                val r = Pn532.loopback(applicationContext, dev)
                if (r == "OK") {
                    setStatus("Loopback OK: o CH340 e o app funcionam no celular.", green)
                } else {
                    setStatus("Loopback falhou ($r).", red)
                }
            } catch (e: Exception) {
                setStatus("Loopback: ${e.message}", red)
            }
        }
    }

    private fun requireConnected(): Boolean {
        if (Pn532.connected) return true
        setStatus("Módulo não conectado. Toque em CONECTAR MÓDULO.", red)
        return false
    }

    // ---------------------------------------------------------------- ler e gravar

    private fun readTag() {
        if (!requireConnected()) return
        setStatus("Aproxime a tag da antena do módulo…")
        io.execute {
            try {
                val tag = Pn532.waitTag(10000)
                if (tag == null) {
                    setStatus("Nenhuma tag detectada. Aproxime a tag bem rente à antena.", red)
                    return@execute
                }
                var msg = "UID: ${tag.uidHex}"
                if (tag.isType2) {
                    val u = try { Pn532.readUrl() } catch (e: TagException) { null }
                    msg += if (u != null) "\nLink gravado: $u" else "\nTag sem link gravado"
                }
                setStatus(msg, green)
            } catch (e: Exception) {
                setStatus(friendly(e), red)
            }
        }
    }

    private fun writeTag() {
        if (!requireConnected()) return
        val url = normalizeUrl(linkInput?.text?.toString() ?: "")
        if (url == null) {
            setStatus("Digite um link válido (ex.: https://g.page/r/...).", red)
            return
        }
        prefs.edit().putString("link", url).apply()
        setStatus("Aproxime a tag NTAG da antena para gravar…")
        io.execute {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val tag = Pn532.waitTag(15000)
                    if (tag == null) {
                        setStatus("Nenhuma tag detectada. Aproxime e tente de novo.", red)
                        return@execute
                    }
                    Pn532.writeAndVerify(url, tag)
                    setStatus("Gravado e verificado!\nUID: ${tag.uidHex}\n$url", green)
                    return@execute
                } catch (e: TagException) {
                    if (attempt >= 3) {
                        setStatus(friendly(e), red)
                        return@execute
                    }
                    Thread.sleep(150)
                } catch (e: Exception) {
                    setStatus(friendly(e), red)
                    return@execute
                }
            }
        }
    }

    // ---------------------------------------------------------------- demonstração

    private fun startDemo() {
        if (!requireConnected()) return
        prefs.edit()
            .putString("name", nameInput?.text?.toString()?.trim() ?: "")
            .putBoolean("direct", directCheck?.isChecked ?: false)
            .apply()
        showDemo()
    }

    private fun showDemo() {
        inDemo = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0E1220")) }
        demoRoot = root

        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        clockBox.addView(TextClock(this).apply {
            format24Hour = "H:mm"
            format12Hour = "h:mm"
            textSize = 84f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        clockBox.addView(TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"
            format12Hour = "EEEE, d 'de' MMMM"
            textSize = 18f
            setTextColor(Color.parseColor("#C9CEDB"))
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(clockBox, FrameLayout.LayoutParams(match, wrap).apply { topMargin = dp(150) })

        val d = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(green)
            }
        }
        dot = d
        root.addView(d, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16)
            marginEnd = dp(16)
        })

        val h = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0B4"))
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        hint = h
        root.addView(h, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM).apply { bottomMargin = dp(90) })

        val exit = TextView(this).apply {
            text = "Sair"
            setTextColor(Color.parseColor("#5C6378"))
            textSize = 14f
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { showMain() }
        }
        root.addView(exit, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START))

        setContentView(root)
        startDemoLoop()
    }

    private fun startDemoLoop() {
        if (demoRunning) return
        demoRunning = true
        val direct = prefs.getBoolean("direct", false)
        val name = prefs.getString("name", "") ?: ""
        Thread {
            var armed = true
            var absent = 0
            var failures = 0
            while (demoRunning) {
                try {
                    if (!Pn532.connected) throw NfcException("desconectado")
                    val tag = Pn532.poll()
                    failures = 0
                    setDot(true)
                    if (tag != null) {
                        absent = 0
                        if (armed) {
                            var failedRead = false
                            val url = try {
                                if (tag.isType2) Pn532.readUrl() else null
                            } catch (e: TagException) {
                                failedRead = true
                                null
                            }
                            if (!failedRead) {
                                armed = false
                                ui.post { onTagUrl(url, direct, name) }
                            }
                        }
                    } else {
                        absent++
                        if (absent >= 4) armed = true
                    }
                    Thread.sleep(60)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failures++
                    setDot(false)
                    if (failures >= 2) Pn532.reconnect(applicationContext)
                    try { Thread.sleep(500) } catch (ie: InterruptedException) { break }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun setDot(ok: Boolean) {
        ui.post {
            (dot?.background as? GradientDrawable)?.setColor(if (ok) green else Color.parseColor("#E0A100"))
        }
    }

    private fun onTagUrl(url: String?, direct: Boolean, name: String) {
        if (!inDemo) return
        if (url == null) {
            showHint("Tag sem link gravado")
        } else if (direct) {
            vibrate()
            openLink(url)
        } else {
            showBanner(url, name)
        }
    }

    private fun showHint(msg: String) {
        hint?.text = msg
        hint?.animate()?.alpha(1f)?.setDuration(200)?.start()
        ui.postDelayed({ hint?.animate()?.alpha(0f)?.setDuration(400)?.start() }, 3000)
    }

    private fun prettyTitle(url: String): String {
        val host = Uri.parse(url).host ?: url
        return if (host.contains("google") || host.contains("g.page") || host.contains("goo.gl")) {
            "Avaliar no Google"
        } else host
    }

    private fun showBanner(url: String, name: String) {
        val root = demoRoot ?: return
        hideBanner(false)
        val title = if (name.isNotEmpty()) "Avalie $name" else prettyTitle(url)
        val host = Uri.parse(url).host ?: url

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#F2F2F7"), 0, 22, 0)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(8).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "NFC"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(blue, 0, 10, 0)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#111111"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        })
        texts.addView(TextView(this).apply {
            text = "Toque para abrir · $host"
            setTextColor(Color.parseColor("#555555"))
            textSize = 13f
            maxLines = 1
        })
        card.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        card.addView(TextView(this).apply {
            text = "agora"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
        })

        root.addView(card, FrameLayout.LayoutParams(match, wrap).apply {
            setMargins(dp(10), dp(44), dp(10), 0)
        })
        banner = card
        card.translationY = -dp(220).toFloat()
        card.animate().translationY(0f).setDuration(450).setInterpolator(OvershootInterpolator(1.1f)).start()
        vibrate()
        card.setOnClickListener {
            openLink(url)
            hideBanner(true)
        }
        val r = Runnable { hideBanner(true) }
        bannerHide = r
        ui.postDelayed(r, 8000)
    }

    private fun hideBanner(animated: Boolean) {
        bannerHide?.let { ui.removeCallbacks(it) }
        bannerHide = null
        val b = banner ?: return
        banner = null
        if (animated) {
            b.animate().translationY(-dp(220).toFloat()).setDuration(250).withEndAction {
                (b.parent as? ViewGroup)?.removeView(b)
            }.start()
        } else {
            (b.parent as? ViewGroup)?.removeView(b)
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(60)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.decorView.systemUiVisibility = 0
    }
}
