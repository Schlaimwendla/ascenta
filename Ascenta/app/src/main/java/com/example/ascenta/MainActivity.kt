package com.example.ascenta

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567812345678")
    private val CHAR_UUID = UUID.fromString("87654321-8765-4321-8765-432187654321")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val META_PREFIX = "META".toByteArray()

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val chunks = ConcurrentHashMap<Int, ByteArray>()
    private var totalChunks = -1
    private var totalSize = -1

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        scanner = mgr.adapter.bluetoothLeScanner
        requestPerms()

        setContent {
            BLEScreen()
        }
    }

    @Composable
    fun BLEScreen() {
        var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
        var status by remember { mutableStateOf("Idle") }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Nicla BLE Receiver") }) }
        ) { pad ->
            Column(
                modifier = Modifier
                    .padding(pad)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = {
                    status = "Scanning..."
                    startScan { status = it }
                }) {
                    Text("Connect & Receive")
                }

                if (imageBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes!!.size)
                    if (bmp != null)
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null)
                }

                Button(
                    enabled = imageBytes != null,
                    onClick = {
                        imageBytes?.let { saveImage(it) }
                        status = "Saved to gallery"
                    }) {
                    Text("Download Image")
                }

                Text(status)
            }
        }

        // callbacks for Compose UI updates
        imageCallback = {
            imageBytes = it
            status = "Image received (${it.size} bytes)"
        }
        statusCallback = { status = it }
    }

    private fun requestPerms() {
        val p = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        permLauncher.launch(p)
    }

    private fun startScan(onStatus: (String) -> Unit) {
        val perm = Manifest.permission.BLUETOOTH_SCAN
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            onStatus("No scan permission")
            requestPerms()
            return
        }
        if (scanning) return
        scanning = true
        onStatus("Scanning...")
        scanner?.startScan(null, ScanSettings.Builder().build(), scanCb)
        Handler(Looper.getMainLooper()).postDelayed({
            if (scanning) {
                scanning = false
                scanner?.stopScan(scanCb)
                onStatus("Device not found")
            }
        }, 8000)
    }

    private val scanCb = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        override fun onScanResult(type: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            scanner?.stopScan(this)
            connect(result.device)
        }
    }

    private fun connect(device: BluetoothDevice) {
        val perm = Manifest.permission.BLUETOOTH_CONNECT
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            statusCallback("No connect permission")
            requestPerms()
            return
        }
        statusCallback("Connecting ${device.name ?: device.address}")
        gatt = device.connectGatt(this, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val perm = Manifest.permission.BLUETOOTH_CONNECT
                if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED)
                    g.discoverServices()
                else statusCallback("No discover permission")
            } else statusCallback("Disconnected")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE_UUID) ?: return
            val c = s.getCharacteristic(CHAR_UUID) ?: return
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD_UUID)
            d?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (d != null && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
                g.writeDescriptor(d)
            }
            statusCallback("Ready for data")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handlePacket(c.value)
        }
    }

    private fun handlePacket(data: ByteArray) {
        if (data.size >= 4 && data.sliceArray(0..3).contentEquals(META_PREFIX)) {
            val bb = ByteBuffer.wrap(data, 4, data.size - 4).order(ByteOrder.LITTLE_ENDIAN)
            totalChunks = bb.int
            totalSize = bb.int
            chunks.clear()
            statusCallback("META received")
            return
        }
        if (data.size < 4) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val seq = bb.int
        val payload = data.copyOfRange(4, data.size)
        chunks[seq] = payload
        if (totalChunks > 0 && chunks.size >= totalChunks) reassemble()
    }

    private fun reassemble() {
        val out = ByteArray(totalSize)
        var pos = 0
        for (i in 0 until totalChunks) {
            val p = chunks[i] ?: continue
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        chunks.clear()
        imageCallback(out)
    }

    private fun saveImage(bytes: ByteArray) {
        val name = "nicla_${System.currentTimeMillis()}.jpg"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Nicla")
        }
        val uri: Uri? =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let {
            val out: OutputStream? = contentResolver.openOutputStream(it)
            out?.use { o -> o.write(bytes) }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        gatt?.close()
    }

    // Compose UI callbacks
    private var imageCallback: (ByteArray) -> Unit = {}
    private var statusCallback: (String) -> Unit = {}
}
