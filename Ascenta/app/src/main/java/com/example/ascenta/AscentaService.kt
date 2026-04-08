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
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.*

class AscentaService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var callback: ServiceCallback? = null

    private val PROV_SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val SSID_CHAR_UUID    = UUID.fromString("12345678-1234-1234-1234-123456789abd")
    private val PASS_CHAR_UUID    = UUID.fromString("12345678-1234-1234-1234-123456789abe")

    private val TCP_PORT = 5005
    private val UDP_DISC_PORT = 5006

    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var connectionJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    var isConnected = false
    private var pendingPass: String? = null
    private var isScanning = false
    private var provisioningInProgress = false

    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface ServiceCallback {
        fun onStatusUpdate(status: String)
        fun onImageReceived(bitmap: Bitmap)
        fun onTranscriptUpdate(text: String)
        fun onDetectionResult(label: String, conf: String, distAndPos: String)
        fun onCollisionWarning(dist: Int, zone: String)
    }

    interface ScanResultCallback {
        fun onFound()
        fun onTimeout()
    }

    inner class LocalBinder : Binder() { fun getService(): AscentaService = this@AscentaService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // ACQUIRE WAKELOCK: Keeps CPU alive when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ascenta::WakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4hr safety timeout

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("AscentaUDP")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        tts = TextToSpeech(this, this)

        startForeground(1, createNotification("Ascenta Active"))
        startDiscoveryListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.GERMAN
        }
    }

    fun forceDisconnect() {
        scope.launch(Dispatchers.IO) {
            isConnected = false
            provisioningInProgress = false
            isScanning = false
            try { tcpSocket?.close() } catch (e: Exception) {}
            tcpSocket = null
            try { udpSocket?.close() } catch (e: Exception) {}
            udpSocket = null
            connectionJob?.cancel()
            handler.post { callback?.onStatusUpdate("Disconnected") }
            startDiscoveryListener()
        }
    }

    private fun startDiscoveryListener() {
        connectionJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(UDP_DISC_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && !isConnected) {
                    try {
                        udpSocket?.receive(packet)
                        val str = String(packet.data, 0, packet.length).trim()
                        if (str == "NICLA_READY") {
                            udpSocket?.close()
                            connectTcp(packet.address)
                            break
                        }
                    } catch (e: Exception) {
                        if (!isActive || isConnected) break
                        delay(500)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun connectTcp(address: InetAddress) {
        scope.launch(Dispatchers.IO) {
            try {
                tcpSocket = Socket(address, TCP_PORT)
                tcpSocket?.soTimeout = 15000
                tcpSocket?.tcpNoDelay = true
                isConnected = true
                handler.post { callback?.onStatusUpdate("Connected") }

                val input = tcpSocket!!.getInputStream()

                while (isActive && isConnected) {
                    val line = readLineFromStream(input) ?: break
                    if (line.isEmpty()) continue

                    processIncomingLine(line, input)
                }
            } catch (e: Exception) {
                disconnectTcp()
            }
        }
    }

    private suspend fun processIncomingLine(line: String, input: InputStream) {
        try {
            when {
                line.startsWith("IMG_START:") -> {
                    val size = line.substringAfter(":").toInt()
                    val bytes = readBytesFromStream(input, size)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) handler.post { callback?.onImageReceived(bmp) }
                }
                line.startsWith("AUD_START:") -> {
                    val size = line.substringAfter(":").toInt()
                    val bytes = readBytesFromStream(input, size)
                    saveAudioForVosk(bytes)
                }
                line.startsWith("{") -> {
                    val json = JSONObject(line)
                    val type = json.optString("type")
                    if (type == "det") {
                        val label = json.optString("label")
                        val dist = json.optString("dist", "0")
                        val pos = json.optString("pos", "straight")
                        handler.post { callback?.onDetectionResult(label, "1.0", "$dist|$pos") }
                    } else if (type == "collision") {
                        val dist = json.optInt("dist", 0)
                        val zone = json.optString("zone", "clear")
                        handler.post { callback?.onCollisionWarning(dist, zone) }
                    } else if (type.contains("bat")) {
                        val soc = json.optString("soc")
                        handler.post { callback?.onStatusUpdate("BATTERY_STATUS:$soc") }
                    }
                }
                line == "pong" -> handler.post { callback?.onStatusUpdate("pong") }
            }
        } catch(e: Exception){}
    }

    private fun readLineFromStream(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readBytesFromStream(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(buffer, read, size - read)
            if (count == -1) break
            read += count
        }
        return buffer
    }

    private fun disconnectTcp() {
        isConnected = false
        handler.post { callback?.onStatusUpdate("Disconnected") }
        try { tcpSocket?.close() } catch (e: Exception) {}
        tcpSocket = null
        startDiscoveryListener()
    }

    fun sendTcpCommand(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                if (isConnected && tcpSocket != null) {
                    val output = tcpSocket!!.getOutputStream()
                    output.write("$cmd\n".toByteArray())
                    output.flush()
                }
            } catch (e: Exception) {
                disconnectTcp()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForNicla(scanCb: ScanResultCallback) {
        if (bluetoothAdapter?.isEnabled != true) {
            handler.post { callback?.onStatusUpdate("Bluetooth deaktiviert") }
            scanCb.onTimeout()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            handler.post { callback?.onStatusUpdate("Standort aktivieren") }
            scanCb.onTimeout()
            return
        }
        // Refresh scanner in case BT was not ready at onCreate
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            handler.post { callback?.onStatusUpdate("Bluetooth nicht bereit") }
            scanCb.onTimeout()
            return
        }
        if (isScanning) { isScanning = false }
        isScanning = true
        handler.post { callback?.onStatusUpdate("Scanning...") }
        val filters = listOf(ScanFilter.Builder().setDeviceName("Nicla").build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val leCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScanSafely(this)
                scanCb.onFound()
            }
            override fun onScanFailed(errorCode: Int) {
                stopScanSafely(this)
                scanCb.onTimeout()
            }
        }
        scanner?.startScan(filters, settings, leCallback)
        handler.postDelayed({
            if (isScanning) {
                stopScanSafely(leCallback)
                scanCb.onTimeout()
            }
        }, 3000)
    }

    @SuppressLint("MissingPermission")
    fun startProvisioning(ssid: String, pass: String) {
        if (bluetoothAdapter?.isEnabled != true) {
            handler.post { callback?.onStatusUpdate("Bluetooth deaktiviert") }
            return
        }
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            handler.post { callback?.onStatusUpdate("Bluetooth nicht bereit") }
            return
        }
        if (isScanning) return
        isScanning = true
        pendingPass = pass
        provisioningInProgress = true
        callback?.onStatusUpdate("Scanning...")
        val filters = listOf(ScanFilter.Builder().setDeviceName("Nicla").build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScanSafely(this)
                connectGatt(result.device, ssid)
            }
            override fun onScanFailed(errorCode: Int) {
                stopScanSafely(this)
                callback?.onStatusUpdate("Scan Error")
                provisioningInProgress = false
            }
        }
        scanner?.startScan(filters, settings, scanCallback)
        handler.postDelayed({
            if (isScanning) {
                stopScanSafely(scanCallback)
                callback?.onStatusUpdate("Not Found")
                provisioningInProgress = false
            }
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafely(cb: ScanCallback) {
        isScanning = false
        try { scanner?.stopScan(cb) } catch(e: Exception){}
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice, ssid: String) {
        callback?.onStatusUpdate("Configuring...")
        device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
                if (n == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (n == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    if (provisioningInProgress) {
                        handler.post { callback?.onStatusUpdate("Configured") }
                        provisioningInProgress = false
                        startDiscoveryListener()
                    }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                val svc = g.getService(PROV_SERVICE_UUID)
                svc?.getCharacteristic(SSID_CHAR_UUID)?.let {
                    it.value = ssid.toByteArray()
                    g.writeCharacteristic(it)
                }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (char.uuid == SSID_CHAR_UUID) {
                        val passChar = g.getService(PROV_SERVICE_UUID)?.getCharacteristic(PASS_CHAR_UUID)
                        if (passChar != null && pendingPass != null) {
                            Thread.sleep(150)
                            passChar.value = pendingPass!!.toByteArray()
                            g.writeCharacteristic(passChar)
                        }
                    } else if (char.uuid == PASS_CHAR_UUID) {
                        handler.post { callback?.onStatusUpdate("Wait for WiFi...") }
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

    fun speakText(text: String) { tts.speak(text, TextToSpeech.QUEUE_ADD, null, null) }
    fun setServiceCallback(cb: ServiceCallback?) { this.callback = cb }

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
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        multicastLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        try { tcpSocket?.close() } catch (e: Exception) {}
        try { udpSocket?.close() } catch (e: Exception) {}
        tts.shutdown()
    }
}