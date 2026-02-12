package com.xreal.nativear

import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.FunctionDeclaration
import org.json.JSONObject

object GeminiTools {
    
    // Using positional arguments (name, description, parameters) as inferred from build errors
    val searchWeb = defineFunction(
        "searchWeb",
        "Search the web for information using Naver Search API."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "query": {
                        "type": "STRING",
                        "description": "The search query."
                    }
                },
                "required": ["query"]
            }
        """.trimIndent())
    }
    
    val getWeather = defineFunction(
        "getWeather",
        "Get current weather for a specific location."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "location": {
                        "type": "STRING",
                        "description": "City or region name."
                    }
                },
                "required": ["location"]
            }
        """.trimIndent())
    }
    
    val setVisionControl = defineFunction(
        "setVisionControl",
        "Control vision features like OCR or pose detection."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "feature": {
                        "type": "STRING",
                        "description": "The feature to control (OCR, POSE)."
                    },
                    "enabled": {
                        "type": "BOOLEAN",
                        "description": "Whether to enable or disable."
                    }
                },
                "required": ["feature", "enabled"]
            }
        """.trimIndent())
    }

    val queryTemporalMemory = defineFunction(
        "query_temporal_memory",
        "Search memories within a specific time range."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "start_time": { "type": "STRING", "description": "ISO format or 'today', 'now'" },
                    "end_time": { "type": "STRING", "description": "ISO format or 'today', 'now'" }
                },
                "required": ["start_time", "end_time"]
            }
        """.trimIndent())
    }

    val querySpatialMemory = defineFunction(
        "query_spatial_memory",
        "Search memories near a location."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "latitude": { "type": "NUMBER" },
                    "longitude": { "type": "NUMBER" },
                    "radius_km": { "type": "NUMBER", "description": "Radius in kilometers" }
                },
                "required": ["latitude", "longitude"]
            }
        """.trimIndent())
    }

    val queryKeywordMemory = defineFunction(
        "query_keyword_memory",
        "Search memories by text keywords."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "keyword": { "type": "STRING" }
                },
                "required": ["keyword"]
            }
        """.trimIndent())
    }

    val getDirections = defineFunction(
        "get_directions",
        "Get navigation directions to a destination."
    ) {
        JSONObject("""
            {
                "type": "OBJECT",
                "properties": {
                    "destination": { "type": "STRING", "description": "The destination address or place name." },
                    "origin": { "type": "STRING", "description": "Optional starting point (defaults to current location)." }
                },
                "required": ["destination"]
            }
        """.trimIndent())
    }

    val takeSnapshot = defineFunction(
        "take_snapshot",
        "Capture a manual snapshot of the current scene."
    ) {
        JSONObject("{ \"type\": \"OBJECT\", \"properties\": {} }")
    }

    val getCurrentLocation = defineFunction(
        "get_current_location",
        "Get the user's current GPS coordinates and speed."
    ) {
        JSONObject("{ \"type\": \"OBJECT\", \"properties\": {} }")
    }

    val syncMemory = defineFunction(
        "sync_memory",
        "Synchronize the local memory database to the cloud for backup."
    ) {
        JSONObject("{ \"type\": \"OBJECT\", \"properties\": {} }")
    }

    val queryVisualMemory = defineFunction(
        "query_visual_memory",
        "Search memories that are visually similar to what the user is currently seeing."
    ) {
        JSONObject("{ \"type\": \"OBJECT\", \"properties\": {} }")
    }

    fun getAllTools(): List<FunctionDeclaration> {
        return listOf(
            searchWeb, 
            getWeather, 
            setVisionControl, 
            queryTemporalMemory, 
            querySpatialMemory, 
            queryKeywordMemory,
            getDirections,
            takeSnapshot,
            getCurrentLocation,
            syncMemory,
            queryVisualMemory
        )
    }


}



