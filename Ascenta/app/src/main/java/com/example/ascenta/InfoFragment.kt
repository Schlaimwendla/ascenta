package com.example.ascenta

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class InfoFragment : Fragment(R.layout.fragment_info) {

    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private var currentStatus = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tv_connection_status)
        btnConnect = view.findViewById(R.id.btn_connect_general)

        btnConnect.setOnClickListener {
            val act = activity as? MainActivity

            if (act?.isConnected == true ||
                currentStatus.contains("Scan") ||
                currentStatus.contains("Config") ||
                currentStatus.contains("Wait") ||
                currentStatus.contains("Warte")) {

                act?.resetConnection()
            } else {
                act?.attemptSmartConnection()
            }
        }

        // Set initial German state
        tvStatus.text = "Bereit"
        btnConnect.text = "Verbinden"

        (activity as? MainActivity)?.let {
            if (it.isConnected) updateStatus("Connected")
        }
    }

    fun updateStatus(msg: String) {
        if (!isAdded || view == null) return
        currentStatus = msg
        tvStatus.text = displayText(msg)
        val act = activity as? MainActivity

        when {
            act?.isConnected == true -> {
                btnConnect.text = "Trennung"
                btnConnect.isEnabled = true
            }
            msg.contains("Scan") || msg.contains("Config") || msg.contains("Wait") || msg.contains("Warte") -> {
                btnConnect.text = "Abbrechen"
                btnConnect.isEnabled = true
            }
            else -> {
                btnConnect.text = "Verbinden"
                btnConnect.isEnabled = true
            }
        }
    }

    private fun displayText(msg: String): String = when {
        msg == "Connected" -> "Verbunden"
        msg == "Disconnected" -> "Getrennt"
        msg == "Ready to connect" -> "Bereit"
        msg == "Warte auf Nicla..." -> "Warte auf Nicla..."
        msg.startsWith("Scanning") -> "Scannen..."
        msg == "Configured" -> "Konfiguriert"
        msg.startsWith("Configuring") -> "Konfiguriere..."
        msg.startsWith("Wait") -> "Warte auf WLAN..."
        msg == "Not Found" -> "Nicht gefunden"
        msg == "Scan Error" -> "Scan-Fehler"
        msg == "Bluetooth deaktiviert" -> "Bluetooth deaktiviert"
        msg == "Standort aktivieren" -> "Standort aktivieren"
        msg == "Bluetooth nicht bereit" -> "Bluetooth nicht bereit"
        else -> msg
    }
}