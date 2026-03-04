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
import androidx.fragment.app.Fragment
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Smart Voice Cooldown
    private var lastSpokenLabel = ""
    private var lastSpokenTime = 0L
    private val SPEECH_COOLDOWN = 6000L // 6 seconds before repeating the same object

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
            } else {
                attemptSmartConnection()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            ascentaService = null
            isConnected = false
            stopHeartbeat()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!hasPermissions()) ActivityCompat.requestPermissions(this, PERMISSIONS, 1)

        val intent = Intent(this, AscentaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setupUI()
        loadVoskModel()
        if (savedInstanceState == null) showHome()
    }

    private fun setupUI() {
        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_navigation)
        fab = findViewById(R.id.fab_home)

        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.offscreenPageLimit = 2

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
        val transaction = supportFragmentManager.beginTransaction()
        var homeFrag = supportFragmentManager.findFragmentByTag("HOME")
        if (homeFrag == null) {
            homeFrag = InfoFragment()
            transaction.add(R.id.fragment_container, homeFrag, "HOME")
        } else transaction.show(homeFrag)
        transaction.commitAllowingStateLoss()
        viewPager.visibility = View.INVISIBLE
        fab.hide()
    }

    private fun hideHome() {
        val homeFrag = supportFragmentManager.findFragmentByTag("HOME")
        if (homeFrag != null) supportFragmentManager.beginTransaction().hide(homeFrag).commitAllowingStateLoss()
        viewPager.visibility = View.VISIBLE
        fab.show()
    }

    private fun attemptSmartConnection() {
        if (!isConnected) {
            ascentaService?.scanForNicla(object : AscentaService.ScanResultCallback {
                override fun onFound() {
                    runOnUiThread { showWifiSetupDialog() }
                }
                override fun onTimeout() {
                    runOnUiThread {
                        ascentaService?.isConnected = true
                        isConnected = true
                        onStatusUpdate("Connected (Auto)")
                        hideHome()
                        startHeartbeat()
                    }
                }
            })
        }
    }

    fun connectToNicla() {
        attemptSmartConnection()
    }

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
                            isConnected = false
                            stopHeartbeat()
                            showHome()
                            // Force explicit Disconnected string on timeout
                            (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus("Disconnected")
                        }
                    }
                } else {
                    delay(5000)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun showWifiSetupDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_setup, null)
        val etSsid = view.findViewById<EditText>(R.id.et_ssid)
        val etPass = view.findViewById<EditText>(R.id.et_password)

        val prefs = getSharedPreferences("AscentaPrefs", Context.MODE_PRIVATE)

        var currentSSID = ""
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val s = info.ssid
            if (s != null && s.startsWith("\"") && s.endsWith("\"")) {
                currentSSID = s.substring(1, s.length - 1)
            } else if (s != null) {
                currentSSID = s
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (currentSSID == "<unknown ssid>") currentSSID = ""

        if (currentSSID.isNotEmpty()) {
            etSsid.setText(currentSSID)
        } else {
            etSsid.setText(prefs.getString("WIFI_SSID", ""))
        }
        etPass.setText(prefs.getString("WIFI_PASS", ""))

        AlertDialog.Builder(this)
            .setTitle("WiFi Setup")
            .setView(view)
            .setPositiveButton("Connect") { _, _ ->
                val ssid = etSsid.text.toString()
                val pass = etPass.text.toString()
                prefs.edit().putString("WIFI_SSID", ssid).putString("WIFI_PASS", pass).apply()
                ascentaService?.startProvisioning(ssid, pass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun sendCommand(cmd: String) { ascentaService?.sendTcpCommand(cmd) }

    fun speakText(text: String) { ascentaService?.speakText(text) }

    override fun onStatusUpdate(status: String) {
        if (status == "pong") {
            lastPingResponse = System.currentTimeMillis()
            return
        }

        if (status.startsWith("BATTERY_STATUS:")) {
            val soc = status.substringAfter("BATTERY_STATUS:")
            speakText("Akkustand: $soc Prozent")
            runOnUiThread { (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus("Akku: $soc%") }
            return
        }

        if (status == "BATTERY_LOW") {
            speakText("Warnung: Akku kritisch bei zehn Prozent")
            runOnUiThread { (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus("AKKU KRITISCH!") }
            return
        }

        if (status.contains("Connected")) {
            lastPingResponse = System.currentTimeMillis()
        }

        runOnUiThread {
            // Update state first before telling the UI to refresh
            if (status.contains("Connected") && !isConnected) {
                isConnected = true
                hideHome()
                startHeartbeat()
            } else if (status == "Disconnected") {
                isConnected = false
                stopHeartbeat()
                showHome()
            }

            // Now update the UI so it accurately reads the new isConnected state
            (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus(status)
        }
    }

    override fun onImageReceived(bitmap: Bitmap) {
        runOnUiThread { (findFragment(0) as? ImageFragment)?.displayImage(bitmap) }
    }

    override fun onTranscriptUpdate(text: String) {
        if (text == "Audio Ready") processLocalAudio()
    }

    override fun onDetectionResult(label: String, conf: String, dist: String) {
        runOnUiThread {
            if (label != "Uncertain" && label != "Error" && label.isNotEmpty()) {
                val now = System.currentTimeMillis()

                val distanceMm = dist.toIntOrNull() ?: 0
                val isCollisionWarning = distanceMm in 10..500
                val activeCooldown = if (isCollisionWarning) 3000L else SPEECH_COOLDOWN

                if (now - lastSpokenTime > activeCooldown || label != lastSpokenLabel) {
                    lastSpokenLabel = label
                    lastSpokenTime = now

                    if (isCollisionWarning) {
                        speakText("Kollisionswarnung! $label direkt vor dir!")
                    } else {
                        val distanceText = if (distanceMm in 501..4000) {
                            val meters = distanceMm / 1000
                            val cm = (distanceMm % 1000) / 10
                            if (meters > 0) "in $meters Meter und $cm Zentimeter Entfernung"
                            else "in $cm Zentimeter Entfernung"
                        } else ""

                        val speechText = "Vor dir ist: $label $distanceText".trim()
                        speakText(speechText)
                    }
                }
            }
        }
    }

    private fun processLocalAudio() {
        if (voskModel == null) {
            runOnUiThread {
                Toast.makeText(this, "Sprachmodell noch nicht bereit!", Toast.LENGTH_SHORT).show()
                speakText("Sprachmodell nicht geladen.")
            }
            return
        }

        Executors.newSingleThreadExecutor().execute {
            try {
                val file = File(cacheDir, "mic.wav")
                if (!file.exists()) return@execute

                val bytes = file.readBytes()
                // Strip the 44-byte WAV header safely
                val audioData = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes

                val recognizer = Recognizer(voskModel, 16000.0f)

                // FEED VOSK IN CHUNKS - This fixes the STT buffer freeze
                val chunkSize = 4096
                var offset = 0
                while (offset < audioData.size) {
                    val length = minOf(chunkSize, audioData.size - offset)
                    recognizer.acceptWaveForm(audioData.copyOfRange(offset, offset + length), length)
                    offset += length
                }

                // Force read the final result
                val resultJson = recognizer.finalResult
                val text = JSONObject(resultJson).optString("text", "")

                runOnUiThread {
                    (findFragment(1) as? AudioFragment)?.updateTranscript("You: $text")
                    if (text.isNotEmpty()) {
                        speakText("Du sagtest: $text")
                    } else {
                        speakText("Ich habe nichts verstanden.")
                    }
                }
                recognizer.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadVoskModel() {
        val root = File(filesDir, "model")
        CoroutineScope(Dispatchers.IO).launch {
            if (!root.exists()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Entpacke Sprachmodell...", Toast.LENGTH_LONG).show() }
                copyAssets("model", root.absolutePath)
            }
            val dir = findModelDir(root)
            if (dir != null) {
                try {
                    voskModel = Model(dir.absolutePath)
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Sprachmodell bereit", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    root.deleteRecursively()
                    loadVoskModel()
                }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Fehler: Modell-Ordner nicht gefunden", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun findModelDir(dir: File): File? {
        if (File(dir, "conf").exists()) return dir
        if (File(dir, "final.mdl").exists()) return if (dir.name == "am") dir.parentFile else dir
        dir.listFiles()?.forEach { val f = findModelDir(it); if (f != null) return f }
        return null
    }

    private fun copyAssets(path: String, out: String) {
        val list = assets.list(path) ?: return
        if (list.isNotEmpty()) {
            File(out).mkdirs()
            list.forEach { copyAssets("$path/$it", "$out/$it") }
        } else {
            val dir = File(out).parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            assets.open(path).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
    }

    private fun hasPermissions() = PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun findFragment(idx: Int) = supportFragmentManager.findFragmentByTag("f$idx")

    class ViewPagerAdapter(fa: androidx.fragment.app.FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 2
        override fun createFragment(idx: Int) = when(idx) { 0 -> ImageFragment(); else -> AudioFragment() }
    }
}