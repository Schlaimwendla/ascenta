package com.example.ascenta

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.UUID

class AscentaService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var callback: ServiceCallback? = null

    private val PROV_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567890ABCDEF")
    private val SSID_CHAR_UUID    = UUID.fromString("12345678-1234-5678-1234-567890ABCDE1")
    private val PASS_CHAR_UUID    = UUID.fromString("12345678-1234-5678-1234-567890ABCDE2")

    private val UDP_PORT = 5005
    private var udpSocket: DatagramSocket? = null
    private var udpListenerJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    var isConnected = false
    private var pendingPass: String? = null
    private var isScanning = false
    private var provisioningInProgress = false

    private val imageBuffer = ByteArrayOutputStream()
    private var isReceivingImg = false
    private val audioBuffer = ByteArrayOutputStream()
    private var isReceivingAudio = false
    private var audioExpectedSize = 0

    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    interface ServiceCallback {
        fun onStatusUpdate(status: String)
        fun onImageReceived(bitmap: Bitmap)
        fun onTranscriptUpdate(text: String)
        fun onDetectionResult(label: String, conf: String)
    }

    inner class LocalBinder : Binder() { fun getService(): AscentaService = this@AscentaService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("AscentaUDP")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        tts = TextToSpeech(this, this)

        startForeground(1, createNotification("Ascenta Active"))
        startUdpListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
    }

    private fun startUdpListener() {
        if (udpSocket != null) return
        udpListenerJob = scope.launch {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
                udpSocket?.broadcast = true
                udpSocket?.reuseAddress = true

                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        udpSocket?.receive(packet)
                        val data = packet.data.copyOf(packet.length)
                        processUdpData(data)
                    } catch (e: Exception) {
                        if (isActive) e.printStackTrace()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun processUdpData(data: ByteArray) {
        if (!isConnected) {
            isConnected = true
            handler.post { callback?.onStatusUpdate("Connected (Via WiFi)") }
        }

        val str = String(data).trim()

        if (str == "IMG_START") {
            isReceivingImg = true; imageBuffer.reset(); broadcastStatus("Rx Image...")
        } else if (str == "IMG_END") {
            isReceivingImg = false
            val bytes = imageBuffer.toByteArray()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) handler.post { callback?.onImageReceived(bmp) }
            broadcastStatus("Image Complete")
        } else if (isReceivingImg) {
            imageBuffer.write(data)
        } else if (str.startsWith("AUD_START:")) {
            try { audioExpectedSize = str.substringAfter(":").toInt(); isReceivingAudio = true; audioBuffer.reset(); broadcastStatus("Receiving Audio...") } catch(e: Exception){}
        } else if (str == "AUD_END") {
            isReceivingAudio = false
            saveAudioForVosk(audioBuffer.toByteArray())
            broadcastStatus("Processing Voice...")
        } else if (isReceivingAudio) {
            audioBuffer.write(data)
        } else if (str.startsWith("{")) {
            try {
                val json = org.json.JSONObject(str)
                val label = json.optString("label")
                val conf = json.optString("conf")
                if(label.isNotEmpty()) handler.post { callback?.onDetectionResult(label, conf) }
            } catch(e: Exception){}
        }
    }

    fun sendUdpCommand(cmd: String) {
        scope.launch {
            try {
                val data = cmd.toByteArray()
                // Use broadcast address calculation if available, else standard broadcast
                val broadcastAddress = getBroadcastAddress() ?: InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, broadcastAddress, UDP_PORT)
                udpSocket?.send(packet)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun getBroadcastAddress(): InetAddress? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp == null) return null
            val broadcast = (dhcp.ipAddress and dhcp.netmask) or (dhcp.netmask.inv())
            val quads = ByteArray(4)
            for (k in 0..3) quads[k] = ((broadcast shr k * 8) and 0xFF).toByte()
            InetAddress.getByAddress(quads)
        } catch (e: Exception) { null }
    }

    @SuppressLint("MissingPermission")
    fun startProvisioning(ssid: String, pass: String) {
        if (isScanning) return
        isScanning = true
        pendingPass = pass
        provisioningInProgress = true

        callback?.onStatusUpdate("Scanning for Nicla...")

        val filters = listOf(ScanFilter.Builder().setDeviceName("Nicla").build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScanSafely(this)
                connectGatt(result.device, ssid)
            }
            override fun onScanFailed(errorCode: Int) {
                stopScanSafely(this)
                callback?.onStatusUpdate("BLE Scan Failed: $errorCode")
                provisioningInProgress = false
            }
        }

        scanner?.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            if (isScanning) {
                stopScanSafely(scanCallback)
                callback?.onStatusUpdate("Nicla Not Found. Is it in BLE mode?")
                provisioningInProgress = false
            }
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafely(cb: ScanCallback) {
        if (isScanning) {
            isScanning = false
            try { scanner?.stopScan(cb) } catch(e: Exception){}
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice, ssid: String) {
        callback?.onStatusUpdate("Connecting BLE...")
        device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
                if (n == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (n == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    if (provisioningInProgress) {
                        handler.post { callback?.onStatusUpdate("Credentials Sent!") }
                        provisioningInProgress = false
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                val svc = g.getService(PROV_SERVICE_UUID)
                if (svc != null) {
                    val ssidChar = svc.getCharacteristic(SSID_CHAR_UUID)
                    if (ssidChar != null) {
                        ssidChar.value = ssid.toByteArray()
                        g.writeCharacteristic(ssidChar)
                    }
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (char.uuid == SSID_CHAR_UUID) {
                        val svc = g.getService(PROV_SERVICE_UUID)
                        val passChar = svc.getCharacteristic(PASS_CHAR_UUID)
                        if (passChar != null && pendingPass != null) {
                            Thread.sleep(100)
                            passChar.value = pendingPass!!.toByteArray()
                            g.writeCharacteristic(passChar)
                        }
                    } else if (char.uuid == PASS_CHAR_UUID) {
                        isConnected = true // Immediately flag as connected
                        handler.post { callback?.onStatusUpdate("Credentials Sent! Connecting WiFi...") }
                        g.disconnect()
                    }
                }
            }
        })
    }

    private fun saveAudioForVosk(bytes: ByteArray) {
        try {
            val file = File(cacheDir, "mic.wav")
            FileOutputStream(file).use { it.write(bytes) }
            handler.post { callback?.onTranscriptUpdate("Audio Ready") }
        } catch(e: Exception){}
    }

    fun speakText(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
    fun setServiceCallback(cb: ServiceCallback?) { this.callback = cb }
    private fun broadcastStatus(msg: String) { handler.post { callback?.onStatusUpdate(msg) } }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("AscentaChannel", "Ascenta", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "AscentaChannel")
            .setContentTitle("Ascenta")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        multicastLock?.release()
        udpListenerJob?.cancel()
        udpSocket?.close()
        tts.shutdown()
    }
}