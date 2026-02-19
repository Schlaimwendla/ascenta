package com.example.ascenta

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

class BleService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var callback: BleServiceCallback? = null

    // --- UUIDs ---
    private val IMAGE_DATA_CHAR = UUID.fromString("12345678-1234-5678-1234-567890ABCDE0")
    private val IMAGE_CMD_CHAR  = UUID.fromString("80000001-0000-1000-8000-00805f9b34fb")
    private val AUDIO_RX_CHAR   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val AUDIO_TX_CHAR   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- Config ---
    // Use BuildConfig to keep token safe (requires build.gradle setup)
    private val WIT_AI_TOKEN = BuildConfig.WIT_AI_TOKEN
    private val CHANNEL_ID = "AscentaServiceChannel"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    var isConnected = false
        private set

    // --- Buffers ---
    private val imageBuffer = ByteBuffer.allocate(200 * 1024)
    private var isReceivingImg = false
    private val START_SEQ = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
    private val END_SEQ = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

    private var audioBuffer: ByteArrayOutputStream? = null
    private var audioExpectedSize = 0

    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())

    // --- INTERFACE DEFINITION ---
    interface BleServiceCallback {
        fun onStatusUpdate(status: String)          // Was 'onStatusChange'
        fun onImageReceived(bitmap: Bitmap)
        fun onTranscriptUpdate(text: String)
        fun onDetectionResult(label: String, conf: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Service Running")
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
    }

    // --- PUBLIC METHODS ---

    fun setServiceCallback(cb: BleServiceCallback?) {
        this.callback = cb
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isConnected) {
            callback?.onStatusUpdate("Already Connected")
            return
        }
        callback?.onStatusUpdate("Scanning...")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("Nicla").build())
        scanner?.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            scanner?.stopScan(scanCallback)
            if (!isConnected) callback?.onStatusUpdate("Not Found")
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
        callback?.onStatusUpdate("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(type: String) {
        if (gatt == null) return

        val serviceUuid = if (type == "take_picture") UUID.fromString("80000000-0000-1000-8000-00805f9b34fb") else UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val charUuid = if (type == "take_picture") IMAGE_CMD_CHAR else AUDIO_RX_CHAR

        // Map command string
        val command = when(type) {
            "take_picture" -> "take_picture"
            "RECORD" -> "RECORD"
            "START_DETECT" -> "START_DETECT"
            else -> "RECORD"
        }

        val service = gatt?.getService(serviceUuid)
        val char = service?.getCharacteristic(charUuid)

        if (char != null) {
            char.value = command.toByteArray()
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt?.writeCharacteristic(char)
        }
    }

    fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // --- BLE CALLBACKS ---

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner?.stopScan(this)
            callback?.onStatusUpdate("Found! Connecting...")
            gatt = result.device.connectGatt(this@BleService, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                updateNotification("Connected to Nicla")
                handler.post { callback?.onStatusUpdate("Connected") }
                gatt.requestMtu(500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                updateNotification("Disconnected")
                handler.post { callback?.onStatusUpdate("Disconnected") }
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

            handler.post { callback?.onStatusUpdate("Ready") }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (characteristic.uuid == IMAGE_DATA_CHAR) {
                handleImageData(data)
            } else if (characteristic.uuid == AUDIO_TX_CHAR) {
                handleAudioOrJsonData(data)
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

    // --- DATA PROCESSING ---

    private fun handleImageData(data: ByteArray) {
        if (!isReceivingImg && startsWith(data, START_SEQ)) {
            isReceivingImg = true
            imageBuffer.clear()
            if (data.size > 4) imageBuffer.put(data, 4, data.size - 4)
            broadcastStatus("Receiving Image...")
        } else if (isReceivingImg) {
            if (endsWith(data, END_SEQ)) {
                imageBuffer.put(data, 0, data.size - 4)
                isReceivingImg = false

                val bytes = ByteArray(imageBuffer.position())
                imageBuffer.rewind(); imageBuffer.get(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    handler.post { callback?.onImageReceived(bmp) }
                }
                broadcastStatus("Image Done")
            } else {
                imageBuffer.put(data)
            }
        }
    }

    private fun handleAudioOrJsonData(data: ByteArray) {
        val str = String(data).trim()

        // 1. Object Detection JSON
        if (str.startsWith("{") && str.endsWith("}")) {
            try {
                val json = JSONObject(str)
                if (json.has("label")) {
                    val label = json.getString("label")
                    val conf = json.getString("conf")
                    handler.post { callback?.onDetectionResult(label, conf) }
                    return
                }
            } catch (e: Exception) {}
        }

        // 2. Audio Data
        if (data.size < 64) {
            if (str.startsWith("START:")) {
                try {
                    audioExpectedSize = str.substringAfter(":").trim().toInt()
                    audioBuffer = ByteArrayOutputStream(audioExpectedSize)
                    broadcastStatus("Receiving Audio...")
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

    private fun processFinishedAudio(bytes: ByteArray) {
        // Send to Wit.ai logic
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.wit.ai/speech?v=20230215")
                val conn = url.openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $WIT_AI_TOKEN")
                conn.setRequestProperty("Content-Type", "audio/wav")

                DataOutputStream(conn.outputStream).use { dos ->
                    dos.write(bytes)
                }

                val code = conn.responseCode
                if (code == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    parseWitResponse(resp)
                } else {
                    handler.post { callback?.onTranscriptUpdate("Wit Error: $code") }
                }
            } catch (e: Exception) {
                handler.post { callback?.onTranscriptUpdate("Net Error: ${e.message}") }
            }
        }
    }

    private fun parseWitResponse(raw: String) {
        try {
            val regex = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
            val matches = regex.findAll(raw)
            var bestText = ""
            for (match in matches) {
                val text = match.groupValues[1]
                if (text.length > bestText.length) bestText = text
            }

            val result = if (bestText.isNotEmpty()) bestText else "No text"
            handler.post { callback?.onTranscriptUpdate(result) }

        } catch (e: Exception) { }
    }

    // --- HELPERS ---

    private fun broadcastStatus(msg: String) {
        handler.post { callback?.onStatusUpdate(msg) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ascenta Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ascenta")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(text))
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        return prefix.indices.all { data[it] == prefix[it] }
    }

    private fun endsWith(data: ByteArray, suffix: ByteArray): Boolean {
        if (data.size < suffix.size) return false
        return suffix.indices.all { data[data.size - suffix.size + it] == suffix[it] }
    }
}