package com.xreal.nativear

interface ISearchService {
    fun searchWeb(query: String): String
}

interface IWeatherService {
    fun getWeather(location: String): String
}

interface INavigationService {
    fun getDirections(from: String, to: String): String
}

interface IVisionService {
    fun setOcrEnabled(enabled: Boolean)
    fun setPoseEnabled(enabled: Boolean)
    fun setSceneCaptureEnabled(enabled: Boolean)
    fun setDetectionEnabled(enabled: Boolean)
    fun translate(text: String, onResult: (String) -> Unit)
    fun captureSceneSnapshot()
    fun cycleCamera()
    fun getLatestBitmap(): android.graphics.Bitmap?
}

interface IVoiceService {
    fun startListening()
    fun stopListening()
    fun speak(text: String, isResponse: Boolean = true)
    fun startWakeWordDetection()
    fun stopWakeWordDetection()
    fun setConversing(isConversing: Boolean)
}

interface IMemoryService {
    suspend fun saveMemory(content: String, role: String, metadata: String? = null, lat: Double? = null, lon: Double? = null)

    suspend fun queryTemporal(startTime: Long, endTime: Long): String
    suspend fun querySpatial(lat: Double, lon: Double, radiusKm: Double): String
    suspend fun queryKeyword(keyword: String): String
    suspend fun queryVisual(bitmap: android.graphics.Bitmap): String
    suspend fun queryEmotion(emotion: String): String
}

interface ILocationService {
    fun getCurrentLocation(): android.location.Location?
    fun updatePdr(dx: Float, dy: Float)
}

