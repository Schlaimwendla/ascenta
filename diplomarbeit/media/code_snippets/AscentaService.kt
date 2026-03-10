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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID

class AscentaService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var callback: ServiceCallback? = null

    private val PROV_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567890ABCDEF")
    private val SSID_CHAR_UUID    = UUID.fromString("12345678-1234-5678-1234-567890ABCDE1")
    private val PASS_CHAR_UUID    = UUID.fromString("12345678-1234-5678-1234-567890ABCDE2")

    private val TCP_PORT = 5005
    private val UDP_DISC_PORT = 5006

    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var connectionJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    var isConnected = false
    private var pendingPass: String? = null
    private var isScanning = false
    private var provisioningInProgress = false

    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    interface ServiceCallback {
        fun onStatusUpdate(status: String)
        fun onImageReceived(bitmap: Bitmap)
        fun onTranscriptUpdate(text: String)
        fun onDetectionResult(label: String, conf: String, dist: String)
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
            tts.language = Locale.GERMANY
        }
    }

    private fun startDiscoveryListener() {
        connectionJob?.cancel()
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(UDP_DISC_PORT).apply {
                    broadcast = true
                    reuseAddress = true
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
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }
    }

    private fun connectTcp(address: InetAddress) {
        scope.launch(Dispatchers.IO) {
            try {
                tcpSocket = Socket(address, TCP_PORT)
                tcpSocket?.soTimeout = 15000
                isConnected = true
                handler.post { callback?.onStatusUpdate("Connected") }

                val input = tcpSocket!!.getInputStream()

                while (isActive && isConnected) {
                    val line = readLineFromStream(input)
                    if (line == null) {
                        disconnectTcp()
                        break
                    }
                    if (line.isEmpty()) continue

                    if (line.startsWith("IMG_START:")) {
                        val size = line.substringAfter(":").toInt()
                        val bytes = readBytesFromStream(input, size)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) handler.post { callback?.onImageReceived(bmp) }
                    } else if (line.startsWith("AUD_START:")) {
                        val size = line.substringAfter(":").toInt()
                        val bytes = readBytesFromStream(input, size)
                        saveAudioForVosk(bytes)
                    } else if (line == "pong") {
                        handler.post { callback?.onStatusUpdate("pong") }
                    } else if (line.startsWith("{")) {
                        try {
                            val json = JSONObject(line)
                            val type = json.optString("type")

                            // Battery Handling Logic Required Here
                            if (type == "battery") {
                                val soc = json.optString("soc")
                                handler.post { callback?.onStatusUpdate("BATTERY_STATUS:$soc") }
                            } else if (type == "battery_warning") {
                                handler.post { callback?.onStatusUpdate("BATTERY_LOW") }
                            } else {
                                // Default detection handling
                                val label = json.optString("label")
                                val conf = json.optString("conf")
                                val dist = json.optString("dist", "0")
                                if(label.isNotEmpty()) handler.post { callback?.onDetectionResult(label, conf, dist) }
                            }
                        } catch(e: Exception){}
                    }
                }
            } catch (e: Exception) {
                disconnectTcp()
            }
        }
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
        if (isScanning) return
        isScanning = true
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
        }, 2000)
    }

    @SuppressLint("MissingPermission")
    fun startProvisioning(ssid: String, pass: String) {
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
                callback?.onStatusUpdate("Scan Error: $errorCode")
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
        if (isScanning) {
            isScanning = false
            try { scanner?.stopScan(cb) } catch(e: Exception){}
        }
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
                        handler.post { callback?.onStatusUpdate("Waiting for Stream...") }
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
        connectionJob?.cancel()
        try { tcpSocket?.close() } catch (e: Exception) {}
        try { udpSocket?.close() } catch (e: Exception) {}
        tts.shutdown()
    }
}