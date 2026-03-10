package com.example.ascenta

import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File

class AudioFragment : Fragment(R.layout.fragment_audio) {

    private lateinit var tvTranscript: TextView
    private lateinit var btnReplayAudio: Button
    private lateinit var btnReplayTts: Button
    private lateinit var btnRecord: Button

    private var lastSpokenText = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnReplayAudio = view.findViewById(R.id.btn_replay_audio)
        btnReplayTts   = view.findViewById(R.id.btn_replay_tts)
        btnRecord      = view.findViewById(R.id.btn_record)
        tvTranscript   = view.findViewById(R.id.tv_transcript)

        btnRecord.setOnClickListener {
            Toast.makeText(context, "Aufnahme läuft...", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.sendCommand("RECORD")
        }
        btnReplayAudio.setOnClickListener { playLocalAudio() }
        // FIX: route TTS through MainActivity → AscentaService (single engine, no conflicts)
        btnReplayTts.setOnClickListener {
            if (lastSpokenText.isNotEmpty()) (activity as? MainActivity)?.speakText(lastSpokenText)
        }
    }

    fun updateTranscript(text: String) {
        lastSpokenText = text
        activity?.runOnUiThread { if (isAdded) tvTranscript.text = text }
    }

    private fun playLocalAudio() {
        val file = File(requireContext().cacheDir, "mic.wav")
        if (file.exists() && file.length() > 44) {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
                mp.setOnCompletionListener { it.release() }
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            Toast.makeText(context, "Keine Aufnahme", Toast.LENGTH_SHORT).show()
        }
    }
}