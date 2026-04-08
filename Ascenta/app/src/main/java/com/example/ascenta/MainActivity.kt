package com.example.ascenta

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), AscentaService.ServiceCallback {

    private val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private var ascentaService: AscentaService? = null
    var isConnected = false
    private var voskModel: Model? = null
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fab: FloatingActionButton

    private var lastSpokenLabel = ""
    private var lastSpokenTime = 0L
    private val SPEECH_COOLDOWN = 6000L
    private val voskExecutor = Executors.newSingleThreadExecutor()

    private var heartbeatJob: Job? = null
    private var lastPingResponse = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AscentaService.LocalBinder
            ascentaService = binder.getService()
            ascentaService?.setServiceCallback(this@MainActivity)
            if (ascentaService?.isConnected == true) {
                isConnected = true
                onStatusUpdate("Connected")
                hideHome()
                startHeartbeat()
            } else if (hasPermissions()) {
                attemptSmartConnection()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            ascentaService = null
            isConnected = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = Intent(this, AscentaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setupUI()
        loadVoskModel()
        if (savedInstanceState == null) showHome()

        if (!hasPermissions()) ActivityCompat.requestPermissions(this, PERMISSIONS, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (!isConnected) attemptSmartConnection()
        }
    }

    private fun setupUI() {
        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_navigation)
        fab = findViewById(R.id.fab_home)
        viewPager.adapter = ViewPagerAdapter(this)
        bottomNav.setOnItemSelectedListener { item ->
            hideHome()
            when (item.itemId) {
                R.id.nav_image -> viewPager.currentItem = 0
                R.id.nav_audio -> viewPager.currentItem = 1
            }
            true
        }
        fab.setOnClickListener { showHome() }
    }

    private fun showHome() {
        supportFragmentManager.beginTransaction().let {
            val frag = supportFragmentManager.findFragmentByTag("HOME") ?: InfoFragment()
            if (!frag.isAdded) it.add(R.id.fragment_container, frag, "HOME") else it.show(frag)
            it.commitAllowingStateLoss()
        }
        viewPager.visibility = View.INVISIBLE
        fab.hide()
    }

    private fun hideHome() {
        supportFragmentManager.findFragmentByTag("HOME")?.let {
            supportFragmentManager.beginTransaction().hide(it).commitAllowingStateLoss()
        }
        viewPager.visibility = View.VISIBLE
        fab.show()
    }

    fun attemptSmartConnection() {
        if (!isConnected) {
            ascentaService?.scanForNicla(object : AscentaService.ScanResultCallback {
                override fun onFound() { runOnUiThread { showWifiSetupDialog() } }
                override fun onTimeout() {
                    // BLE not found — Nicla may already be on WiFi
                    // UDP listener is running, show waiting state
                    onStatusUpdate("Warte auf Nicla...")
                }
            })
        }
    }

    fun resetConnection() { ascentaService?.forceDisconnect() }

    private fun startHeartbeat() {
        stopHeartbeat()
        lastPingResponse = System.currentTimeMillis()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (isConnected) {
                    sendCommand("ping")
                    delay(10000)
                    if (System.currentTimeMillis() - lastPingResponse > 25000) {
                        withContext(Dispatchers.Main) {
                            onStatusUpdate("Disconnected")
                            ascentaService?.forceDisconnect()
                        }
                    }
                } else delay(5000)
            }
        }
    }

    private fun stopHeartbeat() { heartbeatJob?.cancel(); heartbeatJob = null }

    private fun showWifiSetupDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_setup, null)
        val etSsid = view.findViewById<EditText>(R.id.et_ssid)
        val etPass = view.findViewById<EditText>(R.id.et_password)
        val prefs = getSharedPreferences("AscentaPrefs", Context.MODE_PRIVATE)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ssid = try { wifiManager.connectionInfo.ssid.replace("\"", "") } catch (_: Exception) { "<unknown ssid>" }
        if (ssid != "<unknown ssid>") etSsid.setText(ssid) else etSsid.setText(prefs.getString("WIFI_SSID", ""))
        etPass.setText(prefs.getString("WIFI_PASS", ""))

        AlertDialog.Builder(this)
            .setTitle("WLAN Einrichtung")
            .setView(view)
            .setPositiveButton("Verbinden") { _, _ ->
                val s = etSsid.text.toString()
                val p = etPass.text.toString()
                prefs.edit().putString("WIFI_SSID", s).putString("WIFI_PASS", p).apply()
                ascentaService?.startProvisioning(s, p)
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    fun sendCommand(cmd: String) { ascentaService?.sendTcpCommand(cmd) }
    fun speakText(text: String) { ascentaService?.speakText(text) }

    override fun onStatusUpdate(status: String) {
        if (status == "pong") { lastPingResponse = System.currentTimeMillis(); return }

        runOnUiThread {
            when {
                status.startsWith("BATTERY_STATUS:") -> {
                    val soc = status.substringAfter("BATTERY_STATUS:")
                    speakText("Akkustand $soc Prozent")
                    (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus("Akku: $soc%")
                }
                status == "Disconnected" -> {
                    isConnected = false
                    stopHeartbeat()
                    showHome()
                    (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus(status)
                }
                status.contains("Connected") -> {
                    isConnected = true
                    hideHome()
                    startHeartbeat()
                    (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus(status)
                }
                else -> (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus(status)
            }
        }
    }

    override fun onImageReceived(bitmap: Bitmap) {
        runOnUiThread { (findFragment(0) as? ImageFragment)?.displayImage(bitmap) }
    }

    override fun onTranscriptUpdate(text: String) {
        if (text == "Audio Ready") processLocalAudio()
    }

    override fun onDetectionResult(label: String, conf: String, distAndPos: String) {
        runOnUiThread {
            val now = System.currentTimeMillis()
            val parts = distAndPos.split("|")
            val distanceMm = parts[0].toIntOrNull() ?: 0
            val position = if (parts.size > 1) parts[1] else "straight"

            // Collision warning logic
            val isCollision = distanceMm in 10..600
            val activeCooldown = if (isCollision) 3000L else SPEECH_COOLDOWN

            if (now - lastSpokenTime > activeCooldown || label != lastSpokenLabel) {
                lastSpokenLabel = label
                lastSpokenTime = now

                val posDe = when(position) {
                    "left" -> "links"
                    "right" -> "rechts"
                    else -> "vor dir"
                }

                if (isCollision) {
                    speakText("Achtung! $label $posDe!")
                } else {
                    val distCm = distanceMm / 10
                    val distText = if (distCm > 60) "in $distCm Zentimeter" else ""
                    speakText("$label $distText $posDe")
                }
            }
        }
    }

    override fun onCollisionWarning(dist: Int, zone: String) {
        runOnUiThread {
            if (zone == "clear") return@runOnUiThread
            val now = System.currentTimeMillis()
            val cooldown = when (zone) {
                "critical" -> 2000L
                "alert" -> 4000L
                else -> 6000L
            }
            if (now - lastSpokenTime > cooldown) {
                lastSpokenTime = now
                val warning = when (zone) {
                    "critical" -> "Stopp! Hindernis bei ${dist / 10} Zentimeter!"
                    "alert" -> "Vorsicht! Hindernis nah, ${dist / 10} Zentimeter"
                    else -> "Hindernis erkannt, ${dist / 10} Zentimeter"
                }
                speakText(warning)
            }
        }
    }

    private fun processLocalAudio() {
        if (voskModel == null) return
        voskExecutor.execute {
            try {
                val bytes = File(cacheDir, "mic.wav").readBytes()
                val audioData = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
                val recognizer = Recognizer(voskModel, 16000.0f)
                recognizer.acceptWaveForm(audioData, audioData.size)
                val text = JSONObject(recognizer.finalResult).optString("text", "")
                runOnUiThread {
                    (findFragment(1) as? AudioFragment)?.updateTranscript("You: $text")
                    if (text.isNotEmpty()) speakText("Du sagtest: $text")
                }
                recognizer.close()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun loadVoskModel() {
        val root = File(filesDir, "model")
        CoroutineScope(Dispatchers.IO).launch {
            if (!root.exists()) copyAssets("model", root.absolutePath)
            try {
                voskModel = Model(root.absolutePath)
            } catch (e: Exception) { root.deleteRecursively() }
        }
    }

    private fun copyAssets(path: String, out: String) {
        val list = assets.list(path) ?: return
        if (list.isNotEmpty()) {
            File(out).mkdirs()
            list.forEach { copyAssets("$path/$it", "$out/$it") }
        } else {
            assets.open(path).use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        voskExecutor.shutdownNow()
        unbindService(serviceConnection)
    }

    private fun hasPermissions() = PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun findFragment(idx: Int) = supportFragmentManager.findFragmentByTag("f$idx")

    class ViewPagerAdapter(fa: androidx.fragment.app.FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 2
        override fun createFragment(idx: Int) = when(idx) { 0 -> ImageFragment(); else -> AudioFragment() }
    }
}