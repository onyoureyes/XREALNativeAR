package com.xreal.hardware

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.calib3d.StereoSGBM
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class StereoDepthEngine(private val width: Int = 640, private val height: Int = 480) {

    private val sgbm: StereoSGBM

    init {
        // Initialize StereoSGBM algorithm with default parameters tailored for 640x480 fisheye
        // Since we don't have perfect rectification yet, block matching might be noisy.
        val blockSize = 5
        sgbm = StereoSGBM.create(
            0,             // minDisparity
            64,            // numDisparities (must be a multiple of 16)
            blockSize,     // blockSize (SAD window size)
            8 * blockSize * blockSize,  // P1 parameter controlling disparity smoothness
            32 * blockSize * blockSize, // P2 parameter controlling disparity smoothness
            1,             // disp12MaxDiff
            63,            // preFilterCap
            15,            // uniquenessRatio
            100,           // speckleWindowSize (removes small false matches)
            32,            // speckleRange
            StereoSGBM.MODE_SGBM // Full SGBM algorithm
        )
    }

    /**
     * Computes the depth heatmap from raw grayscale stereo byte arrays.
     * CAUTION: This operation is extremely CPU-heavy. Run it on a background thread.
     */
    fun computeDisparityMap(leftFrame: ByteArray, rightFrame: ByteArray): Bitmap? {
        if (leftFrame.size != width * height || rightFrame.size != width * height) {
            Log.e("StereoDepthEngine", "Frame sizes do not match expected bounds (640x480)")
            return null
        }

        // 1. Wrap raw bytes into OpenCV Matrices (8-bit Unsigned, 1 Channel)
        val leftMat = Mat(height, width, CvType.CV_8UC1)
        val rightMat = Mat(height, width, CvType.CV_8UC1)

        leftMat.put(0, 0, leftFrame)
        rightMat.put(0, 0, rightFrame)

        // 2. Compute disparity (output is 16-bit signed, CV_16S)
        val disparityMat = Mat()
        sgbm.compute(leftMat, rightMat, disparityMat)

        // 3. Normalize the 16-bit map to 8-bit [0, 255] for visualization
        val disp8 = Mat()
        Core.normalize(disparityMat, disp8, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC1)

        // 4. Apply Jet pseudo-color mapping (Red = Close, Blue = Far)
        val colorMat = Mat()
        Imgproc.applyColorMap(disp8, colorMat, Imgproc.COLORMAP_JET)

        // 5. Convert to Android Bitmap (OpenCV color maps output BGR)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rgbaMat = Mat()
        Imgproc.cvtColor(colorMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(rgbaMat, bmp)

        // 6. Manual garbage collection (critical for continuous hot pipeline)
        leftMat.release()
        rightMat.release()
        disparityMat.release()
        disp8.release()
        colorMat.release()
        rgbaMat.release()

        return bmp
    }
}
