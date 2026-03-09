package com.xreal.nativear.cadence

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.BaseRouter
import com.xreal.nativear.router.DecisionLogger
import com.xreal.nativear.router.RouterDecision
import kotlinx.coroutines.launch

/**
 * Detects user needs from sensor/vision events and triggers appropriate actions.
 *
 * Current detections:
 * - Foreign text in OCR → translate → ShowMessage + SpeakTTS
 * - Frequent foreign text → boost OCR capture rate
 * - High-interest exploration → trigger snapshot
 */
class NeedsDetector(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger,
    private val translationService: TranslationService,
    private val cadenceConfig: CadenceConfig,
    private val userStateTracker: UserStateTracker
) : BaseRouter("needs_detector", eventBus, decisionLogger) {

    companion object {
        // ★ Policy Department: PolicyRegistry shadow read
        private val TRANSLATE_COOLDOWN_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.translate_cooldown_ms", 5_000L)
        private val OCR_BOOST_WINDOW_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.ocr_boost_window_ms", 30_000L)
        private val OCR_BOOST_THRESHOLD: Int get() =
            com.xreal.nativear.policy.PolicyReader.getInt("cadence.ocr_boost_threshold", 3)
        private val BOOSTED_OCR_INTERVAL_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.boosted_ocr_interval_ms", 1000L)
        private val INTEREST_COOLDOWN_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.interest_cooldown_ms", 15_000L)
    }

    private var lastTranslateTime = 0L
    private var foreignTextTimestamps = mutableListOf<Long>()
    private var ocrBoosted = false
    private var lastInterestTime = 0L

    override fun shouldProcess(event: XRealEvent): Boolean = when (event) {
        is XRealEvent.PerceptionEvent.OcrDetected -> true
        is XRealEvent.PerceptionEvent.ObjectsDetected -> true
        else -> false
    }

    override fun evaluate(event: XRealEvent): RouterDecision? {
        val now = System.currentTimeMillis()

        when (event) {
            is XRealEvent.PerceptionEvent.OcrDetected -> {
                if (event.results.isEmpty()) return null

                // Combine all OCR text
                val fullText = event.results.joinToString(" ") { it.text }
                if (fullText.isBlank()) return null

                val lang = translationService.detectLanguage(fullText)

                // Check if it's foreign text (not Korean)
                if (lang != "ko" && lang != "unknown") {
                    // Cooldown check
                    if (now - lastTranslateTime < TRANSLATE_COOLDOWN_MS) return null
                    lastTranslateTime = now

                    // Track foreign text frequency
                    foreignTextTimestamps.add(now)
                    foreignTextTimestamps.removeAll { now - it > OCR_BOOST_WINDOW_MS }

                    // Check if we need OCR boost
                    val needsBoost = foreignTextTimestamps.size >= OCR_BOOST_THRESHOLD && !ocrBoosted

                    return RouterDecision(
                        routerId = id,
                        action = "TRANSLATE_FOREIGN_TEXT",
                        confidence = 0.8f,
                        reason = "Foreign text detected ($lang): ${fullText.take(50)}",
                        metadata = mapOf(
                            "text" to fullText,
                            "source_lang" to lang,
                            "needs_ocr_boost" to needsBoost
                        )
                    )
                }

                // If we were boosted but now seeing Korean again, reset
                if (lang == "ko" && ocrBoosted) {
                    ocrBoosted = false
                    foreignTextTimestamps.clear()
                    cadenceConfig.update {
                        copy(ocrIntervalMs = userStateTracker.state.value.defaultProfile().ocrIntervalMs)
                    }
                    Log.i(TAG, "OCR boost reset — Korean text detected")
                }
            }

            is XRealEvent.PerceptionEvent.ObjectsDetected -> {
                // Interest detection: EXPLORING state + high-confidence objects
                if (userStateTracker.state.value == UserState.EXPLORING) {
                    val interestingObjects = event.results.filter { it.confidence > 0.7f }
                    if (interestingObjects.isNotEmpty() && now - lastInterestTime > INTEREST_COOLDOWN_MS) {
                        lastInterestTime = now
                        val labels = interestingObjects.map { det -> det.label }
                        return RouterDecision(
                            routerId = id,
                            action = "INTEREST_DETECTED",
                            confidence = 0.7f,
                            reason = "Interesting objects while exploring: ${labels.joinToString()}",
                            metadata = mapOf(
                                "objects" to labels.joinToString(",")
                            )
                        )
                    }
                }
            }

            else -> {}
        }

        return null
    }

    override fun act(decision: RouterDecision) {
        when (decision.action) {
            "TRANSLATE_FOREIGN_TEXT" -> {
                val text = decision.metadata["text"] as? String ?: return
                val sourceLang = decision.metadata["source_lang"] as? String ?: return
                val needsBoost = decision.metadata["needs_ocr_boost"] as? Boolean ?: false

                // Async translation
                scope.launch {
                    val translated = translationService.translate(text, sourceLang) ?: return@launch
                    Log.i(TAG, "Translated ($sourceLang→ko): $translated")

                    // Show on HUD
                    eventBus.publish(XRealEvent.ActionRequest.ShowMessage("[$sourceLang] $translated"))
                    // Speak translation
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(translated))
                }

                // Boost OCR rate if frequent foreign text
                if (needsBoost) {
                    ocrBoosted = true
                    cadenceConfig.update {
                        copy(ocrIntervalMs = BOOSTED_OCR_INTERVAL_MS)
                    }
                    Log.i(TAG, "OCR boosted to ${BOOSTED_OCR_INTERVAL_MS}ms due to frequent foreign text")
                }
            }

            "INTEREST_DETECTED" -> {
                Log.i(TAG, "Interest while exploring: ${decision.reason}")
                eventBus.publish(XRealEvent.ActionRequest.TriggerSnapshot)
            }
        }
    }
}
