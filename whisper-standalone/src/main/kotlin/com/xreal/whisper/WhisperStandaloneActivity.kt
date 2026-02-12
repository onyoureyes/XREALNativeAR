package com.xreal.whisper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class WhisperStandaloneActivity : AppCompatActivity() {
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var tvResult: TextView
    private lateinit var tvVadStatus: TextView
    private lateinit var btnListen: Button
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whisper_standalone)

        tvResult = findViewById(R.id.tvResult)
        tvVadStatus = findViewById(R.id.tvVadStatus)
        btnListen = findViewById(R.id.btnListen)

        whisperEngine = WhisperEngine(this)
        whisperEngine.setOnResultListener { text ->
            val currentText = tvResult.text.toString()
            tvResult.text = "Detected: $text\n\n$currentText"
        }

        whisperEngine.setOnVadStatusListener { isSpeaking ->
            runOnUiThread {
                tvVadStatus.text = if (isSpeaking) "VAD Status: SPEECH DETECTED 🎤" else "VAD Status: Listening/Silence..."
                tvVadStatus.setTextColor(if (isSpeaking) android.graphics.Color.RED else android.graphics.Color.GRAY)
            }
        }

        btnListen.setOnClickListener {
            if (checkPermissions()) {
                toggleListening()
            } else {
                requestPermissions()
            }
        }
    }

    private fun toggleListening() {
        if (isListening) {
            whisperEngine.stopListening()
            btnListen.text = "Start Listening"
            tvVadStatus.text = "VAD Status: Idle"
            isListening = false
        } else {
            whisperEngine.startListening()
            btnListen.text = "Stop Listening"
            tvResult.text = "Listening with VAD + Padding (0.3s/0.5s)..."
            isListening = true
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperEngine.close()
    }
}
