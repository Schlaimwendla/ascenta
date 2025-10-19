package com.example.ascenta

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.HorizontalDivider


private const val TAG = "BLE_APP"

private val CAMERA_SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
private val IMAGE_CHAR_UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val SCAN_PERIOD_MS: Long = 10000

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val foundDevices = mutableStateListOf<BluetoothDevice>()
    private var bluetoothGatt: BluetoothGatt? = null
    private var imageBytes = mutableStateOf<ByteArray?>(null)

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            if (!granted) Log.e(TAG, "Required BLE permissions not granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        requestPermissionsIfNeeded()

        setContent {
            MaterialTheme {
                BLEAppUI(
                    devices = foundDevices,
                    onDeviceSelected = { device -> connectDevice(device) },
                    imageBytes = imageBytes.value
                )
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissions.isNotEmpty()) requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!foundDevices.contains(device)) {
                foundDevices.add(device)
                safeLog("Found device: ${device.name ?: "Unknown"} (${device.address})")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            safeLog("Scan failed: $errorCode")
        }
    }

    fun scanLeDevice() {
        if (bluetoothLeScanner == null) return
        if (!scanning) {
            handler.postDelayed({
                scanning = false
                safeStopScan()
                safeLog("Scan stopped after $SCAN_PERIOD_MS ms")
            }, SCAN_PERIOD_MS)
            scanning = true
            safeStartScan()
            safeLog("Scan started")
        } else {
            scanning = false
            safeStopScan()
            safeLog("Scan manually stopped")
        }
    }

    private fun safeStartScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner?.startScan(leScanCallback)
        }
    }

    private fun safeStopScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner?.stopScan(leScanCallback)
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        safeLog("Connecting to ${device.name ?: "Unknown"}")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                safeLog("Connected! Discovering services...")
                safeDiscoverServices(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                safeLog("Disconnected")
            }
        }

        private fun safeDiscoverServices(gatt: BluetoothGatt) {
            try {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices()
                }
            } catch (e: SecurityException) {
                safeLog("BLE permission denied: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(CAMERA_SERVICE_UUID)
            val char = service?.getCharacteristic(IMAGE_CHAR_UUID)
            if (char != null) safeEnableNotifications(gatt, char)
        }

        private fun safeEnableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                }
            } catch (e: SecurityException) {
                safeLog("Cannot enable notifications: ${e.message}")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == IMAGE_CHAR_UUID) {
                val chunk = characteristic.value
                if (chunk.size == 1 && chunk[0] == 0xFF.toByte()) {
                    imageBytes.value = byteArrayOf()
                    safeLog("Start of Image")
                } else if (chunk.size == 1 && chunk[0] == 0xFE.toByte()) {
                    safeLog("End of Image, size: ${imageBytes.value?.size}")
                } else {
                    val current = imageBytes.value ?: byteArrayOf()
                    imageBytes.value = current + chunk
                }
            }
        }
    }

    private fun safeLog(msg: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, msg)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BLEAppUI(
    devices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    imageBytes: ByteArray?
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { (context as? MainActivity)?.scanLeDevice() },
            modifier = Modifier.fillMaxWidth()) {
            Text("Scan BLE Devices")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSelected(device) }
                        .padding(8.dp)
                ) {
                    Text(text = device.name ?: "Unknown Device")
                    Text(text = device.address)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        imageBytes?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            bitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "Received Image",
                    modifier = Modifier.fillMaxWidth().height(300.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { saveImageToDownloads(it) }) {
                    Text("Download Image")
                }
            }
        }
    }
}

private fun saveImageToDownloads(bitmap: Bitmap) {
    try {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, "openmv_image_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        Log.d(TAG, "Image saved to ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save image: ${e.message}")
    }
}
