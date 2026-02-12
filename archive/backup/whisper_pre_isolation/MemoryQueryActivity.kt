package com.xreal.nativear

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.genai.types.*

class MemoryQueryActivity : AppCompatActivity() {
    private lateinit var geminiClient: GeminiClient
    private lateinit var toolHandler: MemoryToolHandler
    private lateinit var logDisplay: TextView
    private val activityScope = CoroutineScope(Dispatchers.Main)
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_query)

        val apiKey = intent.getStringExtra("API_KEY") ?: ""
        geminiClient = GeminiClient(apiKey)
        toolHandler = MemoryToolHandler(UnifiedMemoryDatabase(this))
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)

        logDisplay = findViewById(R.id.memoryLog)
        val queryInput = findViewById<EditText>(R.id.queryInput)
        val sendBtn = findViewById<Button>(R.id.sendQueryBtn)

        sendBtn.setOnClickListener {
            val query = queryInput.text.toString()
            if (query.isNotBlank()) {
                if (androidx.core.app.ActivityCompat.checkSelfPermission(this@MemoryQueryActivity, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     fetchLocationAndQuery(query)
                } else {
                     launchQuery(query, " (Location Permission Missing)")
                }
            }
        }
        
        // Debug: Show total count on start
        activityScope.launch(Dispatchers.IO) {
            val count = toolHandler.getDatabaseCount()
            launch(Dispatchers.Main) {
                logDisplay.append("[System] Total Memories in DB: $count\n")
                android.widget.Toast.makeText(this@MemoryQueryActivity, "DB Count: $count", android.widget.Toast.LENGTH_LONG).show()
                if (count == 0) {
                     logDisplay.append("[System] Warning: Database is empty! Capture some memories in the main AR view first.\n")
                }
            }
        }
    }

    private fun fetchLocationAndQuery(query: String) {
        // 1. Try Balanced Power (WiFi/Cell) for better indoor support
        val priority = com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
        
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchQuery(query, " (Location Permission Missing)")
            return
        }

        fusedLocationClient.getCurrentLocation(priority, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val locInfo = " Current Location: ${location.latitude}, ${location.longitude}."
                    runOnUiThread { logDisplay.append("[System] Location Found (Active): ${location.latitude}, ${location.longitude}\n") }
                    launchQuery(query, locInfo)
                } else {
                    // 2. Fallback to Last Known Location
                    Log.w("MemoryQuery", "Active location null, trying LastKnown...")
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                         if (lastLoc != null) {
                             val locInfo = " Current Location (Estimated): ${lastLoc.latitude}, ${lastLoc.longitude}."
                             runOnUiThread { logDisplay.append("[System] Location Found (Cached): ${lastLoc.latitude}, ${lastLoc.longitude}\n") }
                             launchQuery(query, locInfo)
                         } else {
                             // 3. Final Fallback
                             runOnUiThread { logDisplay.append("[System] Location Unknown (Indoor/Deep). Searching globally.\n") }
                             launchQuery(query, " Current Location: Unknown (Indoor/Underground).")
                         }
                    }.addOnFailureListener {
                         runOnUiThread { logDisplay.append("[System] Location Error. Searching globally.\n") }
                         launchQuery(query, " Current Location: Unknown (Error).")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("MemoryQuery", "Active location failed: ${e.message}")
                // Fallback to Last Known (Same as above, simplified recursion could be better but copying for safety)
                fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                     if (lastLoc != null) {
                         val locInfo = " Current Location (Estimated): ${lastLoc.latitude}, ${lastLoc.longitude}."
                         runOnUiThread { logDisplay.append("[System] Location Found (Cached): ${lastLoc.latitude}, ${lastLoc.longitude}\n") }
                         launchQuery(query, locInfo)
                     } else {
                         launchQuery(query, " Current Location: Unknown (Indoor/Underground).")
                     }
                }
            }
    }

    private fun launchQuery(query: String, locationContext: String) {
        activityScope.launch {
            try {
                // Initial prompt setup
                var currentQuery = "You are a memory recall assistant. You can use tools to search the user's memories. " +
                        "Always search for information first before answering. Reply in natural Korean. " +
                        "Current Query: $query. Context: $locationContext"
                
                val history = mutableListOf<com.google.genai.types.Content>()
                history.add(com.google.genai.types.Content.builder().role("user").parts(listOf(com.google.genai.types.Part.builder().text(currentQuery).build())).build())

                var responsePayload = geminiClient.generateText(currentQuery, useTools = true)
                var response = responsePayload.first
                
                // Reasoning Loop (Max 2 turns for safety)
                var turn = 0
                while (turn < 2) {
                    val candidates = response?.candidates()?.orElse(null) ?: break
                    if (candidates.isEmpty()) break
                    
                    val candidate = candidates[0]
                    val contentObj = candidate.content()?.orElse(null) ?: break
                    val partsList = contentObj.parts()?.orElse(null) ?: break
                    
                    val part = partsList.firstOrNull { it.functionCall()?.isPresent == true } ?: break
                    val call = part.functionCall()!!.get()!!
                    
                    logDisplay.append("\n[데이터 검색 중: ${call.name()?.orElse("unknown")}]")
                    
                    // Execute Tool
                    val argsMap = mutableMapOf<String, Any?>()
                    call.args()?.ifPresent { args -> 
                        args.keys?.forEach { key ->
                            argsMap[key] = args.get(key)
                        }
                    }
                    
                    val result = toolHandler.handle(call.name()!!.get(), argsMap)
                    
                    // Add Assistant's call to history
                    history.add(contentObj)
                    
                    // Add Tool Response to History
                    history.add(Content.builder()
                        .role("user")
                        .parts(listOf(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                .name(call.name()!!.get())
                                .response(mapOf("result" to result))
                                .build())
                            .build()))
                        .build())
                    
                    // Call Gemini again with results
                    response = geminiClient.generateWithHistory(history)
                    turn++
                }

                val finalResult = response?.text()
                if (finalResult != null) {
                    logDisplay.append("\nGemini: $finalResult")
                } else {
                    logDisplay.append("\nError: Could not get final response")
                }
            } catch (e: Exception) {
                logDisplay.append("\nError: ${e.message}")
            }
        }
    }
}
