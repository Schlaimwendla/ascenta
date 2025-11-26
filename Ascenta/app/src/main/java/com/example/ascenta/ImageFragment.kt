package com.example.ascenta

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class ImageFragment : Fragment(R.layout.fragment_image) {

    private lateinit var ivStream: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnTakePic: Button
    private lateinit var btnSave: Button

    private var currentBitmap: Bitmap? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivStream = view.findViewById(R.id.iv_stream)
        tvStatus = view.findViewById(R.id.tv_image_status)
        btnTakePic = view.findViewById(R.id.btn_take_picture)
        btnSave = view.findViewById(R.id.btn_save_img)

        btnTakePic.setOnClickListener {
            val act = activity as? MainActivity
            if (act?.isConnected == true) {
                resetUI()
                act.sendCommand("take_picture")
                tvStatus.text = "Requesting Picture..."
            } else {
                Toast.makeText(context, "Not Connected! Go to Home.", Toast.LENGTH_SHORT).show()
            }
        }

        btnSave.setOnClickListener {
            currentBitmap?.let { saveToGallery(it) }
        }
    }

    fun displayImage(bmp: Bitmap?) {
        if (bmp != null) {
            currentBitmap = bmp
            ivStream.setImageBitmap(bmp)
            btnSave.visibility = View.VISIBLE // Show save button ONLY when image exists
            tvStatus.text = "Image Received"
        }
    }

    fun updateStatus(msg: String) {
        if (::tvStatus.isInitialized) tvStatus.text = msg
    }

    private fun resetUI() {
        ivStream.setImageDrawable(null)
        btnSave.visibility = View.GONE
        currentBitmap = null
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val filename = "Ascenta_${System.currentTimeMillis()}.jpg"
        val resolver = requireContext().contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Ascenta")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                Toast.makeText(context, getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }
}