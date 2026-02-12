package com.xreal.nativear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*

/**
 * LocationManager: Handles GPS updates and journey tracking context.
 */
class LocationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val memoryDatabase: UnifiedMemoryDatabase,
    private val naverService: NaverService // Injected
) : ILocationService {


    private val TAG = "LocationManager"
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    // private val geocoder = Geocoder(context, Locale.KOREAN) // Replaced by NaverService
    
    // ... (rest of the class)

    private fun finalizeJourney(endLocation: Location) {
        val now = System.currentTimeMillis()
        val startLoc = journeyStartLocation
        isJourneyActive = false
        lastStationaryStartTime = 0L

        scope.launch(Dispatchers.IO) {
            try {
                // Use Naver Reverse Geocoding
                val startAddress = startLoc?.let { naverService.reverseGeocode(it.latitude, it.longitude) } ?: "Start Location"
                val endAddress = naverService.reverseGeocode(endLocation.latitude, endLocation.longitude) ?: "End Location"
                
                val durationMin = (now - journeyStartTime) / 60000
                val distanceKm = journeyTotalDistance / 1000.0
                
                val content = "Journey Summary: Traveled ${String.format("%.2f", distanceKm)}km from $startAddress to $endAddress (Duration: ${durationMin} min)"
                Log.i(TAG, "🏁 $content")

                val metadata = JSONObject().apply {
                    put("type", "JOURNEY_SUMMARY")
                    put("start_address", startAddress)

                    put("end_address", endAddress)
                    put("distance_km", distanceKm)
                    put("duration_min", durationMin)
                }.toString()

                memoryDatabase.insertNode(UnifiedMemoryDatabase.MemoryNode(
                    timestamp = now,
                    role = "SYSTEM_LOG",
                    content = content,
                    latitude = endLocation.latitude,
                    longitude = endLocation.longitude,
                    metadata = metadata
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize journey", e)
            }
        }
    }

    private fun checkSpatialTrigger(location: Location, onTrigger: () -> Unit) {

        if (lastCapturedLocation == null) {
            lastCapturedLocation = location
            return
        }

        val distance = location.distanceTo(lastCapturedLocation!!)
        val speed = location.speed // m/s
        
        val threshold = when {
            speed < 3.0 -> THRESHOLD_WALKING
            speed < 10.0 -> THRESHOLD_CYCLING
            else -> THRESHOLD_DRIVING
        }

        if (distance >= threshold) {
            Log.i(TAG, "Spatial Trigger: Moved ${distance}m at speed ${speed}m/s")
            lastCapturedLocation = location
            onTrigger()
        }
    }


    private fun logPassiveLocation(loc: Location) {
        Thread {
            try {
                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val addressStr = addresses?.get(0)?.getAddressLine(0) ?: "Unknown Location"
                
                Log.d(TAG, "📍 Passive Location Logged: $addressStr")
                
                val metadata = JSONObject().apply {
                    put("type", "LOCATION_TRACE")
                    put("address", addressStr)
                    put("source", "GPS_PASSIVE")
                }.toString()

                memoryDatabase.insertNode(UnifiedMemoryDatabase.MemoryNode(
                    timestamp = System.currentTimeMillis(),
                    role = "SYSTEM_LOG",
                    content = "User Location Update: $addressStr",
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    metadata = metadata
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log location", e)
            }
        }.start()
    }
}
