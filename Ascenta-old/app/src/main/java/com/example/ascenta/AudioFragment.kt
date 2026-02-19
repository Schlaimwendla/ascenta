package com.example.ascenta

import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import java.util.Locale

class AudioFragment : Fragment(R.layout.fragment_audio), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var btnReplayAudio: Button
    private lateinit var btnReplayTts: Button
    private lateinit var btnRecord: Button

    private var lastSpokenText = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnReplayAudio = view.findViewById(R.id.btn_replay_audio)
        btnReplayTts = view.findViewById(R.id.btn_replay_tts)
        btnRecord = view.findViewById(R.id.btn_record)
        tvStatus = view.findViewById(R.id.tv_audio_status)
        tvTranscript = view.findViewById(R.id.tv_transcript)

        tts = TextToSpeech(requireContext(), this)

        btnRecord.setOnClickListener {
            (activity as? MainActivity)?.sendCommand("RECORD")
        }
        btnReplayAudio.setOnClickListener { playLocalAudio() }
        btnReplayTts.setOnClickListener {
            if (lastSpokenText.isNotEmpty()) speakText(lastSpokenText)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.GERMAN
    }

    fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            if (isAdded) tvStatus.text = msg
        }
    }

    fun updateTranscript(text: String) {
        lastSpokenText = text
        activity?.runOnUiThread {
            if (isAdded) tvTranscript.text = text
        }
    }

    private fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun playLocalAudio() {
        val file = File(requireContext().cacheDir, "mic.wav")
        if (file.exists()) {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}