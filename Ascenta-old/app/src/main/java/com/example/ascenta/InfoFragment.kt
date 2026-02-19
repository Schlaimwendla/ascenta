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
            if (act?.isConnected == true) {
                act.disconnectNicla()
            } else {
                act?.connectToNicla()
            }
        }

        // Restore state if already connected when returning to this screen
        if (act?.isConnected == true) {
            updateStatus("Connected")
        }
    }

    fun updateStatus(msg: String) {
        // Ensure Fragment is attached before updating UI
        if (!isAdded || view == null) return

        tvStatus.text = "Status: $msg"

        val act = activity as? MainActivity
        // FIX: Check the boolean state from MainActivity instead of the message text
        if (act?.isConnected == true) {
            btnConnect.text = getString(R.string.menu_disconnect)
        } else {
            btnConnect.text = getString(R.string.menu_connect_general)
        }
    }
}