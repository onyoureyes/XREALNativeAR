package com.xreal.nativear

/**
 * FaceInfo: Detected face data with optional person identification.
 * Coordinates are in pixel space of the source bitmap.
 */
data class FaceInfo(
    val x: Float,              // center x (pixels)
    val y: Float,              // center y (pixels)
    val width: Float,          // bbox width (pixels)
    val height: Float,         // bbox height (pixels)
    val confidence: Float,     // detection confidence
    val personId: Long? = null,      // null if unknown/unmatched
    val personName: String? = null,  // null if unlabeled
    val embedding: ByteArray? = null,  // 192d face embedding
    val expression: String? = null,    // Phase 2: facial expression
    val expressionScore: Float? = null
)
