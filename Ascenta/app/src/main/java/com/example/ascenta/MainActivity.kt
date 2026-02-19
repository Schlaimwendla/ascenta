package com.example.ascenta

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AscentaService.LocalBinder
            ascentaService = binder.getService()
            ascentaService?.setServiceCallback(this@MainActivity)

            if (ascentaService?.isConnected == true) {
                isConnected = true
                onStatusUpdate("Connected")
                hideHome()
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

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1)
        }

        val intent = Intent(this, AscentaService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setupUI()
        loadVoskModel()

        if (savedInstanceState == null) {
            showHome()
        }
    }

    private fun setupUI() {
        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_navigation)
        fab = findViewById(R.id.fab_home)

        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        viewPager.isUserInputEnabled = true

        bottomNav.setOnItemSelectedListener { item ->
            hideHome()
            when (item.itemId) {
                R.id.nav_image -> viewPager.currentItem = 0
                R.id.nav_audio -> viewPager.currentItem = 1
                R.id.nav_detection -> viewPager.currentItem = 2
            }
            true
        }

        fab.setOnClickListener {
            showHome()
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = when (position) {
                    0 -> R.id.nav_image
                    1 -> R.id.nav_audio
                    2 -> R.id.nav_detection
                    else -> -1
                }
                if (itemId != -1) {
                    bottomNav.selectedItemId = itemId
                }
            }
        })
    }

    private fun showHome() {
        val fragmentManager = supportFragmentManager
        var homeFrag = fragmentManager.findFragmentByTag("HOME")
        val transaction = fragmentManager.beginTransaction()

        if (homeFrag == null) {
            homeFrag = InfoFragment()
            val containerId = if (findViewById<View>(R.id.fragment_container) != null) R.id.fragment_container else android.R.id.content
            transaction.add(containerId, homeFrag, "HOME")
        } else {
            transaction.show(homeFrag)
        }

        transaction.commitAllowingStateLoss()
        viewPager.visibility = View.INVISIBLE
        bottomNav.visibility = View.VISIBLE
        fab.hide()

        val menu = bottomNav.menu
        menu.setGroupCheckable(0, true, false)
        for (i in 0 until menu.size()) {
            menu.getItem(i).isChecked = false
        }
        menu.setGroupCheckable(0, true, true)
    }

    private fun hideHome() {
        val homeFrag = supportFragmentManager.findFragmentByTag("HOME")
        if (homeFrag != null) {
            supportFragmentManager.beginTransaction().hide(homeFrag).commitAllowingStateLoss()
        }
        viewPager.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        fab.show()
    }

    private fun showWifiSetupDialog() {
        if (isConnected) {
            hideHome()
            return
        }

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

        val prefs = getSharedPreferences("AscentaPrefs", Context.MODE_PRIVATE)
        val savedPass = prefs.getString("WIFI_PASS", "")

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_setup, null)
        val etSsid = view.findViewById<EditText>(R.id.et_ssid)
        val etPass = view.findViewById<EditText>(R.id.et_password)

        etSsid.setText(currentSSID)
        if (!savedPass.isNullOrEmpty()) etPass.setText(savedPass)

        AlertDialog.Builder(this)
            .setTitle("Connect Nicla")
            .setView(view)
            .setCancelable(true)
            .setPositiveButton("Connect") { _, _ ->
                val ssid = etSsid.text.toString()
                val pass = etPass.text.toString()
                if (ssid.isNotEmpty() && pass.isNotEmpty()) {
                    prefs.edit().putString("WIFI_PASS", pass).apply()
                    ascentaService?.startProvisioning(ssid, pass)
                } else {
                    Toast.makeText(this, "Credentials needed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun connectToNicla() {
        if (ascentaService?.isConnected == true) {
            isConnected = true
            onStatusUpdate("Connected")
            hideHome()
        } else {
            showWifiSetupDialog()
        }
    }

    fun disconnectNicla() {
        isConnected = false
        val homeFrag = supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment
        homeFrag?.updateStatus("Disconnected")
    }

    fun sendCommand(cmd: String) {
        ascentaService?.sendUdpCommand(cmd)
    }

    fun speakText(text: String) {
        ascentaService?.speakText(text)
    }

    override fun onStatusUpdate(status: String) {
        runOnUiThread {
            val homeFrag = supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment
            homeFrag?.updateStatus(status)

            if (status.contains("WiFi") || status.contains("Connected") || status.contains("Credentials Sent")) {
                isConnected = true
                if (viewPager.visibility != View.VISIBLE) {
                    hideHome()
                }
            }
        }
    }

    override fun onImageReceived(bitmap: Bitmap) {
        runOnUiThread {
            val imgFrag = findFragment(0) as? ImageFragment
            imgFrag?.displayImage(bitmap)
        }
    }

    override fun onTranscriptUpdate(text: String) {
        if (text == "Audio Ready") {
            processLocalAudio()
        }
    }

    override fun onDetectionResult(label: String, conf: String) {
        runOnUiThread {
            val detFrag = findFragment(2) as? DetectionFragment
            if (detFrag != null && detFrag.isAdded) {
                detFrag.updateResult(label, conf)
            }

            if (label != "Uncertain" && label != "Error" && label.isNotEmpty()) {
                speakText("Detected $label")
            }
        }
    }

    private fun processLocalAudio() {
        if (voskModel == null) return

        Executors.newSingleThreadExecutor().execute {
            try {
                val file = File(cacheDir, "mic.wav")
                if (!file.exists()) return@execute

                val bytes = file.readBytes()
                val audioData = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes

                val recognizer = Recognizer(voskModel, 16000.0f)
                if (recognizer.acceptWaveForm(audioData, audioData.size)) {
                    val result = recognizer.finalResult
                    val text = JSONObject(result).optString("text", "")

                    runOnUiThread {
                        val audioFrag = findFragment(1) as? AudioFragment
                        audioFrag?.updateTranscript("You said: $text")
                        if (text.isNotEmpty()) speakText(text)
                    }
                }
                recognizer.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadVoskModel() {
        val rootModelPath = File(filesDir, "model")
        CoroutineScope(Dispatchers.IO).launch {
            if (!rootModelPath.exists()) {
                try {
                    copyAssets("model", rootModelPath.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var modelDir = findModelDir(rootModelPath)

            if (modelDir == null) {
                try {
                    rootModelPath.deleteRecursively()
                    copyAssets("model", rootModelPath.absolutePath)
                    modelDir = findModelDir(rootModelPath)
                } catch(e: Exception){ e.printStackTrace() }
            }

            if (modelDir != null) {
                try {
                    voskModel = Model(modelDir.absolutePath)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "AI Model Loaded", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        rootModelPath.deleteRecursively()
                        copyAssets("model", rootModelPath.absolutePath)
                        val retryDir = findModelDir(rootModelPath)
                        if (retryDir != null) {
                            voskModel = Model(retryDir.absolutePath)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "AI Model Recovered", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            throw Exception("Model dir not found after retry")
                        }
                    } catch(retryEx: Exception) {
                        retryEx.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Model Failed. Reinstall App.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Model not found in assets", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun findModelDir(dir: File): File? {
        // Strategy: Look for "conf" folder which marks the root of a Vosk model
        if (File(dir, "conf").exists() && File(dir, "conf").isDirectory) return dir

        // Fallback: If we find "final.mdl" inside "am", return the PARENT directory
        if (File(dir, "final.mdl").exists()) {
            if (dir.name == "am") return dir.parentFile
            return dir
        }

        val files = dir.listFiles() ?: return null
        for (file in files) {
            if (file.isDirectory) {
                val found = findModelDir(file)
                if (found != null) return found
            }
        }
        return null
    }

    private fun copyAssets(assetPath: String, outPath: String) {
        val assetsList = assets.list(assetPath) ?: return
        if (assetsList.isNotEmpty()) {
            val dir = File(outPath)
            if (!dir.exists()) dir.mkdirs()
            for (asset in assetsList) {
                copyAssets("$assetPath/$asset", "$outPath/$asset")
            }
        } else {
            try {
                val dir = File(outPath).parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                val inputStream = assets.open(assetPath)
                val outputStream = FileOutputStream(outPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.flush()
                outputStream.close()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun findFragment(index: Int): Fragment? {
        return supportFragmentManager.findFragmentByTag("f$index")
    }

    class ViewPagerAdapter(fa: androidx.fragment.app.FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ImageFragment()
                1 -> AudioFragment()
                2 -> DetectionFragment()
                else -> ImageFragment()
            }
        }
    }
}