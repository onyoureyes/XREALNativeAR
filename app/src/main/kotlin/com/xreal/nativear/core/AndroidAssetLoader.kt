package com.xreal.nativear.core

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Android Context.assets를 이용한 IAssetLoader 구현.
 * AppModule에서 single<IAssetLoader>으로 등록.
 */
class AndroidAssetLoader(private val context: Context) : IAssetLoader {

    override fun loadModelBuffer(assetName: String): ByteBuffer {
        val fd = context.assets.openFd(assetName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    override fun loadTextAsset(assetName: String): String {
        return context.assets.open(assetName).bufferedReader().use { it.readText() }
    }

    override fun assetExists(assetName: String): Boolean {
        return try {
            context.assets.openFd(assetName).close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
