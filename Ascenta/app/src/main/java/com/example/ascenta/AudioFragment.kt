package com.example.ascenta

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
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
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
import java.util.Locale
import java.util.UUID

@Suppress("DEPRECATION")
class AudioFragment : Fragment(R.layout.fragment_audio), TextToSpeech.OnInitListener {

    private val WIT_AI_TOKEN = "INSERT_YOUR_WIT_AI_TOKEN_HERE"
    private val NICLA_AUDIO_NAME = "NiclaAudio"

    private val UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX_CHAR = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val TX_CHAR = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private lateinit var tts: TextToSpeech

    private lateinit var btnReplay: Button
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView

    private var fileBuffer: ByteArrayOutputStream? = null
    private var expectedSize = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnReplay = view.findViewById(R.id.btn_replay)
        btnRecord = view.findViewById(R.id.btn_record)
        tvStatus = view.findViewById(R.id.tv_audio_status)
        tvTranscript = view.findViewById(R.id.tv_transcript)

        tts = TextToSpeech(requireContext(), this)
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        btnRecord.setOnClickListener { connectAndRecord() }
        btnReplay.setOnClickListener { playLocalAudio() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.GERMAN
    }

    @SuppressLint("MissingPermission")
    private fun connectAndRecord() {
        tvStatus.text = getString(R.string.status_scan_audio)
        val filters = listOf(ScanFilter.Builder().setDeviceName(NICLA_AUDIO_NAME).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            bluetoothLeScanner?.stopScan(this)
            tvStatus.text = getString(R.string.status_connecting)
            bluetoothGatt = result.device.connectGatt(requireContext(), false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                activity?.runOnUiThread { tvStatus.text = getString(R.string.status_negotiating) }
                gatt.requestMtu(500)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val char = gatt.getService(UART_SERVICE)?.getCharacteristic(TX_CHAR)
            char?.let {
                gatt.setCharacteristicNotification(it, true)
                val desc = it.getDescriptor(CCCD)
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val rx = gatt.getService(UART_SERVICE)?.getCharacteristic(RX_CHAR)
            rx?.let {
                it.value = "RECORD".toByteArray()
                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(it)
                activity?.runOnUiThread { tvStatus.text = getString(R.string.status_recording) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            val str = String(data)

            if (str.startsWith("START:")) {
                expectedSize = str.substringAfter(":").trim().toInt()
                fileBuffer = ByteArrayOutputStream(expectedSize)
                activity?.runOnUiThread { tvStatus.text = getString(R.string.status_downloading) }
            } else if (str.startsWith("END")) {
                val bytes = fileBuffer?.toByteArray()
                if (bytes != null) processAudio(bytes)
                fileBuffer = null
            } else {
                fileBuffer?.write(data)
            }
        }
    }

    private fun processAudio(data: ByteArray) {
        activity?.runOnUiThread { tvStatus.text = getString(R.string.status_transcribing) }
        val file = File(requireContext().cacheDir, "mic.wav")
        FileOutputStream(file).use { it.write(data) }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.wit.ai/speech?v=20230215")
                val conn = url.openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $WIT_AI_TOKEN")
                conn.setRequestProperty("Content-Type", "audio/wav")
                DataOutputStream(conn.outputStream).use { it.write(data) }

                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                parseResponse(resp)
            } catch (e: Exception) {
                activity?.runOnUiThread { tvTranscript.text = getString(R.string.error_format, e.message) }
            }
        }
    }

    private fun parseResponse(json: String) {
        val regex = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
        val match = regex.find(json)
        val text = match?.groupValues?.get(1) ?: getString(R.string.no_speech)

        activity?.runOnUiThread {
            tvTranscript.text = getString(R.string.you_said_format, text)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            tvStatus.text = getString(R.string.status_done)
        }
    }

    private fun playLocalAudio() {
        val file = File(requireContext().cacheDir, "mic.wav")
        if (file.exists()) {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onPause() {
        super.onPause()
        bluetoothGatt?.disconnect()
    }
}