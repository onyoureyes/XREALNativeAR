package com.xreal.nativear.core

import java.nio.ByteBuffer

/**
 * Android Context.assets 추상화 — 모델 파일 로딩의 Android 프레임워크 종속 제거.
 *
 * 프로덕션: AndroidAssetLoader (Context.assets 위임) — app 모듈
 * 테스트:   TestAssetLoader (Map<String, ByteArray>) — 테스트 전용
 *
 * ## 설정
 * ```kotlin
 * // AppModule.kt:
 * single<IAssetLoader> { AndroidAssetLoader(androidContext()) }
 * ```
 */
interface IAssetLoader {

    /**
     * 모델 파일을 ByteBuffer로 로드.
     * TFLite Interpreter 생성에 사용: `Interpreter(assetLoader.loadModelBuffer("model.tflite"))`
     */
    fun loadModelBuffer(assetName: String): ByteBuffer

    /**
     * 텍스트 파일 내용을 String으로 로드 (레이블 파일 등).
     */
    fun loadTextAsset(assetName: String): String

    /**
     * 에셋 존재 여부 확인.
     */
    fun assetExists(assetName: String): Boolean
}
