package com.example.ascenta

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.nio.ByteBuffer
import java.util.UUID

@Suppress("DEPRECATION")
class ImageFragment : Fragment(R.layout.fragment_image) {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    private lateinit var ivStream: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button

    private val NICLA_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567890ABCDEF")
    private val DATA_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val START_SEQ = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
    private val END_SEQ = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

    private val imageBuffer = ByteBuffer.allocate(100 * 1024)
    private var isReceiving = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivStream = view.findViewById(R.id.iv_stream)
        tvStatus = view.findViewById(R.id.tv_image_status)
        btnConnect = view.findViewById(R.id.btn_connect_image)

        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        btnConnect.setOnClickListener { startScan() }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        scanning = true
        tvStatus.text = getString(R.string.status_scanning)

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("Nicla").build())

        bluetoothLeScanner?.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                bluetoothLeScanner?.stopScan(scanCallback)
                scanning = false
                if (bluetoothGatt == null) tvStatus.text = getString(R.string.status_not_found)
            }
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            bluetoothLeScanner?.stopScan(this)
            scanning = false
            tvStatus.text = getString(R.string.status_found_connecting)
            bluetoothGatt = result.device.connectGatt(requireContext(), false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                activity?.runOnUiThread { tvStatus.text = getString(R.string.status_connected_stream) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                activity?.runOnUiThread { tvStatus.text = getString(R.string.status_disconnected) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val char = gatt.getService(NICLA_SERVICE_UUID)?.getCharacteristic(DATA_CHAR_UUID)
            char?.let {
                gatt.setCharacteristicNotification(it, true)
                val desc = it.getDescriptor(CLIENT_CONFIG_UUID)
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value

            if (!isReceiving && startsWith(data, START_SEQ)) {
                isReceiving = true
                imageBuffer.clear()
                if (data.size > 4) imageBuffer.put(data, 4, data.size - 4)
            } else if (isReceiving) {
                if (endsWith(data, END_SEQ)) {
                    imageBuffer.put(data, 0, data.size - 4)
                    isReceiving = false
                    decodeImage()
                } else {
                    imageBuffer.put(data)
                }
            }
        }
    }

    private fun decodeImage() {
        val bytes = ByteArray(imageBuffer.position())
        imageBuffer.rewind()
        imageBuffer.get(bytes)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        activity?.runOnUiThread {
            if (bmp != null) {
                ivStream.setImageBitmap(bmp)
                tvStatus.text = getString(R.string.status_live_format, bytes.size)
            }
        }
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        return prefix.indices.all { data[it] == prefix[it] }
    }

    private fun endsWith(data: ByteArray, suffix: ByteArray): Boolean {
        if (data.size < suffix.size) return false
        return suffix.indices.all { data[data.size - suffix.size + it] == suffix[it] }
    }

    @SuppressLint("MissingPermission")
    override fun onPause() {
        super.onPause()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }
}