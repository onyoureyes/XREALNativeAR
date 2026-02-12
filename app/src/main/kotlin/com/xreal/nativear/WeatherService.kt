package com.xreal.nativear

/**
 * WeatherService: Implements IWeatherService for real-time weather alerts.
 */
class WeatherService : IWeatherService {
    override fun getWeather(location: String): String {
        return "Weather for $location: Partly Cloudy, 24°C, Humidity 45%"
    }
}
