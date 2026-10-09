package no.molle.intervaller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pending: (() -> Unit)? = null
    private var askedNotif = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleHub.init(applicationContext)

        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF0E1215.toInt())

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = true
        s.setSupportMultipleWindows(true)
        s.textZoom = 100

        // Holder siden i gang med full fart også når appen ligger i bakgrunnen.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "file") return false
                openExternal(request.url.toString())
                return true
            }
        }
        // Lenker med target="_blank" åpnes i nettleseren.
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        openExternal(r.url.toString())
                        v.destroy()
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "MolleNative")
        BleHub.js = { script -> runOnUiThread { if (!isDestroyed) web.evaluateJavascript(script, null) } }
        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onDestroy() {
        BleHub.js = null
        web.destroy()
        super.onDestroy()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    // ---------- tillatelser ----------

    private fun btPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Ber om Bluetooth-tilgang (og varsler) ved behov, sjekker at Bluetooth er på, og kjører så handlingen. */
    @SuppressLint("MissingPermission")
    fun withBluetooth(action: () -> Unit) {
        val notif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val requiredMissing = btPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (requiredMissing.isNotEmpty()) {
            val ask = requiredMissing.toMutableList()
            if (notif) ask.add(Manifest.permission.POST_NOTIFICATIONS)
            pending = { withBluetooth(action) }
            requestPermissions(ask.toTypedArray(), 1)
            return
        }
        if (notif && !askedNotif) {
            // Varsler er ikke påkrevd, men gir status i varslingsfeltet.
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            BleHub.message("Fant ikke Bluetooth på telefonen.")
            return
        }
        if (!adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
            BleHub.message("Slå på Bluetooth og trykk en gang til.")
            return
        }
        action()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val action = pending
        pending = null
        val ok = btPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok) action?.invoke()
        else BleHub.message("Appen trenger Bluetooth-tilgang. Gi tilgang under Innstillinger → Apper → Mølle → Tillatelser.")
    }

    fun keepScreenOn(on: Boolean) = runOnUiThread {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- broen mellom nettsiden og Android ----------

    inner class Bridge {
        @JavascriptInterface fun available(): Boolean = true
        @JavascriptInterface fun status() = BleHub.pushStatus()

        @JavascriptInterface fun zwiftStart() = runOnUiThread { withBluetooth { BleHub.startRsc() } }
        @JavascriptInterface fun zwiftStop() = runOnUiThread { BleHub.stopRsc() }

        @JavascriptInterface fun hrScan() = runOnUiThread { withBluetooth { BleHub.scanHr() } }
        @JavascriptInterface fun hrConnect(id: String) = runOnUiThread { withBluetooth { BleHub.connectHr(id) } }
        @JavascriptInterface fun hrDisconnect() = runOnUiThread { BleHub.disconnectHr() }

        @JavascriptInterface fun setState(json: String) {
            BleHub.setState(json)
            keepScreenOn(BleHub.sessionActive)
        }

        @JavascriptInterface fun saveFile(name: String, content: String): String = FileSaver.save(this@MainActivity, name, content)
    }
}
