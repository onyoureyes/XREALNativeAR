package com.xreal.hardware

/**
 * Identifies Nreal Light USB device types by VID/PID.
 */
enum class NrealDeviceType(val vid: Int, val pid: Int, val description: String) {
    MCU(0x0486, 0x573C, "STM32 MCU (display, buttons, heartbeat)"),
    OV580(0x05A9, 0x0680, "OV580 Camera DSP (stereo camera + IMU)"),
    AUDIO(0x0BDA, 0x4B77, "Realtek Audio"),
    RGB_CAMERA(0x0817, 0x0909, "Nreal Light RGB Center Camera");

    companion object {
        fun identify(vid: Int): NrealDeviceType? = entries.find { it.vid == vid }
    }
}
