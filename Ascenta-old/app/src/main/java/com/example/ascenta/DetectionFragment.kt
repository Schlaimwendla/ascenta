package com.example.ascenta

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class DetectionFragment : Fragment(R.layout.fragment_detection) {

    private lateinit var ivPreview: ImageView
    private lateinit var tvResult: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var btnDetect: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivPreview = view.findViewById(R.id.iv_detect_preview)
        tvResult = view.findViewById(R.id.tv_detect_result)
        tvConfidence = view.findViewById(R.id.tv_detect_confidence)
        btnDetect = view.findViewById(R.id.btn_start_detect)

        btnDetect.setOnClickListener {
            triggerDetection()
        }
    }

    fun triggerDetection() {
        val act = activity as? MainActivity
        if (act?.isConnected == true) {
            tvResult.text = "Analyzing..."
            tvConfidence.text = "..."
            ivPreview.setImageDrawable(null)
            ivPreview.setBackgroundColor(Color.LTGRAY)
            act.sendCommand("START_DETECT")
        } else {
            Toast.makeText(context, "Not Connected!", Toast.LENGTH_SHORT).show()
        }
    }

    fun displayImage(bmp: Bitmap) {
        activity?.runOnUiThread {
            ivPreview.visibility = View.VISIBLE
            ivPreview.background = null
            ivPreview.setImageBitmap(bmp)
        }
    }

    fun updateResult(label: String, confidence: String) {
        activity?.runOnUiThread {
            tvResult.text = label
            tvConfidence.text = "Confidence: $confidence"
            (activity as? MainActivity)?.speakText("Detected $label")
        }
    }
}