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
    private lateinit var btnTiny: Button
    private lateinit var btnBase: Button
    private lateinit var btnSmall: Button
    
    private var isListening = false
    private var currentModel = ModelType.TINY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whisper_standalone)

        tvResult = findViewById(R.id.tvResult)
        tvVadStatus = findViewById(R.id.tvVadStatus)
        btnListen = findViewById(R.id.btnListen)
        btnTiny = findViewById(R.id.btnTiny)
        btnBase = findViewById(R.id.btnBase)
        btnSmall = findViewById(R.id.btnSmall)

        initEngine(ModelType.TINY)

        btnTiny.setOnClickListener { switchModel(ModelType.TINY) }
        btnBase.setOnClickListener { switchModel(ModelType.BASE) }
        btnSmall.setOnClickListener { switchModel(ModelType.SMALL) }

        btnListen.setOnClickListener {
            if (checkPermissions()) {
                toggleListening()
            } else {
                requestPermissions()
            }
        }
    }

    private fun initEngine(modelType: ModelType) {
        currentModel = modelType
        
        // LiteRT (standalone) — CPU 4 threads, GPU/NPU는 앱 모듈에서 Orchestrator가 관리
        val options = org.tensorflow.lite.Interpreter.Options().apply {
            setNumThreads(4)
        }
        
        whisperEngine.initialize(options, modelType)
        tvResult.text = "Model Loaded: $modelType (Auto-Acceleration)\n"
        updateButtonStyles()
    }

    private fun switchModel(modelType: ModelType) {
        if (isListening) toggleListening()
        initEngine(modelType)
    }

    private fun updateButtonStyles() {
        btnTiny.alpha = if (currentModel == ModelType.TINY) 1.0f else 0.5f
        btnBase.alpha = if (currentModel == ModelType.BASE) 1.0f else 0.5f
        btnSmall.alpha = if (currentModel == ModelType.SMALL) 1.0f else 0.5f
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
