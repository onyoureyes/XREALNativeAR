package com.xreal.nativear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * WeatherService: Implements IWeatherService with Korea Meteorological Administration (기상청) API.
 * Uses short-term forecast API (초단기예보조회서비스).
 */
class WeatherService(private val context: Context) : IWeatherService {
    private val TAG = "WeatherService"
    
    // 기상청 API Hub configuration
    private val AUTH_KEY = "PHMF5tfmQG2zBebX5gBtlA"
    
    // API Hub Endpoint (typ02/openApi)
    private val BASE_URL = "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getUltraSrtFcst"
    
    // Cache for weather data (10 minutes TTL)
    private data class WeatherCache(
        val data: String,
        val timestamp: Long
    )
    private val cache = mutableMapOf<String, WeatherCache>()
    private val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes
    
    // 주요 도시 좌표 (격자 X, Y)
    private val cityCoordinates = mapOf(
        "서울" to Pair(60, 127),
        "Seoul" to Pair(60, 127),
        "부산" to Pair(98, 76),
        "Busan" to Pair(98, 76),
        "대구" to Pair(89, 90),
        "Daegu" to Pair(89, 90),
        "인천" to Pair(55, 124),
        "Incheon" to Pair(55, 124),
        "광주" to Pair(58, 74),
        "Gwangju" to Pair(58, 74),
        "대전" to Pair(67, 100),
        "Daejeon" to Pair(67, 100),
        "울산" to Pair(102, 84),
        "Ulsan" to Pair(102, 84),
        "세종" to Pair(66, 103),
        "Sejong" to Pair(66, 103)
    )
    
    override fun getWeather(location: String): String {
        // Check cache first
        val cached = cache[location]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            Log.d(TAG, "Returning cached weather for $location")
            return cached.data
        }
        
        // Try to fetch from API
        return try {
            fetchWeatherFromKMA(location)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch weather: ${e.message}", e)
            // Fallback to dummy data
            getDummyWeather(location)
        }
    }
    
    /**
     * Get dummy weather data as fallback.
     */
    private fun getDummyWeather(location: String): String {
        Log.w(TAG, "Using dummy weather data for $location (API unavailable)")
        return when (location) {
            "서울", "Seoul" -> "서울 날씨: 맑음, 15°C, 습도 60% (데모 모드)"
            "부산", "Busan" -> "부산 날씨: 구름많음, 18°C, 습도 70% (데모 모드)"
            "대전", "Daejeon" -> "대전 날씨: 흐림, 14°C, 습도 65% (데모 모드)"
            else -> "$location 날씨: 맑음, 16°C, 습도 55% (데모 모드)"
        }
    }
    
    /**
     * Fetch weather from Korea Meteorological Administration API Hub.
     */
    private fun fetchWeatherFromKMA(location: String): String {
        // Get grid coordinates
        val coords = cityCoordinates[location] ?: cityCoordinates["서울"]!!
        val (nx, ny) = coords
        
        // Get current date/time
        val now = LocalDateTime.now()
        val baseDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val baseTime = getBaseTime(now)
        
        // Build API URL
        val urlString = buildString {
            append(BASE_URL)
            append("?authKey=").append(URLEncoder.encode(AUTH_KEY, "UTF-8")) // Use authKey for API Hub
            append("&numOfRows=60")
            append("&pageNo=1")
            append("&dataType=JSON")
            append("&base_date=").append(baseDate)
            append("&base_time=").append(baseTime)
            append("&nx=").append(nx)
            append("&ny=").append(ny)
        }
        
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                
                // API Hub might return plain text or JSON depending on dataType.
                // Assuming JSON structure is similar to Public Data Portal but let's be safe.
                val weatherData = parseKMAResponse(response, location)
                
                // Cache the result
                cache[location] = WeatherCache(weatherData, System.currentTimeMillis())
                
                weatherData
            } else {
                Log.e(TAG, "API returned error code: $responseCode")
                "$location 날씨 정보를 가져올 수 없습니다 (오류: $responseCode)"
            }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Get base time for API request (기상청 API는 매시각 30분에 발표)
     */
    private fun getBaseTime(now: LocalDateTime): String {
        val hour = if (now.minute < 30) {
            if (now.hour == 0) 23 else now.hour - 1
        } else {
            now.hour
        }
        return String.format("%02d30", hour)
    }
    
    /**
     * Parse KMA JSON response.
     */
    private fun parseKMAResponse(jsonResponse: String, location: String): String {
        return try {
            val json = JSONObject(jsonResponse)
            val response = json.getJSONObject("response")
            val body = response.getJSONObject("body")
            val items = body.getJSONObject("items").getJSONArray("item")
            
            var temp: String? = null  // T1H: 기온
            var humidity: String? = null  // REH: 습도
            var sky: String? = null  // SKY: 하늘상태
            var pty: String? = null  // PTY: 강수형태
            
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val category = item.getString("category")
                val fcstValue = item.getString("fcstValue")
                
                when (category) {
                    "T1H" -> temp = fcstValue
                    "REH" -> humidity = fcstValue
                    "SKY" -> sky = fcstValue
                    "PTY" -> pty = fcstValue
                }
            }
            
            // Parse sky condition
            val skyCondition = when (sky) {
                "1" -> "맑음"
                "3" -> "구름많음"
                "4" -> "흐림"
                else -> "알 수 없음"
            }
            
            // Parse precipitation type
            val precipitation = when (pty) {
                "0" -> ""
                "1" -> ", 비"
                "2" -> ", 비/눈"
                "3" -> ", 눈"
                "4" -> ", 소나기"
                else -> ""
            }
            
            "$location 날씨: $skyCondition$precipitation, ${temp}°C, 습도 ${humidity}%"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse KMA response: ${e.message}", e)
            "$location 날씨 데이터 파싱 오류"
        }
    }
    
    /**
     * Async version for coroutine contexts.
     */
    suspend fun getWeatherAsync(location: String): String = withContext(Dispatchers.IO) {
        getWeather(location)
    }
    
    /**
     * Clear weather cache.
     */
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "Weather cache cleared")
    }
}
