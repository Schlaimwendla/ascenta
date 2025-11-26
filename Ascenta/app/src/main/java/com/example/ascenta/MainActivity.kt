package com.example.ascenta

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.nio.ByteBuffer
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // --- BLE UUIDs ---
    private val IMAGE_DATA_CHAR = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
    private val IMAGE_CMD_CHAR  = UUID.fromString("80000001-0000-1000-8000-00805f9b34fb")
    private val AUDIO_RX_CHAR   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Phone -> Nicla
    private val AUDIO_TX_CHAR   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Nicla -> Phone
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

    // --- UI Elements ---
    lateinit var viewPager: ViewPager2
    lateinit var bottomNav: BottomNavigationView
    var pendingAction: String? = null

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
            char.value = command.toByteArray()
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
            runOnUiThread { notifyImageFragmentStatus("Receiving Image...") }
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
                // REMOVED: Continuous UI updates to stop byte count spam
            }
        }
    }

    private fun handleAudioData(data: ByteArray) {
        val str = String(data)
        if (str.startsWith("START:")) {
            try {
                audioExpectedSize = str.substringAfter(":").trim().toInt()
                audioBuffer = ByteArrayOutputStream(audioExpectedSize)
                runOnUiThread { notifyAudioFragmentStatus("Receiving Audio...") }
            } catch (e: Exception) {}
        } else if (str.startsWith("END")) {
            val bytes = audioBuffer?.toByteArray()
            if (bytes != null) {
                runOnUiThread {
                    val frag = findAudioFragment()
                    frag?.processFinishedAudio(bytes)
                }
            }
            audioBuffer = null
        } else {
            audioBuffer?.write(data)
        }
    }

    private fun findImageFragment(): ImageFragment? {
        return supportFragmentManager.fragments.firstOrNull { it is ImageFragment } as? ImageFragment
    }

    private fun findAudioFragment(): AudioFragment? {
        return supportFragmentManager.fragments.firstOrNull { it is AudioFragment } as? AudioFragment
    }

    private fun updateHomeStatus(status: String) {
        val frag = supportFragmentManager.fragments.firstOrNull { it is InfoFragment } as? InfoFragment
        frag?.updateStatus(status)
    }

    private fun notifyImageFragmentStatus(msg: String) {
        findImageFragment()?.updateStatus(msg)
    }

    private fun notifyAudioFragmentStatus(msg: String) {
        findAudioFragment()?.updateStatus(msg)
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        return prefix.indices.all { data[it] == prefix[it] }
    }

    private fun endsWith(data: ByteArray, suffix: ByteArray): Boolean {
        if (data.size < suffix.size) return false
        return suffix.indices.all { data[data.size - suffix.size + it] == suffix[it] }
    }

    private fun hasPermissions(): Boolean {
        return PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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