package com.xreal.nativear

import android.util.Log

/**
 * NaverService: Implements ISearchService and INavigationService using Naver API.
 */
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class NaverService : ISearchService, INavigationService {
    private val TAG = "NaverService"
    private val client = OkHttpClient()
    
    // NCP Keys (Maps, Directions, Geocoding)
    private val ncpClientId = BuildConfig.NAVER_CLIENT_ID 
    private val ncpClientSecret = BuildConfig.NAVER_CLIENT_SECRET 

    // Search Keys (Naver Developers Open API)
    private val searchClientId = BuildConfig.NAVER_SEARCH_CLIENT_ID
    private val searchClientSecret = BuildConfig.NAVER_SEARCH_CLIENT_SECRET

    override fun searchWeb(query: String): String {
        Log.i(TAG, "Searching Naver for: $query")
        
        if (searchClientId.isEmpty() || searchClientSecret.isEmpty()) {
             // Fallback to Geocoding if Search keys are missing but NCP keys exist
             if (ncpClientId.isNotEmpty()) return geocode(query)
             return "Error: Naver Search API keys not configured."
        }

        val url = "https://openapi.naver.com/v1/search/webkr.json?query=${java.net.URLEncoder.encode(query, "UTF-8")}&display=3"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Naver-Client-Id", searchClientId)
            .addHeader("X-Naver-Client-Secret", searchClientSecret)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Naver Search Failed: ${response.code}"
                
                val json = JSONObject(response.body?.string() ?: "{}")
                val items = json.optJSONArray("items")
                val sb = StringBuilder()
                
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val title = item.optString("title").replace("<b>", "").replace("</b>", "")
                        val desc = item.optString("description").replace("<b>", "").replace("</b>", "")
                        val link = item.optString("link")
                        sb.append("${i + 1}. $title\n   $desc\n   ($link)\n\n")
                    }
                }
                if (sb.isEmpty()) "No search results found." else sb.toString()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Search error", e)
            "Network error during search."
        }
    }

    override fun getDirections(from: String, to: String): String {
        Log.i(TAG, "Getting NCP Directions from $from to $to")
        
        if (ncpClientId.isEmpty() || ncpClientSecret.isEmpty()) return "Error: Naver Cloud keys missing."

        // 1. Convert text addresses to Coordinates (Geocoding)
        val fromCoords = resolveCoords(from) ?: return "Could not find start location: $from"
        val toCoords = resolveCoords(to) ?: return "Could not find destination: $to"
        
        // 2. Request Driving Directions (Directions 15 API)
        val url = "https://naveropenapi.apigw.ntruss.com/map-direction/v1/driving" +
                "?start=${fromCoords.first},${fromCoords.second}" +
                "&goal=${toCoords.first},${toCoords.second}" +
                "&option=trafast" // Real-time optimal route

        val request = Request.Builder()
            .url(url)
            .addHeader("X-NCP-APIGW-API-KEY-ID", ncpClientId)
            .addHeader("X-NCP-APIGW-API-KEY", ncpClientSecret)
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Directions Failed: ${response.code}"
                
                val json = JSONObject(response.body?.string() ?: "{}")
                val route = json.optJSONObject("route")?.optJSONArray("trafast")?.optJSONObject(0)
                
                if (route != null) {
                    val summary = route.optJSONObject("summary")
                    val distance = summary?.optInt("distance") ?: 0 // meters
                    val duration = summary?.optInt("duration") ?: 0 // milliseconds
                    
                    val guide = route.optJSONArray("guide")
                    val steps = StringBuilder()
                    if (guide != null) {
                        for (i in 0 until guide.length()) {
                            val step = guide.getJSONObject(i)
                            val instruct = step.optString("instructions")
                            if (instruct.isNotEmpty()) steps.append("- $instruct\n")
                        }
                    }
                    
                    "Route Info:\nDistance: ${distance / 1000.0} km\nDuration: ${duration / 60000} mins\n\nSteps:\n$steps"
                } else {
                    "No route found."
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Directions error", e)
            "Network error during navigation."
        }
    }

    private fun resolveCoords(address: String): Pair<Double, Double>? {
        // Minimal Geocoding Implementation for NCP
        val url = "https://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode?query=${java.net.URLEncoder.encode(address, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-NCP-APIGW-API-KEY-ID", ncpClientId)
            .addHeader("X-NCP-APIGW-API-KEY", ncpClientSecret)
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: "{}")
                val addressItem = json.optJSONArray("addresses")?.optJSONObject(0)
                if (addressItem != null) {
                    val lon = addressItem.optString("x").toDoubleOrNull()
                    val lat = addressItem.optString("y").toDoubleOrNull()
                    if (lat != null && lon != null) Pair(lon, lat) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun geocode(query: String): String {
       val coords = resolveCoords(query)
       return if (coords != null) {
           "Found Location: $query at Lat ${coords.second}, Lon ${coords.first}"
       } else {
           "Could not find location: $query"
       }
    }

    fun reverseGeocode(lat: Double, lon: Double): String? {
        // NCP Reverse Geocoding (gc)
        // https://naveropenapi.apigw.ntruss.com/map-reversegeocode/v2/gc
        val url = "https://naveropenapi.apigw.ntruss.com/map-reversegeocode/v2/gc" +
                "?coords=$lon,$lat" +
                "&output=json" +
                "&orders=roadaddr,addr" // Prioritize Road Address, then Legacy Address

        val request = Request.Builder()
            .url(url)
            .addHeader("X-NCP-APIGW-API-KEY-ID", ncpClientId)
            .addHeader("X-NCP-APIGW-API-KEY", ncpClientSecret)
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                
                val json = JSONObject(response.body?.string() ?: "{}")
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val result = results.getJSONObject(0)
                    val region = result.optJSONObject("region")
                    val area1 = region?.optJSONObject("area1")?.optString("name") ?: "" // Do (e.g., Gyeonggi-do)
                    val area2 = region?.optJSONObject("area2")?.optString("name") ?: "" // Si/Gu (e.g., Seongnam-si)
                    val area3 = region?.optJSONObject("area3")?.optString("name") ?: "" // Dong (e.g., Jeongja-dong)
                    
                    val land = result.optJSONObject("land")
                    val roadName = land?.optString("name") ?: ""
                    val number1 = land?.optString("number1") ?: ""
                    
                    // Construct Address
                    val sb = StringBuilder()
                    if (area1.isNotEmpty()) sb.append(area1).append(" ")
                    if (area2.isNotEmpty()) sb.append(area2).append(" ")
                    if (area3.isNotEmpty()) sb.append(area3).append(" ")
                    if (roadName.isNotEmpty()) sb.append(roadName).append(" ").append(number1)
                    
                    sb.toString().trim()
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reverse Geocode Error", e)
            null
        }
    }
}




