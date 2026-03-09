package com.xreal.relay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.xreal.relay.health.HealthConnectReader
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 릴레이 앱 메인 화면 — 시작/중지 + 상태 표시.
 *
 * Tailscale IP를 우선 표시 — WiFi 변경에 무관하게 고정.
 * Fold 4에서 이 주소로 연결하면 네트워크 전환에도 재입력 불필요.
 */
class RelayMainActivity : AppCompatActivity() {

    private val TAG = "RelayMainActivity"
    private val PERMISSION_REQUEST_CODE = 1001

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvStats: TextView

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    // Health Connect 권한 요청 launcher
    private val healthConnectPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.REQUIRED_PERMISSIONS)) {
            Log.i(TAG, "Health Connect 권한 모두 승인됨")
            tvStats.text = "Health Connect: 권한 승인됨"
        } else {
            val grantedCount = granted.size
            val totalCount = HealthConnectReader.REQUIRED_PERMISSIONS.size
            Log.w(TAG, "Health Connect 일부 권한만 승인: $grantedCount/$totalCount")
            tvStats.text = "Health Connect: $grantedCount/$totalCount 권한 승인됨"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_main)

        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)
        tvAddress = findViewById(R.id.tv_address)
        tvStats = findViewById(R.id.tv_stats)

        btnToggle.setOnClickListener {
            if (isRunning) stopRelay() else startRelay()
        }

        // Tailscale IP 우선 표시 (WiFi 변경에 무관)
        updateAddressDisplay()

        checkPermissions()
        requestHealthConnectPermissions()
    }

    private fun checkPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestHealthConnectPermissions() {
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            tvStats.text = "Health Connect 미설치"
            return
        }

        healthConnectPermissionLauncher.launch(HealthConnectReader.REQUIRED_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                tvStatus.text = "권한 필요: ${denied.joinToString { it.first.substringAfterLast('.') }}"
            }
        }
    }

    private fun startRelay() {
        val intent = Intent(this, RelayForegroundService::class.java).apply {
            action = RelayForegroundService.ACTION_START
        }
        startForegroundService(intent)
        isRunning = true
        btnToggle.text = "중지"
        tvStatus.text = "릴레이 시작 중..."

        // 통계 갱신 루프
        startStatsUpdate()
    }

    private fun stopRelay() {
        val intent = Intent(this, RelayForegroundService::class.java).apply {
            action = RelayForegroundService.ACTION_STOP
        }
        startService(intent)
        isRunning = false
        btnToggle.text = "시작"
        tvStatus.text = "중지됨"
        tvStats.text = ""
        handler.removeCallbacksAndMessages(null)
    }

    private fun startStatsUpdate() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                tvStatus.text = "릴레이 실행 중"
                updateAddressDisplay()
                handler.postDelayed(this, 2000)
            }
        }, 2000)
    }

    /**
     * 주소 표시 — Tailscale IP 우선 (WiFi 변경 무관), WiFi IP는 참고용.
     */
    private fun updateAddressDisplay() {
        val tailscaleIp = getTailscaleIp()
        val wifiIp = getWifiIp()
        val port = RelayForegroundService.RELAY_PORT

        tvAddress.text = if (tailscaleIp != null) {
            "★ Tailscale: $tailscaleIp:$port (고정)\n   WiFi: ${wifiIp ?: "없음"}:$port"
        } else {
            "WiFi: ${wifiIp ?: "없음"}:$port\n⚠ Tailscale 미연결 (WiFi 변경 시 주소 바뀜)"
        }
    }

    /**
     * Tailscale VPN IP 탐지 — 100.x.x.x 대역, tun/tailscale 인터페이스.
     * WiFi/LTE 변경에 무관하게 고정 IP 유지.
     */
    private fun getTailscaleIp(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                val name = iface.name.lowercase()
                // Tailscale 인터페이스: tun0, tailscale0, utun 등
                val isTailscale = name.startsWith("tun") || name.contains("tailscale") || name.startsWith("utun")
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // Tailscale CGNAT 대역: 100.64.0.0/10 (100.64.x.x ~ 100.127.x.x)
                        if (ip.startsWith("100.")) {
                            return ip
                        }
                        // tun 인터페이스이면 Tailscale일 가능성 높음
                        if (isTailscale) return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tailscale IP 탐지 실패: ${e.message}")
        }
        return null
    }

    private fun getWifiIp(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "WiFi IP 확인 실패: ${e.message}")
        }
        return null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
