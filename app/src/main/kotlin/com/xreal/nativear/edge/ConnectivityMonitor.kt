package com.xreal.nativear.edge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ConnectivityMonitor — 네트워크 상태 감지 및 EventBus 발행.
 *
 * RESEARCH.md §12 GlobalEventBus, §6 Kotlin 패턴 참조.
 *
 * ## 역할
 * - ConnectivityManager.NetworkCallback 등록
 * - 상태 변경 시 XRealEvent.SystemEvent.NetworkStateChanged 발행
 * - EdgeDelegationRouter가 구독하여 오프라인 전환 시 엣지 AI 활성화
 *
 * ## 초기 상태
 * 앱 시작 시 현재 네트워크 상태를 즉시 확인하여 isOnline 설정.
 */
class ConnectivityMonitor(
    private val context: Context,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "ConnectivityMonitor"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 현재 온라인 여부 (thread-safe) */
    @Volatile
    var isOnline: Boolean = false
        private set

    /** 현재 네트워크 타입 ("WIFI", "MOBILE", "NONE") */
    @Volatile
    var networkType: String = "NONE"
        private set

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val type = detectNetworkType()
            updateState(true, type)
            Log.i(TAG, "네트워크 연결: $type")
        }

        override fun onLost(network: Network) {
            updateState(false, "NONE")
            Log.i(TAG, "네트워크 끊김 — 엣지 AI 모드 전환")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            if (type != networkType) {
                updateState(true, type)
                Log.d(TAG, "네트워크 타입 변경: $type")
            }
        }
    }

    fun start() {
        if (registered) return

        // 현재 상태 즉시 확인
        val currentOnline = checkCurrentState()
        isOnline = currentOnline
        networkType = if (currentOnline) detectNetworkType() else "NONE"

        // NetworkCallback 등록 — registerDefaultNetworkCallback: 순수 모니터링
        // (registerNetworkCallback은 Samsung에서 WiFi 자동 활성화 유발 가능)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        registered = true

        Log.i(TAG, "ConnectivityMonitor started — 현재 상태: ${if (isOnline) networkType else "OFFLINE"}")
    }

    fun stop() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "NetworkCallback 해제 오류: ${e.message}")
        }
        registered = false
        Log.i(TAG, "ConnectivityMonitor stopped")
    }

    // =========================================================================
    // 내부 유틸리티
    // =========================================================================

    private fun updateState(online: Boolean, type: String) {
        val changed = isOnline != online || networkType != type
        isOnline = online
        networkType = type

        if (changed) {
            scope.launch {
                eventBus.publish(
                    XRealEvent.SystemEvent.NetworkStateChanged(
                        isOnline = online,
                        networkType = type
                    )
                )
            }
        }
    }

    private fun checkCurrentState(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun detectNetworkType(): String {
        val activeNetwork = connectivityManager.activeNetwork ?: return "NONE"
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }
}
