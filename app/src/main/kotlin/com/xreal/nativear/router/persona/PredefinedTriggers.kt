package com.xreal.nativear.router.persona

import com.xreal.nativear.core.XRealEvent

/**
 * Predefined trigger rules for auto-invoking personas based on event patterns.
 */
object PredefinedTriggers {

    private val VEHICLE_LABELS = setOf("car", "truck", "bus", "motorcycle", "bicycle")

    fun getDefaultRules(): List<TriggerRule> = listOf(
        // Safety Monitor: Auto-trigger on vehicle detection nearby
        TriggerRule(
            personaId = "safety_monitor",
            eventType = XRealEvent.PerceptionEvent.ObjectsDetected::class,
            condition = { event ->
                val detected = event as XRealEvent.PerceptionEvent.ObjectsDetected
                detected.results.any { det ->
                    det.label.lowercase() in VEHICLE_LABELS && det.confidence > 0.6f
                }
            },
            queryBuilder = { event ->
                val detected = event as XRealEvent.PerceptionEvent.ObjectsDetected
                val vehicles = detected.results.filter { it.label.lowercase() in VEHICLE_LABELS }
                "차량 감지됨: ${vehicles.joinToString { "${it.label}(${String.format("%.0f", it.confidence * 100)}%)" }}. 안전 위험도를 평가해주세요."
            },
            cooldownMs = 10_000,
            priority = 10,
            speakResult = true
        ),

        // Context Predictor: Auto-trigger on significant location change
        TriggerRule(
            personaId = "context_predictor",
            eventType = XRealEvent.PerceptionEvent.LocationUpdated::class,
            condition = { true }, // Always trigger on location update (cooldown handles frequency)
            queryBuilder = { event ->
                val loc = event as XRealEvent.PerceptionEvent.LocationUpdated
                val addr = loc.address ?: "${loc.lat}, ${loc.lon}"
                "사용자가 새 위치로 이동했습니다: $addr. 이 장소에서 예상되는 상황과 필요한 정보를 예측해주세요."
            },
            contextBuilder = { event ->
                val loc = event as XRealEvent.PerceptionEvent.LocationUpdated
                "lat=${loc.lat}, lon=${loc.lon}, address=${loc.address ?: "unknown"}"
            },
            cooldownMs = 120_000,
            priority = 3,
            speakResult = false
        ),

        // Memory Curator: Auto-trigger on person identification
        TriggerRule(
            personaId = "memory_curator",
            eventType = XRealEvent.PerceptionEvent.PersonIdentified::class,
            condition = { event ->
                val person = event as XRealEvent.PerceptionEvent.PersonIdentified
                person.confidence > 0.7f
            },
            queryBuilder = { event ->
                val person = event as XRealEvent.PerceptionEvent.PersonIdentified
                val name = person.personName ?: "ID:${person.personId}"
                "인물 감지: $name (신뢰도 ${String.format("%.0f", person.confidence * 100)}%). 이 사람과 관련된 기억을 검색하고 중요한 컨텍스트를 정리해주세요."
            },
            contextBuilder = { event ->
                val person = event as XRealEvent.PerceptionEvent.PersonIdentified
                "personId=${person.personId}, name=${person.personName}, confidence=${person.confidence}"
            },
            cooldownMs = 60_000,
            priority = 5,
            speakResult = false
        ),

        // Vision Analyst: Auto-trigger on scene capture
        TriggerRule(
            personaId = "vision_analyst",
            eventType = XRealEvent.PerceptionEvent.SceneCaptured::class,
            condition = { event ->
                val scene = event as XRealEvent.PerceptionEvent.SceneCaptured
                scene.ocrText.isNotBlank() // Only trigger when OCR found text
            },
            queryBuilder = { event ->
                val scene = event as XRealEvent.PerceptionEvent.SceneCaptured
                "장면 캡처됨. OCR 텍스트: ${scene.ocrText.take(200)}. 이 장면을 분석하고 사용자에게 유용한 정보를 추출해주세요."
            },
            cooldownMs = 30_000,
            priority = 4,
            speakResult = false
        )
    )
}
