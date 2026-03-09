package com.xreal.nativear.monitoring

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.xreal.nativear.core.DeviceMode
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.ResourceSeverity
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

/**
 * ResourceMonitor — 갤럭시 폴드4 시스템 자원 실시간 모니터링.
 *
 * ## 측정 항목
 * - CPU 사용률: /proc/stat 기반 delta 측정 (30초 간격)
 * - RAM 사용량: ActivityManager.MemoryInfo (30초 간격)
 * - 배터리 온도: BatteryManager 브로드캐스트 (변경 시 즉시)
 *
 * ## 임계값 (갤폴드4 기준)
 * | 상태    | CPU  | 배터리 온도 | 조치 |
 * |--------|------|-----------|------|
 * | NORMAL  | <70% | <42°C     | 유지 |
 * | WARNING | 70%+ | 42-46°C   | 케이던스 절감 제안 |
 * | CRITICAL| 85%+ | 46°C+     | 모드 다운그레이드 |
 *
 * @param context Android Context (ApplicationContext 권장)
 * @param eventBus 이벤트 발행용
 * @param intervalMs 폴링 간격 ms (기본 30초)
 */
class ResourceMonitor(
    private val context: Context,
    private val eventBus: GlobalEventBus,
    private val intervalMs: Long = 30_000L
) {
    private val TAG = "ResourceMonitor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    // 최근 측정값 (스냅샷 조회용)
    @Volatile var lastCpuPercent: Int = 0
        private set
    @Volatile var lastRamUsedMb: Int = 0
        private set
    @Volatile var lastRamTotalMb: Int = 0
        private set
    @Volatile var lastBatteryTempC: Float = 0f
        private set
    @Volatile var lastSeverity: ResourceSeverity = ResourceSeverity.NORMAL
        private set

    // CPU 측정을 위한 이전 /proc/stat 값
    private var prevTotal: Long = 0L
    private var prevIdle: Long = 0L

    // 배터리 온도 리시버 (변화 시 즉시 감지)
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            lastBatteryTempC = rawTemp / 10f
        }
    }

    fun start() {
        Log.i(TAG, "ResourceMonitor started (interval: ${intervalMs}ms)")

        // 배터리 온도 브로드캐스트 등록
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        monitorJob = scope.launch {
            while (isActive) {
                val cpuPercent = measureCpuPercent()
                val (ramUsed, ramTotal) = measureRam()
                lastCpuPercent = cpuPercent
                lastRamUsedMb = ramUsed
                lastRamTotalMb = ramTotal

                val severity = calculateSeverity(cpuPercent, lastBatteryTempC)
                lastSeverity = severity

                Log.d(TAG, "CPU: ${cpuPercent}% | RAM: ${ramUsed}/${ramTotal}MB | " +
                        "Temp: ${lastBatteryTempC}°C | Severity: $severity")

                // 경고 이상이면 이벤트 발행 (NORMAL은 로그만)
                if (severity != ResourceSeverity.NORMAL) {
                    eventBus.publish(XRealEvent.SystemEvent.ResourceAlert(
                        cpuPercent = cpuPercent,
                        ramUsedMb = ramUsed,
                        ramTotalMb = ramTotal,
                        batteryTempC = lastBatteryTempC,
                        severity = severity
                    ))
                }

                delay(intervalMs)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // 등록 안 된 경우 무시
        }
        Log.i(TAG, "ResourceMonitor stopped")
    }

    /**
     * /proc/stat을 두 번 읽어 CPU 사용률 계산.
     * delta(idle) / delta(total) 방식 — 30초 간격으로 호출 시 정확함.
     */
    private fun measureCpuPercent(): Int {
        return try {
            val reader = BufferedReader(FileReader(File("/proc/stat")))
            val line = reader.readLine()
            reader.close()

            // cpu  user nice system idle iowait irq softirq steal ...
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return lastCpuPercent

            val user    = parts[1].toLong()
            val nice    = parts[2].toLong()
            val system  = parts[3].toLong()
            val idle    = parts[4].toLong()
            val iowait  = if (parts.size > 5) parts[5].toLong() else 0L
            val irq     = if (parts.size > 6) parts[6].toLong() else 0L
            val softirq = if (parts.size > 7) parts[7].toLong() else 0L

            val total = user + nice + system + idle + iowait + irq + softirq
            val deltaTotal = total - prevTotal
            val deltaIdle  = idle - prevIdle

            prevTotal = total
            prevIdle  = idle

            if (deltaTotal <= 0) return lastCpuPercent
            val usedPercent = ((deltaTotal - deltaIdle) * 100 / deltaTotal).toInt()
            usedPercent.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(TAG, "CPU 측정 실패: ${e.message}")
            lastCpuPercent
        }
    }

    /**
     * ActivityManager로 RAM 사용량 측정.
     * @return Pair(usedMb, availableTotalMb)
     */
    private fun measureRam(): Pair<Int, Int> {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMb = (memInfo.totalMem / 1024 / 1024).toInt()
            val availMb = (memInfo.availMem / 1024 / 1024).toInt()
            val usedMb  = totalMb - availMb

            Pair(usedMb, totalMb)
        } catch (e: Exception) {
            Log.w(TAG, "RAM 측정 실패: ${e.message}")
            Pair(lastRamUsedMb, lastRamTotalMb)
        }
    }

    private fun calculateSeverity(cpuPercent: Int, tempC: Float): ResourceSeverity {
        return when {
            cpuPercent >= 85 || tempC >= 46f -> ResourceSeverity.CRITICAL
            cpuPercent >= 70 || tempC >= 42f -> ResourceSeverity.WARNING
            else -> ResourceSeverity.NORMAL
        }
    }

    /**
     * 현재 자원 상태를 즉시 스냅샷으로 반환.
     * DirectiveConsumer, DeviceModeManager 등이 on-demand 조회 시 사용.
     */
    fun getSnapshot(): ResourceSnapshot = ResourceSnapshot(
        cpuPercent = lastCpuPercent,
        ramUsedMb = lastRamUsedMb,
        ramTotalMb = lastRamTotalMb,
        batteryTempC = lastBatteryTempC,
        severity = lastSeverity
    )

    data class ResourceSnapshot(
        val cpuPercent: Int,
        val ramUsedMb: Int,
        val ramTotalMb: Int,
        val batteryTempC: Float,
        val severity: ResourceSeverity
    ) {
        val ramUsedPercent: Int get() = if (ramTotalMb > 0) (ramUsedMb * 100 / ramTotalMb) else 0
        fun toLogString() = "CPU:${cpuPercent}% RAM:${ramUsedMb}/${ramTotalMb}MB(${ramUsedPercent}%) 온도:${batteryTempC}°C [$severity]"
    }
}
