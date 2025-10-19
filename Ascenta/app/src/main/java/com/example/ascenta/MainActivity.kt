package com.example.ascenta

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.nio.ByteBuffer
import java.util.UUID

private const val TAG = "BLE_STREAM_APP"

// --- UUIDs (Must match Nicla) ---
private val NICLA_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567890ABCDEF")
private val DATA_CHAR_UUID    = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// --- BINARY IMAGE SIGNALING (MUST MATCH MICROPYTHON) ---
private val START_SEQUENCE = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
private val END_SEQUENCE   = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false

    private var imageBitmap by mutableStateOf<android.graphics.Bitmap?>(null)

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
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Last Received Image:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // --- IMAGE VIEWER UI ---
                    ImageDisplay(imageBitmap)
                }
            }
        }
    }

    @Composable
    fun ImageDisplay(bitmap: android.graphics.Bitmap?) {
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
        // Filter for "Nicla"
        val filter = ScanFilter.Builder().setDeviceName("Nicla").build()
        bluetoothLeScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leScanCallback)
        handler.postDelayed({ bluetoothLeScanner?.stopScan(leScanCallback); scanning = false; Log.d(TAG, "Scan stopped") }, 10000)
        Log.d(TAG, "Scanning for Nicla...")
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

    // --- GATT Callbacks ---
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected! Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // --- LOG ADDED ---
                Log.d(TAG, "GATT Services Discovered Successfully.")
            } else {
                Log.e(TAG, "GATT Service Discovery failed with status: $status")
            }

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

        // --- CORE LOGIC: IMAGE REASSEMBLY ---
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == DATA_CHAR_UUID) {
                val rawData = characteristic.value
                val dataLength = rawData.size

                // 1. Check for START sequence
                if (!isReceivingImage) {
                    if (rawData.startsWith(START_SEQUENCE)) {
                        Log.i(TAG, "Image START sequence received.")
                        isReceivingImage = true
                        imageByteBuffer.clear()
                        // Append remaining data after the 4-byte START sequence
                        if (dataLength > START_SEQUENCE.size) {
                            imageByteBuffer.put(rawData, START_SEQUENCE.size, dataLength - START_SEQUENCE.size)
                        }
                        return
                    }
                }

                // 2. Process data chunks if receiving
                if (isReceivingImage) {
                    val endCheckIndex = dataLength - END_SEQUENCE.size

                    // Check for END sequence
                    if (dataLength >= END_SEQUENCE.size && rawData.endsWith(END_SEQUENCE)) {

                        // Put data up to the END sequence
                        imageByteBuffer.put(rawData, 0, endCheckIndex)

                        isReceivingImage = false
                        Log.i(TAG, "Image END sequence received. Reassembling image...")

                        // 3. Decode JPEG
                        val finalArray = ByteArray(imageByteBuffer.position())
                        imageByteBuffer.rewind()
                        imageByteBuffer.get(finalArray)

                        val bitmap = BitmapFactory.decodeByteArray(finalArray, 0, finalArray.size)

                        if (bitmap != null) {
                            handler.post {
                                imageBitmap = bitmap
                                Log.i(TAG, "Image successfully decoded and displayed! Size: ${finalArray.size} bytes.")
                            }
                        } else {
                            Log.e(TAG, "Failed to decode received image data (invalid JPEG). Buffer Size: ${finalArray.size}")
                        }

                        imageByteBuffer.clear()
                        return
                    }

                    // No START/END, just append chunk
                    imageByteBuffer.put(rawData)
                }
            }
        }
    }

    // Helper extension function to check for byte array prefix/suffix
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
    private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
        if (size < suffix.size) return false
        return suffix.indices.all { this[size - suffix.size + it] == suffix[it] }
    }
}