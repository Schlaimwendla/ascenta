package com.example.ascenta

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
// import org.tensorflow.lite.task.vision.detector.Detection

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener { //, ObjectDetectorHelper.DetectorListener {

    private val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val IMAGE_DATA_CHAR = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
    private val IMAGE_CMD_CHAR  = UUID.fromString("80000001-0000-1000-8000-00805f9b34fb")
    private val AUDIO_RX_CHAR   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val AUDIO_TX_CHAR   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val TARGET_DEVICE_NAME = "Nicla"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    var bluetoothGatt: BluetoothGatt? = null
    var isConnected = false
    private val handler = Handler(Looper.getMainLooper())

    private val imageBuffer = ByteBuffer.allocate(200 * 1024)
    private var isReceivingImg = false
    private val START_SEQ = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
    private val END_SEQ = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

    private var audioBuffer: ByteArrayOutputStream? = null
    private var audioExpectedSize = 0

    private lateinit var tts: TextToSpeech
    // private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private var voskModel: Model? = null

    lateinit var viewPager: ViewPager2
    lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val container = findViewById<RelativeLayout>(R.id.main_container)
        (container.background as AnimationDrawable).apply {
            setEnterFadeDuration(2500); setExitFadeDuration(5000); start()
        }

        if (!hasPermissions()) ActivityCompat.requestPermissions(this, PERMISSIONS, 1)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        tts = TextToSpeech(this, this)
        // objectDetectorHelper = ObjectDetectorHelper(context = this, objectDetectorListener = this)

        loadVoskModel()

        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_navigation)
        val fab = findViewById<FloatingActionButton>(R.id.fab_home)

        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.setCurrentItem(0, false)
        viewPager.offscreenPageLimit = 4

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, InfoFragment(), "HOME")
                .commit()
        }

        bottomNav.setOnItemSelectedListener { item ->
            hideHome()
            when (item.itemId) {
                R.id.nav_image -> viewPager.currentItem = 0
                R.id.nav_audio -> viewPager.currentItem = 1
                R.id.nav_detection -> viewPager.currentItem = 2
                R.id.nav_navigation -> viewPager.currentItem = 3
            }
            true
        }

        fab.setOnClickListener { showHome() }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                hideHome()
                bottomNav.menu.findItem(R.id.nav_image).isChecked = false
                bottomNav.menu.findItem(R.id.nav_audio).isChecked = false
                bottomNav.menu.findItem(R.id.nav_detection).isChecked = false
                val navItem = bottomNav.menu.findItem(R.id.nav_navigation)
                if(navItem != null) navItem.isChecked = false

                when (position) {
                    0 -> bottomNav.menu.findItem(R.id.nav_image).isChecked = true
                    1 -> bottomNav.menu.findItem(R.id.nav_audio).isChecked = true
                    2 -> bottomNav.menu.findItem(R.id.nav_detection).isChecked = true
                    3 -> { if(navItem != null) navItem.isChecked = true }
                }
            }
        })
    }

    private fun loadVoskModel() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outputPath = File(filesDir, "model")
                if (!outputPath.exists()) {
                    copyAssets("model", outputPath.absolutePath)
                }
                voskModel = Model(outputPath.absolutePath)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Voice AI Loaded", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Model Load Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
            val inputStream = assets.open(assetPath)
            val outputStream = FileOutputStream(outPath)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.flush()
            outputStream.close()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.GERMAN
    }

    fun connectToNicla() {
        if (isConnected || scanner == null) return
        updateHomeStatus("Scanning for Nicla...")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setDeviceName(TARGET_DEVICE_NAME).build())
        scanner?.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            scanner?.stopScan(scanCallback)
            if (!isConnected) updateHomeStatus("Not Found. Try again.")
        }, 10000)
    }

    fun disconnectNicla() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        updateHomeStatus("Disconnected")
    }

    fun sendCommand(type: String) {
        if (bluetoothGatt == null) {
            Toast.makeText(this, "Not connected!", Toast.LENGTH_SHORT).show()
            return
        }
        val serviceUuid = if (type == "take_picture") UUID.fromString("80000000-0000-1000-8000-00805f9b34fb") else UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val charUuid = if (type == "take_picture") IMAGE_CMD_CHAR else AUDIO_RX_CHAR

        val command = when(type) {
            "take_picture" -> "take_picture"
            "RECORD" -> "RECORD"
            // "START_DETECT" -> "take_picture"
            // "STOP_DETECT" -> "STOP_DETECT"
            else -> "RECORD"
        }

        val service = bluetoothGatt?.getService(serviceUuid)
        val char = service?.getCharacteristic(charUuid)

        if (char != null) {
            char.setValue(command.toByteArray())
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(char)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner?.stopScan(this)
            updateHomeStatus("Found! Connecting...")
            bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                runOnUiThread { updateHomeStatus("Connected. Setting up...") }
                gatt.requestMtu(500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                runOnUiThread { updateHomeStatus("Disconnected") }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val imgService = gatt.getService(UUID.fromString("12345678-1234-5678-1234-567890ABCDEF"))
            val imgChar = imgService?.getCharacteristic(IMAGE_DATA_CHAR)
            if (imgChar != null) enableNotification(gatt, imgChar)

            val audioService = gatt.getService(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
            val audioChar = audioService?.getCharacteristic(AUDIO_TX_CHAR)
            if (audioChar != null) enableNotification(gatt, audioChar)

            runOnUiThread { updateHomeStatus("Ready! Select Vision, Voice or Detect.") }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (characteristic.uuid == IMAGE_DATA_CHAR) {
                handleImageData(data)
            } else if (characteristic.uuid == AUDIO_TX_CHAR) {
                handleAudioData(data)
            }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(CCCD)
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    private fun handleImageData(data: ByteArray) {
        if (!isReceivingImg && startsWith(data, START_SEQ)) {
            isReceivingImg = true
            imageBuffer.clear()
            if (data.size > 4) imageBuffer.put(data, 4, data.size - 4)
            runOnUiThread { notifyImageFragmentStatus("Receiving...") }
        } else if (isReceivingImg) {
            if (endsWith(data, END_SEQ)) {
                imageBuffer.put(data, 0, data.size - 4)
                isReceivingImg = false
                val bytes = ByteArray(imageBuffer.position())
                imageBuffer.rewind(); imageBuffer.get(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                runOnUiThread {
                    if (viewPager.currentItem == 0) {
                        findImageFragment()?.displayImage(bmp)
                    }
                    // else if (viewPager.currentItem == 2) {
                    //     findDetectionFragment()?.displayImage(bmp)
                    //     findDetectionFragment()?.updateResult("Scanning...", "...")
                    //     objectDetectorHelper.detect(bmp, 0)
                    // }
                }
            } else {
                imageBuffer.put(data)
            }
        }
    }

    private fun handleAudioData(data: ByteArray) {
        val str = String(data)
        if (data.size < 64) {
            if (str.startsWith("START:")) {
                try {
                    audioExpectedSize = str.substringAfter(":").trim().toInt()
                    audioBuffer = ByteArrayOutputStream(audioExpectedSize)
                    runOnUiThread { notifyAudioFragmentStatus("Receiving Audio...") }
                    return
                } catch (e: Exception) {}
            } else if (str.startsWith("END")) {
                val bytes = audioBuffer?.toByteArray()
                if (bytes != null) {
                    processFinishedAudio(bytes)
                }
                audioBuffer = null
                return
            }
        }
        audioBuffer?.write(data)
    }

    fun processFinishedAudio(bytes: ByteArray) {
        try {
            val file = File(cacheDir, "mic.wav")
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: Exception) {}

        if (voskModel == null) {
            runOnUiThread { notifyAudioFragmentStatus("Voice Model not ready.") }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val recognizer = Recognizer(voskModel, 16000.0f)
                val offset = if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) 44 else 0
                val audioData = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes

                recognizer.acceptWaveForm(audioData, audioData.size)
                val resultJson = recognizer.finalResult
                val jsonObject = JSONObject(resultJson)
                val text = jsonObject.optString("text", "")

                runOnUiThread { handleVoskResult(text) }

            } catch (e: Exception) {
                runOnUiThread { notifyAudioFragmentStatus("Local STT Error: ${e.message}") }
            }
        }
    }

    private fun handleVoskResult(text: String) {
        if (text.isNotEmpty()) {
            val frag = findAudioFragment()
            frag?.updateTranscript("You said: $text")
            frag?.updateStatus("Done")

            val lower = text.lowercase()
            if (lower.contains("where am i") || lower.contains("location") || lower.contains("wo bin ich")) {
                speakText("Checking location")
                switchToTab(3)
                handler.postDelayed({ findNavigationFragment()?.getCurrentLocation() }, 500)
            }
            // else if (lower.contains("detect") || lower.contains("was ist")) {
            //     speakText("Analyzing")
            //     switchToTab(2)
            //     handler.postDelayed({ findDetectionFragment()?.triggerDetection() }, 500)
            // }
            else {
                speakText(text)
            }
        } else {
            findAudioFragment()?.updateTranscript("No speech detected.")
        }
    }

    fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /*
    override fun onError(error: String) {
        runOnUiThread { findDetectionFragment()?.updateResult("Error", error) }
    }

    override fun onResults(results: MutableList<Detection>?, time: Long, h: Int, w: Int) {
        runOnUiThread {
            if (!results.isNullOrEmpty()) {
                val detection = results[0]
                val label = detection.categories[0].label
                val score = detection.categories[0].score
                val conf = "%.0f%%".format(score * 100)
                findDetectionFragment()?.updateResult(label, conf)
            } else {
                findDetectionFragment()?.updateResult("Nothing found", "0%")
            }
        }
    }
    */

    private fun showHome() {
        val homeFrag = supportFragmentManager.findFragmentByTag("HOME") ?: InfoFragment()
        supportFragmentManager.beginTransaction().show(homeFrag).commit()
        viewPager.visibility = View.INVISIBLE
        bottomNav.menu.findItem(R.id.nav_image).isChecked = false
        bottomNav.menu.findItem(R.id.nav_audio).isChecked = false
        bottomNav.menu.findItem(R.id.nav_detection).isChecked = false
        val navItem = bottomNav.menu.findItem(R.id.nav_navigation)
        if(navItem != null) navItem.isChecked = false
    }

    fun hideHome() {
        val homeFrag = supportFragmentManager.findFragmentByTag("HOME")
        if (homeFrag != null && !homeFrag.isHidden) {
            supportFragmentManager.beginTransaction().hide(homeFrag).commit()
        }
        viewPager.visibility = View.VISIBLE
    }

    fun switchToTab(index: Int) {
        hideHome()
        viewPager.currentItem = index
    }

    private fun updateHomeStatus(status: String) {
        (supportFragmentManager.findFragmentByTag("HOME") as? InfoFragment)?.updateStatus(status)
    }

    private fun notifyImageFragmentStatus(msg: String) { findImageFragment()?.updateStatus(msg) }
    private fun notifyAudioFragmentStatus(msg: String) { findAudioFragment()?.updateStatus(msg) }

    private fun findImageFragment() = supportFragmentManager.findFragmentByTag("f0") as? ImageFragment
    private fun findAudioFragment() = supportFragmentManager.findFragmentByTag("f1") as? AudioFragment
    private fun findDetectionFragment() = supportFragmentManager.findFragmentByTag("f2") as? DetectionFragment
    private fun findNavigationFragment() = supportFragmentManager.findFragmentByTag("f3") as? NavigationFragment

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        return prefix.indices.all { data[it] == prefix[it] }
    }

    private fun endsWith(data: ByteArray, suffix: ByteArray): Boolean {
        if (data.size < suffix.size) return false
        return suffix.indices.all { data[data.size - suffix.size + it] == suffix[it] }
    }

    private fun hasPermissions(): Boolean {
        return PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    class ViewPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 4
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ImageFragment()
                1 -> AudioFragment()
                2 -> DetectionFragment()
                3 -> NavigationFragment()
                else -> InfoFragment()
            }
        }
    }
}