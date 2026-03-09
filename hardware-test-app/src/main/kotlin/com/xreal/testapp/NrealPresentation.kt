package com.xreal.testapp

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * NrealPresentation: Renders AR HUD directly on the Nreal Light external display.
 *
 * On the waveguide display:
 *   - Black (#000000) = fully transparent → real world visible
 *   - Any bright color = visible floating overlay
 *
 * This renders at native 1920x1080 on the Nreal Light,
 * independent of the Galaxy Fold phone screen.
 */
class NrealPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var tvStatus: TextView
        private set
    lateinit var tvFps: TextView
        private set
    lateinit var tvOrientation: TextView
        private set
    lateinit var tvCenterLabel: TextView
        private set
    lateinit var crosshair: View
        private set
    lateinit var anchorContainer: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_ar_hud)

        tvStatus = findViewById(R.id.hudTvStatus)
        tvFps = findViewById(R.id.hudTvFps)
        tvOrientation = findViewById(R.id.hudTvOrientation)
        tvCenterLabel = findViewById(R.id.hudTvCenterLabel)
        crosshair = findViewById(R.id.hudCrosshair)
        anchorContainer = findViewById(R.id.hudAnchorContainer)
    }

    /** Show a recognized label at HUD center, auto-hides after 3s */
    fun showLabel(text: String) {
        tvCenterLabel.text = text
        tvCenterLabel.visibility = View.VISIBLE
        tvCenterLabel.removeCallbacks(hideRunnable)
        tvCenterLabel.postDelayed(hideRunnable, 3000)
    }

    fun updateOrientation(roll: Double, pitch: Double, yaw: Double) {
        tvOrientation.text = "R:%.0f° P:%.0f° Y:%.0f°".format(roll, pitch, yaw)
    }

    fun updateFps(fps: Double) {
        tvFps.text = "CAM: %.0f FPS".format(fps)
    }

    private val hideRunnable = Runnable {
        tvCenterLabel.visibility = View.GONE
    }
}
