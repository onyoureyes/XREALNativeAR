package com.xreal.nativear

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * MemoryQueryActivity: UI for interactive memory recall.
 * Refactored to use Koin and lifecycleScope.
 */
class MemoryQueryActivity : AppCompatActivity() {
    private val TAG = "MemoryQuery"
    
    // Injected dependencies
    private val memoryService: IMemoryService by inject()
    
    // Lazy injection for AIAgentManager (now a singleton)
    private val aiAgentManager: AIAgentManager by inject()
    
    private val aiCallback = object : AIAgentManager.AIAgentCallback {
        
        override fun onCentralMessage(text: String) {
            runOnUiThread {
                logDisplay.append("\n[Status] $text")
            }
        }
        
        override fun onGeminiResponse(reply: String) {
            runOnUiThread {
                logDisplay.append("\n\nGemini: $reply\n")
            }
        }

        override fun onSearchResults(resultsJson: String) {
            runOnUiThread {
                try {
                    val results = org.json.JSONArray(resultsJson)
                    if (results.length() > 0) {
                        logDisplay.append("\n--- Found ${results.length()} memory fragments ---")
                        for (i in 0 until results.length()) {
                            val node = results.getJSONObject(i)
                            val time = node.optString("time", "Unknown")
                            val role = node.optString("role", "LOG")
                            val content = node.optString("content", "")
                            logDisplay.append("\n[$time | $role] $content")
                        }
                        logDisplay.append("\n----------------------------------------\n")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse search results", e)
                }
            }
        }

        override fun showSnapshotFeedback() {}
        override fun onGetLatestBitmap(): android.graphics.Bitmap? = null
        override fun onGetScreenObjects(): String = "[]"
    }

    
    private lateinit var logDisplay: TextView
    private lateinit var queryInput: EditText
    private lateinit var sendBtn: Button
    


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_query)

        // Set callback on the shared AIAgentManager singleton
        aiAgentManager.setCallback(aiCallback)

        logDisplay = findViewById(R.id.memoryLog)
        queryInput = findViewById(R.id.queryInput)
        sendBtn = findViewById(R.id.sendQueryBtn)

        sendBtn.setOnClickListener {
            val query = queryInput.text.toString()
            if (query.isNotBlank()) {
                launchQuery(query)
            }
        }
        
        // Show DB status on start
        lifecycleScope.launch(Dispatchers.IO) {
            val count = memoryService.getMemoryCount()
            runOnUiThread {
                logDisplay.append("[System] Total Memories in DB: $count\n")
                if (count == 0) {
                    logDisplay.append("[System] Database is empty. Capture some memories in AR mode first.\n")
                }
            }
        }
    }

    private fun launchQuery(query: String) {
        logDisplay.append("\nYou: $query")
        queryInput.text.clear()
        
        // Build external context from intent extras
        val lat = intent.getDoubleExtra("extra_lat", 0.0)
        val lon = intent.getDoubleExtra("extra_lon", 0.0)
        val externalContext = if (lat != 0.0 && lon != 0.0) {
            """
                [Context]
                Time: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}
                Location: $lat, $lon (Inherited from AR View)
            """.trimIndent()
        } else null

        // Use AIAgentManager with optional inherited context
        aiAgentManager.processWithGemini(query, externalContext)
    }


}
