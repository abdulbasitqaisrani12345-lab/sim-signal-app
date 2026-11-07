package pk.zong.signalmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var telephonyManager: TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private var pageLoaded = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (pageLoaded) {
                pushLiveSignalsToUi()
            }
            handler.postDelayed(this, 2000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                pushLiveSignalsToUi()
            }
        }

        // Load the exact same 4-SIM HTML UI from assets
        webView.loadUrl("file:///android_asset/index.html")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val perms = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 101)
        } else {
            handler.post(pollRunnable)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handler.post(pollRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun pushLiveSignalsToUi() {
        val permsGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permsGranted) return

        val cells: List<CellInfo> = telephonyManager.allCellInfo ?: emptyList()
        val payload = JSONObject()

        for (cell in cells) {
            when (cell) {
                is CellInfoLte -> {
                    val id = cell.cellIdentity
                    val str = cell.cellSignalStrength
                    val mnc = if (Build.VERSION.SDK_INT >= 28) (id.mncString ?: "") else id.mnc.toString()
                    val simKey = mapMncToSimKey(mnc)
                    if (simKey != null && str.rsrp in -140..-43) {
                        val obj = JSONObject()
                        obj.put("dbm", str.rsrp)
                        obj.put("rush", if (str.rsrq in -20..-3) str.rsrq else -10)
                        payload.put(simKey, obj)
                    }
                }
                is CellInfoWcdma -> {
                    val id = cell.cellIdentity
                    val str = cell.cellSignalStrength
                    val mnc = if (Build.VERSION.SDK_INT >= 28) (id.mncString ?: "") else id.mnc.toString()
                    val simKey = mapMncToSimKey(mnc)
                    if (simKey != null && str.dbm in -120..-24 && !payload.has(simKey)) {
                        val obj = JSONObject()
                        obj.put("dbm", str.dbm)
                        obj.put("rush", -10)
                        payload.put(simKey, obj)
                    }
                }
            }
        }

        if (payload.length() > 0) {
            val jsCall = "window.updateFromAndroidHardware(${JSONObject.quote(payload.toString())});"
            webView.evaluateJavascript(jsCall, null)
        }
    }

    private fun mapMncToSimKey(mnc: String): String? {
        return when (mnc.trimStart('0')) {
            "1", "7" -> "jazz"     // Jazz (PLMN 410-01 / 410-07)
            "3" -> "ufone"         // Ufone (PLMN 410-03)
            "4" -> "zong"          // Zong (PLMN 410-04)
            "6" -> "telenor"       // Telenor (PLMN 410-06)
            else -> null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
