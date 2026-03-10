package com.xreal.nativear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import com.xreal.nativear.memory.api.IMemoryStore
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
    private val naverService: NaverService,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus
) : ILocationService {

    // Lazy inject to break circular dependency: MemoryRepository → ILocationService → LocationManager → IMemoryStore
    private val memoryStore: IMemoryStore by org.koin.java.KoinJavaComponent.inject(IMemoryStore::class.java)

    private val TAG = "LocationManager"
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val geocoder = Geocoder(context, Locale.KOREAN)
    
    private var lastCapturedLocation: Location? = null
    private val THRESHOLD_WALKING = 10.0f
    private val THRESHOLD_CYCLING = 30.0f
    private val THRESHOLD_DRIVING = 100.0f
    
    // Journey Tracking Variables
    private var isJourneyActive = false
    private var journeyStartLocation: Location? = null
    private var journeyStartTime = 0L
    private var journeyTotalDistance = 0f
    private var lastStationaryStartTime = 0L

    override fun getCurrentLocation(): Location? {
        return lastCapturedLocation
    }

    override fun updatePdr(dx: Float, dy: Float) {
        // PDR tracking not fully implemented, logging for now
        Log.d(TAG, "PDR Update: dx=$dx, dy=$dy")
    }

    private var locationCallback: LocationCallback? = null

    /**
     * Start GPS location updates. Calls [onSpatialTrigger] when the user has
     * moved beyond the speed-dependent distance threshold.
     */
    fun startLocationUpdates(onSpatialTrigger: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted — skipping GPS tracking")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastCapturedLocation = location

                // Publish PhoneGps event for position fusion engine
                scope.launch {
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.PhoneGps(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        timestamp = System.currentTimeMillis()
                    ))
                }

                // Journey tracking
                if (!isJourneyActive) {
                    isJourneyActive = true
                    journeyStartLocation = location
                    journeyStartTime = System.currentTimeMillis()
                    journeyTotalDistance = 0f
                } else {
                    val prev = journeyStartLocation
                    if (prev != null) {
                        journeyTotalDistance += location.distanceTo(prev)
                    }
                }

                // Spatial trigger (distance-based scene capture)
                checkSpatialTrigger(location, onSpatialTrigger)

                // Passive location log (every update)
                logPassiveLocation(location)
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        Log.i(TAG, "📍 GPS location tracking started (10s interval)")
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            Log.i(TAG, "📍 GPS location tracking stopped")
        }
        locationCallback = null
        if (isJourneyActive && lastCapturedLocation != null) {
            finalizeJourney(lastCapturedLocation!!)
        }
    }

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
                    put("distance_km", distanceKm.toDouble())
                    put("duration_min", durationMin.toLong())
                }.toString()

                memoryStore.save(content, "SYSTEM_LOG", metadata, lat = endLocation.latitude, lon = endLocation.longitude)
            } catch (e: Exception) {
                ErrorReporter.report(TAG, "여정 완료 기록 실패", e, ErrorSeverity.WARNING)
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
        scope.launch(Dispatchers.IO) {
            try {
                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val addressStr = addresses?.get(0)?.getAddressLine(0) ?: "Unknown Location"

                Log.d(TAG, "Passive Location Logged: $addressStr")

                val metadata = JSONObject().apply {
                    put("type", "LOCATION_TRACE")
                    put("address", addressStr)
                    put("source", "GPS_PASSIVE")
                }.toString()

                memoryStore.save("User Location Update: $addressStr", "SYSTEM_LOG", metadata, lat = loc.latitude, lon = loc.longitude)
            } catch (e: Exception) {
                ErrorReporter.report(TAG, "위치 로그 기록 실패", e, ErrorSeverity.INFO)
            }
        }
    }
}
