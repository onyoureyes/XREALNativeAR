package com.xreal.nativear

/**
 * PoseKeypoint — 포즈 추정 키포인트.
 * 원래 PoseEstimationModel.Keypoint로 중첩되어 있었으나 모듈 분리를 위해 독립.
 */
data class PoseKeypoint(val id: Int, val x: Float, val y: Float, val score: Float)
