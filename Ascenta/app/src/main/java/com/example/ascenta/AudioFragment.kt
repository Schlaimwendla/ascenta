package com.example.ascenta

import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class AudioFragment : Fragment(R.layout.fragment_audio), TextToSpeech.OnInitListener {

    private val WIT_AI_TOKEN = BuildConfig.WIT_AI_TOKEN
    private lateinit var tts: TextToSpeech
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnRecord = view.findViewById<Button>(R.id.btn_record)
        val btnReplayAudio = view.findViewById<Button>(R.id.btn_replay_audio)
        val btnReplayTts = view.findViewById<Button>(R.id.btn_replay_tts)
        tvStatus = view.findViewById(R.id.tv_audio_status)
        tvTranscript = view.findViewById(R.id.tv_transcript)

        tts = TextToSpeech(requireContext(), this)

        // 1. Record -> Send Command via MainActivity
        btnRecord.setOnClickListener {
            (activity as? MainActivity)?.sendCommand("RECORD")
        }

        btnReplayAudio.setOnClickListener { playLocalAudio() }

        // Replay last TTS
        btnReplayTts.setOnClickListener {
            val text = tvTranscript.text.toString().substringAfter("You said: ")
            if (text.isNotEmpty()) speakText(text)
        }
    }

    // Called by MainActivity when audio download finishes
    fun processFinishedAudio(data: ByteArray) {
        updateStatus("Transcribing...")
        // Save locally
        val file = File(requireContext().cacheDir, "mic.wav")
        FileOutputStream(file).use { it.write(data) }

        // Send to Wit.ai
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.wit.ai/speech?v=20230215")
                val conn = url.openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $WIT_AI_TOKEN")
                conn.setRequestProperty("Content-Type", "audio/wav")
                DataOutputStream(conn.outputStream).use { it.write(data) }

                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                parseResponse(resp)
            } catch (e: Exception) {
                activity?.runOnUiThread { tvTranscript.text = "Error: ${e.message}" }
            }
        }
    }

    fun updateStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun parseResponse(json: String) {
        // Simple regex parser
        val regex = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
        val match = regex.find(json)
        val text = match?.groupValues?.get(1) ?: "No speech detected"

        activity?.runOnUiThread {
            tvTranscript.text = "You said: $text"
            updateStatus("Done")
            speakText(text)
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.GERMAN
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}