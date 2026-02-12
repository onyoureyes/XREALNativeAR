package com.xreal.nativear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * WeatherServiceTest: Test for Korea Meteorological Administration API integration.
 */
object WeatherServiceTest {
    private const val TAG = "WeatherServiceTest"
    
    fun runTests(context: Context) {
        Log.i(TAG, "=== 기상청 API 테스트 시작 ===")
        
        val weatherService = WeatherService(context)
        
        // Test 1: 서울 날씨
        testLocation(weatherService, "서울")
        
        // Test 2: 부산 날씨
        testLocation(weatherService, "부산")
        
        // Test 3: 대전 날씨
        testLocation(weatherService, "대전")
        
        // Test 4: 캐시 테스트 (캐시된 결과 반환)
        Log.i(TAG, "\n--- 캐시 테스트 ---")
        testLocation(weatherService, "서울") // Should be cached
        
        // Test 5: 영문 도시명
        testLocation(weatherService, "Seoul")
        
        Log.i(TAG, "\n=== 기상청 API 테스트 완료 ===")
    }
    
    private fun testLocation(service: WeatherService, location: String) {
        Log.i(TAG, "\n--- 테스트: $location ---")
        try {
            val startTime = System.currentTimeMillis()
            val weather = service.getWeather(location)
            val duration = System.currentTimeMillis() - startTime
            
            Log.i(TAG, "✅ 결과: $weather")
            Log.i(TAG, "⏱️ 소요시간: ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 실패: ${e.message}", e)
        }
    }
    
    fun runAsyncTests(context: Context) {
        runBlocking {
            Log.i(TAG, "=== 비동기 기상청 API 테스트 시작 ===")
            
            val weatherService = WeatherService(context)
            
            try {
                val weather = weatherService.getWeatherAsync("서울")
                Log.i(TAG, "✅ 비동기 결과: $weather")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 비동기 실패: ${e.message}", e)
            }
            
            Log.i(TAG, "=== 비동기 기상청 API 테스트 완료 ===")
        }
    }
}
