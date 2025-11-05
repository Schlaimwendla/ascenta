package com.example.ascenta

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

private const val TAG = "Ascenta"

private val NICLA_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567890ABCDEF")
private val DATA_CHAR_UUID    = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val START_SEQUENCE = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
private val END_SEQUENCE   = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false

    private var imageBitmap by mutableStateOf<Bitmap?>(null)

    private var imageByteBuffer = ByteBuffer.allocate(100 * 1024)
    private var isReceivingImage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        setContent {
            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Button(onClick = {
                        imageBitmap = null
                        imageByteBuffer.clear()
                        isReceivingImage = false
                        startScanForStreamingDevice()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect and Receive Image Stream (Nicla)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(onClick = {
                        imageBitmap?.let { saveImageToStorage(it) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download Last Image")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Last Received Image:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ImageDisplay(imageBitmap)
                }
            }
        }
    }

    @Composable
    fun ImageDisplay(bitmap: Bitmap?) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Received Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("Waiting for image data...", style = MaterialTheme.typography.bodyLarge)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanForStreamingDevice() {
        if (scanning) return
        scanning = true

        val filter = ScanFilter.Builder().setDeviceName("Nicla").build()

        bluetoothLeScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leScanCallback)
        handler.postDelayed({ bluetoothLeScanner?.stopScan(leScanCallback); scanning = false; Log.d(TAG, "Scan stopped") }, 10000)
        Log.d(TAG, "Scanning for Nicla (Short Name)...")
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == "Nicla") {
                bluetoothLeScanner?.stopScan(this)
                scanning = false
                connectToDevice(result.device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        Log.d(TAG, "Connecting to device...")
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected! Discovering services...")
                gatt.requestMtu(517)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT Services Discovered Successfully.")
            } else {
                Log.e(TAG, "GATT Service Discovery failed with status: $status")
            }

            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            val service = gatt.getService(NICLA_SERVICE_UUID)
            val dataChar = service?.getCharacteristic(DATA_CHAR_UUID)
            dataChar?.let { enableNotifications(gatt, it) }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
                Log.d(TAG, "Enabling notifications for data stream...")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)

            if (descriptor?.uuid == CLIENT_CONFIG_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notifications enabled. Image stream starting...")
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to write descriptor! Status: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU changed to: $mtu")
            } else {
                Log.e(TAG, "MTU change failed. Status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == DATA_CHAR_UUID) {
                val rawData = characteristic.value
                val dataLength = rawData.size

                if (!isReceivingImage) {
                    if (rawData.startsWith(START_SEQUENCE)) {
                        Log.i(TAG, "Image START sequence received.")
                        isReceivingImage = true
                        imageByteBuffer.clear()
                        if (dataLength > START_SEQUENCE.size) {
                            imageByteBuffer.put(rawData, START_SEQUENCE.size, dataLength - START_SEQUENCE.size)
                        }
                        return
                    }
                }

                if (isReceivingImage) {
                    val endCheckIndex = dataLength - END_SEQUENCE.size

                    if (dataLength >= END_SEQUENCE.size && rawData.endsWith(END_SEQUENCE)) {
                        imageByteBuffer.put(rawData, 0, endCheckIndex)
                        isReceivingImage = false
                        Log.i(TAG, "Image END sequence received. Reassembling image...")

                        val finalArray = ByteArray(imageByteBuffer.position())
                        imageByteBuffer.rewind()
                        imageByteBuffer.get(finalArray)

                        val bitmap = BitmapFactory.decodeByteArray(finalArray, 0, finalArray.size)
                        if (bitmap != null) {
                            handler.post { imageBitmap = bitmap }
                        } else {
                            Log.e(TAG, "Failed to decode image")
                        }

                        imageByteBuffer.clear()
                        return
                    }

                    imageByteBuffer.put(rawData)
                }
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
    private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
        if (size < suffix.size) return false
        return suffix.indices.all { this[size - suffix.size + it] == suffix[it] }
    }

    private fun saveImageToStorage(bitmap: Bitmap) {
        try {
            val fileName = "Nicla_Image_${System.currentTimeMillis()}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri).use { fos ->
                    if (fos != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                        Toast.makeText(this, "Image saved to Pictures: $fileName", Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Image saved to MediaStore: $fileName")
                    } else {
                        throw IOException("Failed to get OutputStream.")
                    }
                }
            } else {
                throw IOException("Failed to create new MediaStore entry.")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving image: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error saving image", e)
        }
    }
}