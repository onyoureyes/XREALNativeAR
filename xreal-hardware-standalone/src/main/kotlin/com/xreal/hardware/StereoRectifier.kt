package com.xreal.hardware

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * StereoRectifier: Applies undistortion and stereo rectification
 * using pre-computed calibration constants from stereo_calib_v2.npz.
 *
 * Calibration Data:
 *   - Stereo RMS Error: 1.0691
 *   - Baseline: 104.00 mm
 *   - Image Size: 640x480
 */
class StereoRectifier(private val width: Int = 640, private val height: Int = 480) {

    private val TAG = "StereoRectifier"

    // Pre-computed remap lookup tables (computed once in init)
    private lateinit var map1L: Mat
    private lateinit var map2L: Mat
    private lateinit var map1R: Mat
    private lateinit var map2R: Mat

    // Reusable Mats to reduce allocations in hot loop
    private val srcMatL = Mat(height, width, CvType.CV_8UC1)
    private val srcMatR = Mat(height, width, CvType.CV_8UC1)
    private val dstMatL = Mat()
    private val dstMatR = Mat()
    private val rgbaMatL = Mat()
    private val rgbaMatR = Mat()

    @Volatile var isInitialized = false
        private set

    init {
        try {
            initMaps()
            isInitialized = true
            Log.i(TAG, "StereoRectifier initialized (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
        }
    }

    private fun initMaps() {
        val K1 = Mat(3, 3, CvType.CV_64FC1)
        K1.put(0, 0,
            275.647911, 0.0, 342.885882,
            0.0, 279.095895, 229.477312,
            0.0, 0.0, 1.0
        )

        val D1 = Mat(1, 14, CvType.CV_64FC1)
        D1.put(0, 0,
            10.169100, -23.908586, -0.006794, 0.017876, 22.142107,
            10.185838, -25.115864, 23.039718,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        )

        val K2 = Mat(3, 3, CvType.CV_64FC1)
        K2.put(0, 0,
            280.242236, 0.0, 294.133958,
            0.0, 285.260280, 268.236666,
            0.0, 0.0, 1.0
        )

        val D2 = Mat(1, 14, CvType.CV_64FC1)
        D2.put(0, 0,
            9.836749, 9.640712, 0.029941, -0.016768, 24.522325,
            10.259084, 7.528988, 22.490228,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        )

        val R1 = Mat(3, 3, CvType.CV_64FC1)
        R1.put(0, 0,
            0.998033, 0.003729, 0.062574,
            -0.001283, 0.999235, -0.039098,
            -0.062672, 0.038941, 0.997274
        )

        val P1 = Mat(3, 4, CvType.CV_64FC1)
        P1.put(0, 0,
            350.706615, 0.0, 320.046310, 0.0,
            0.0, 350.706615, 255.721750, 0.0,
            0.0, 0.0, 1.0, 0.0
        )

        val R2 = Mat(3, 3, CvType.CV_64FC1)
        R2.put(0, 0,
            0.998642, 0.007241, -0.051593,
            -0.005220, 0.999218, 0.039186,
            0.051837, -0.038864, 0.997899
        )

        val P2 = Mat(3, 4, CvType.CV_64FC1)
        P2.put(0, 0,
            350.706615, 0.0, 320.046310, -36472.731392,
            0.0, 350.706615, 255.721750, 0.0,
            0.0, 0.0, 1.0, 0.0
        )

        val imageSize = Size(width.toDouble(), height.toDouble())

        map1L = Mat()
        map2L = Mat()
        Calib3d.initUndistortRectifyMap(K1, D1, R1, P1, imageSize, CvType.CV_16SC2, map1L, map2L)

        map1R = Mat()
        map2R = Mat()
        Calib3d.initUndistortRectifyMap(K2, D2, R2, P2, imageSize, CvType.CV_16SC2, map1R, map2R)

        Log.i(TAG, "Rectification maps computed: ${map1L.cols()}x${map1L.rows()}")

        K1.release(); D1.release(); K2.release(); D2.release()
        R1.release(); R2.release(); P1.release(); P2.release()
    }

    /**
     * Rectify a stereo frame pair.
     * Returns a side-by-side Bitmap (2*width x height) of rectified left+right.
     */
    fun rectifySideBySide(leftFrame: ByteArray, rightFrame: ByteArray): Bitmap? {
        if (!isInitialized) return null
        if (leftFrame.size != width * height || rightFrame.size != width * height) return null

        srcMatL.put(0, 0, leftFrame)
        srcMatR.put(0, 0, rightFrame)

        Imgproc.remap(srcMatL, dstMatL, map1L, map2L, Imgproc.INTER_LINEAR)
        Imgproc.remap(srcMatR, dstMatR, map1R, map2R, Imgproc.INTER_LINEAR)

        Imgproc.cvtColor(dstMatL, rgbaMatL, Imgproc.COLOR_GRAY2RGBA)
        Imgproc.cvtColor(dstMatR, rgbaMatR, Imgproc.COLOR_GRAY2RGBA)

        val sideBySide = Mat(height, width * 2, CvType.CV_8UC4)
        rgbaMatL.copyTo(sideBySide.submat(0, height, 0, width))
        rgbaMatR.copyTo(sideBySide.submat(0, height, width, width * 2))

        val bmp = Bitmap.createBitmap(width * 2, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(sideBySide, bmp)
        sideBySide.release()

        return bmp
    }

    /**
     * Rectify and return only the left eye as a Bitmap.
     */
    fun rectifyLeft(leftFrame: ByteArray): Bitmap? {
        if (!isInitialized) return null
        if (leftFrame.size != width * height) return null

        srcMatL.put(0, 0, leftFrame)
        Imgproc.remap(srcMatL, dstMatL, map1L, map2L, Imgproc.INTER_LINEAR)
        Imgproc.cvtColor(dstMatL, rgbaMatL, Imgproc.COLOR_GRAY2RGBA)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbaMatL, bmp)
        return bmp
    }

    /**
     * Rectify left eye frame into an existing ARGB_8888 Bitmap (zero allocation).
     * dst must be 640x480 ARGB_8888. Not thread-safe (reuses internal Mats).
     */
    fun rectifyLeftInto(leftFrame: ByteArray, dst: Bitmap): Boolean {
        if (!isInitialized) return false
        if (leftFrame.size != width * height) return false
        if (dst.width != width || dst.height != height) return false

        srcMatL.put(0, 0, leftFrame)
        Imgproc.remap(srcMatL, dstMatL, map1L, map2L, Imgproc.INTER_LINEAR)
        Imgproc.cvtColor(dstMatL, rgbaMatL, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rgbaMatL, dst)
        return true
    }

    fun release() {
        if (::map1L.isInitialized) map1L.release()
        if (::map2L.isInitialized) map2L.release()
        if (::map1R.isInitialized) map1R.release()
        if (::map2R.isInitialized) map2R.release()
        srcMatL.release(); srcMatR.release()
        dstMatL.release(); dstMatR.release()
        rgbaMatL.release(); rgbaMatR.release()
        Log.i(TAG, "StereoRectifier released")
    }
}
