package com.example.ascenta

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "NiclaApp"
    }

    private val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // --- BLE UUIDs ---
    private val IMAGE_DATA_CHAR = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
    private val IMAGE_CMD_CHAR  = UUID.fromString("80000001-0000-1000-8000-00805f9b34fb")

    private val UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val AUDIO_RX_CHAR   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val AUDIO_TX_CHAR   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- BLE State ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    var bluetoothGatt: BluetoothGatt? = null
    var isConnected = false
    private val handler = Handler(Looper.getMainLooper())

    // --- Data Buffers ---
    private val imageBuffer = ByteBuffer.allocate(200 * 1024)
    private var isReceivingImg = false
    private val START_SEQ = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
    private val END_SEQ = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

    private var audioBuffer: ByteArrayOutputStream? = null
    private var audioExpectedSize = 0

    // --- AI / TTS ---
    private val WIT_AI_TOKEN = BuildConfig.WIT_AI_TOKEN
    private lateinit var tts: TextToSpeech

    // --- UI Elements ---
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

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        tts = TextToSpeech(this, this)

        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_navigation)
        val fab = findViewById<FloatingActionButton>(R.id.fab_home)

        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.setCurrentItem(1, false) // Start at Home
        viewPager.offscreenPageLimit = 2

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_image -> viewPager.currentItem = 0
                R.id.nav_audio -> viewPager.currentItem = 2
            }
            true
        }

        fab.setOnClickListener {
            viewPager.currentItem = 1
            bottomNav.menu.findItem(R.id.nav_image).isChecked = false
            bottomNav.menu.findItem(R.id.nav_audio).isChecked = false
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> bottomNav.menu.findItem(R.id.nav_image).isChecked = true
                    2 -> bottomNav.menu.findItem(R.id.nav_audio).isChecked = true
                    1 -> {
                        bottomNav.menu.findItem(R.id.nav_image).isChecked = false
                        bottomNav.menu.findItem(R.id.nav_audio).isChecked = false
                    }
                }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.GERMAN
    }

    // --- BLE LOGIC ---

    @SuppressLint("MissingPermission")
    fun connectToNicla() {
        if (isConnected || scanner == null) return
        updateHomeStatus("Scanning for Nicla...")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("Nicla").build())
        scanner?.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            scanner?.stopScan(scanCallback)
            if (!isConnected) updateHomeStatus("Not Found. Try again.")
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    fun disconnectNicla() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        updateHomeStatus("Disconnected")
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(type: String) {
        if (bluetoothGatt == null) {
            Toast.makeText(this, "Not connected!", Toast.LENGTH_SHORT).show()
            return
        }
        val serviceUuid = if (type == "take_picture") UUID.fromString("80000000-0000-1000-8000-00805f9b34fb") else UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val charUuid = if (type == "take_picture") IMAGE_CMD_CHAR else AUDIO_RX_CHAR
        val command = if (type == "take_picture") "take_picture" else "RECORD"

        val service = bluetoothGatt?.getService(serviceUuid)
        val char = service?.getCharacteristic(charUuid)

        if (char != null) {
            char.setValue(command.toByteArray()) // FIXED: Use setValue
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(char)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner?.stopScan(this)
            updateHomeStatus("Found! Connecting...")
            bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
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

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val imgService = gatt.getService(UUID.fromString("12345678-1234-5678-1234-567890ABCDEF"))
            val imgChar = imgService?.getCharacteristic(IMAGE_DATA_CHAR)
            if (imgChar != null) enableNotification(gatt, imgChar)

            val audioService = gatt.getService(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
            val audioChar = audioService?.getCharacteristic(AUDIO_TX_CHAR)
            if (audioChar != null) enableNotification(gatt, audioChar)

            runOnUiThread { updateHomeStatus("Ready! Select Vision or Voice.") }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // FIXED: Used correct UUID variables and setValue
            val service = gatt.getService(UART_SERVICE_UUID)
            val rxChar = service?.getCharacteristic(AUDIO_RX_CHAR)
            rxChar?.let {
                it.setValue("START".toByteArray(Charsets.UTF_8)) // FIXED
                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(it)
                Log.i(TAG, "Sent START command") // FIXED: TAG is now accessible
            }
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

    @SuppressLint("MissingPermission")
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
                    val frag = findImageFragment()
                    frag?.displayImage(bmp)
                }
            } else {
                imageBuffer.put(data)
            }
        }
    }

    private fun handleAudioData(data: ByteArray) {
        if (data.size < 64) {
            val str = String(data)
            if (str.startsWith("START:")) {
                try {
                    audioExpectedSize = str.substringAfter(":").trim().toInt()
                    audioBuffer = ByteArrayOutputStream(audioExpectedSize)
                    runOnUiThread { notifyAudioFragmentStatus("Receiving Audio...") }
                    return
                } catch (_: Exception) {}
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
        } catch (_: Exception) {}

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.wit.ai/speech?v=20230215")
                val conn = url.openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $WIT_AI_TOKEN")
                conn.setRequestProperty("Content-Type", "audio/raw;encoding=signed-integer;bits=16;rate=16000;endian=little")

                DataOutputStream(conn.outputStream).use { dos ->
                    if (bytes.size > 44) dos.write(bytes, 44, bytes.size - 44)
                    else dos.write(bytes)
                }

                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                parseWitResponse(resp)
            } catch (e: Exception) {
                runOnUiThread { notifyAudioFragmentStatus("Error: ${e.message}") }
            }
        }
    }

    private fun parseWitResponse(rawResponse: String) {
        logLargeString(rawResponse)

        try {
            val regex = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
            val matches = regex.findAll(rawResponse)

            var bestText = ""

            for (match in matches) {
                val text = match.groupValues[1]
                if (text.length > bestText.length) {
                    bestText = text
                }
            }

            if (bestText.isNotEmpty()) {
                runOnUiThread {
                    val frag = findAudioFragment()
                    frag?.updateTranscript(bestText)
                    frag?.updateStatus("Done")
                    speakText(bestText)
                }
            } else {
                runOnUiThread {
                    val frag = findAudioFragment()
                    frag?.updateTranscript("No text found. Raw:\n$rawResponse")
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                val frag = findAudioFragment()
                frag?.updateTranscript("Parse Error. Raw:\n$rawResponse")
            }
        }
    }

    fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun findImageFragment(): ImageFragment? = supportFragmentManager.fragments.firstOrNull { it is ImageFragment } as? ImageFragment
    private fun findAudioFragment(): AudioFragment? = supportFragmentManager.fragments.firstOrNull { it is AudioFragment } as? AudioFragment

    private fun updateHomeStatus(status: String) {
        (supportFragmentManager.fragments.firstOrNull { it is InfoFragment } as? InfoFragment)?.updateStatus(status)
    }

    private fun notifyImageFragmentStatus(msg: String) { findImageFragment()?.updateStatus(msg) }
    private fun notifyAudioFragmentStatus(msg: String) { findAudioFragment()?.updateStatus(msg) }

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

    private fun logLargeString(content: String) {
        if (content.length > 4000) {
            Log.d(TAG, content.substring(0, 4000))
            logLargeString(content.substring(4000))
        } else {
            Log.d(TAG, content)
        }
    }

    class ViewPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ImageFragment()
                1 -> InfoFragment()
                2 -> AudioFragment()
                else -> InfoFragment()
            }
        }
    }
}