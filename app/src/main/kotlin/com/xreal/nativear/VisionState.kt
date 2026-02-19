package com.xreal.nativear

/**
 * VisionMode: Strict State Machine for Vision Service.
 * Defines the lifecycle of the AI Vision Engine.
 */
sealed interface VisionMode {
    // 1. All sensors OFF. AI Models Released. ReuseBitmap Recycled.
    data object Idle : VisionMode

    // 2. Sensors ON. Low FPS (1Hz). Models Warmed up.
    data object Standby : VisionMode

    // 3. Sensors ON. High FPS (Target). Processing Loop Active.
    data object Active : VisionMode

    // 4. Sensors ON (maybe). Processing Blocked.
    data object Frozen : VisionMode
}
