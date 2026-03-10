package com.example.ascenta

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class InfoFragment : Fragment(R.layout.fragment_info) {

    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tv_connection_status)
        btnConnect = view.findViewById(R.id.btn_connect_general)

        val act = activity as? MainActivity

        btnConnect.setOnClickListener {
            // Trigger Smart Connect
            act?.connectToNicla()
        }

        // Auto-update status
        if (act?.isConnected == true) {
            updateStatus("Connected")
        }
    }

    fun updateStatus(msg: String) {
        if (!isAdded || view == null) return
        tvStatus.text = msg
        val act = activity as? MainActivity

        if (act?.isConnected == true) {
            // If connected, this page is usually hidden, but just in case:
            btnConnect.text = "Connected"
            btnConnect.isEnabled = false // Disable button since we are done
        } else {
            btnConnect.text = "Connect"
            btnConnect.isEnabled = true
        }
    }
}