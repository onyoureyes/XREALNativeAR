package com.xreal.nativear.learning

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DriveApiClient — Google Drive REST API v3 OkHttp 래퍼.
 *
 * ## SyncApiClient.kt 패턴 재사용 (Bearer token, JSONObject)
 *
 * ## 지원 기능
 * - `uploadFile()`: 멀티파트 업로드 (CSV, .tflite 등)
 * - `downloadFile()`: 바이너리 파일 다운로드
 * - `findLatestModel()`: 폴더에서 최신 .tflite 파일 검색
 * - `getOrCreateFolder()`: 폴더 ID 조회/생성
 */
class DriveApiClient(
    private val httpClient: OkHttpClient,
    private val authManager: GoogleDriveAuthManager
) {
    companion object {
        private const val TAG = "DriveApiClient"
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val MIME_CSV = "text/csv"
        private const val MIME_TFLITE = "application/octet-stream"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }

    data class DriveFileInfo(
        val fileId: String,
        val name: String,
        val modifiedTimeMs: Long
    )

    // ─── 파일 업로드 ───

    /**
     * 로컬 파일을 Drive 폴더에 멀티파트 업로드.
     * @param folderName Drive 폴더 이름 (없으면 생성)
     * @return 업로드된 파일 ID (실패 시 null)
     */
    fun uploadFile(file: File, folderName: String, mimeType: String = MIME_CSV): String? {
        val token = authManager.getValidAccessToken() ?: run {
            Log.w(TAG, "uploadFile: 인증 토큰 없음")
            return null
        }

        return try {
            // 1. 폴더 ID 확보
            val folderId = getOrCreateFolder(folderName) ?: run {
                Log.e(TAG, "폴더 생성 실패: $folderName")
                return null
            }

            // 2. 메타데이터 + 파일 바이너리 멀티파트 구성
            val metadata = JSONObject().apply {
                put("name", file.name)
                put("parents", JSONArray().put(folderId))
            }.toString()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "metadata", null,
                    metadata.toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .addFormDataPart(
                    "file", file.name,
                    file.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$DRIVE_UPLOAD/files?uploadType=multipart&fields=id,name")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "업로드 실패 (${response.code}): $body")
                return null
            }

            val fileId = JSONObject(body ?: "{}").optString("id")
            Log.i(TAG, "Drive 업로드 완료: ${file.name} → $fileId")
            fileId.ifEmpty { null }

        } catch (e: Exception) {
            Log.e(TAG, "uploadFile 예외: ${e.message}")
            null
        }
    }

    // ─── 파일 다운로드 ───

    /**
     * Drive 파일을 로컬 경로로 다운로드.
     * @return true = 성공
     */
    fun downloadFile(fileId: String, destFile: File): Boolean {
        val token = authManager.getValidAccessToken() ?: run {
            Log.w(TAG, "downloadFile: 인증 토큰 없음")
            return false
        }

        return try {
            val request = Request.Builder()
                .url("$DRIVE_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "다운로드 실패 (${response.code}): ${response.body?.string()}")
                return false
            }

            destFile.parentFile?.mkdirs()
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Drive 다운로드 완료: $fileId → ${destFile.absolutePath} (${destFile.length()} bytes)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "downloadFile 예외: ${e.message}")
            false
        }
    }

    // ─── 최신 모델 검색 ───

    /**
     * 폴더에서 prefix로 시작하는 가장 최신 파일을 검색.
     * 예: findLatestModel("xreal_models", "routine_classifier")
     */
    fun findLatestModel(folderName: String, prefix: String): DriveFileInfo? {
        val token = authManager.getValidAccessToken() ?: run {
            Log.w(TAG, "findLatestModel: 인증 토큰 없음")
            return null
        }

        return try {
            val folderId = getOrCreateFolder(folderName) ?: return null

            // Drive 쿼리: 폴더 내 prefix 파일, 수정 시각 내림차순
            val query = "'$folderId' in parents and name contains '$prefix' and trashed = false"
            val url = "$DRIVE_BASE/files?q=${query.urlEncode()}" +
                    "&orderBy=modifiedTime desc" +
                    "&fields=files(id,name,modifiedTime)" +
                    "&pageSize=1"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null

            if (!response.isSuccessful) {
                Log.e(TAG, "파일 검색 실패 (${response.code}): $body")
                return null
            }

            val files = JSONObject(body).getJSONArray("files")
            if (files.length() == 0) {
                Log.d(TAG, "모델 파일 없음: $folderName/$prefix*")
                return null
            }

            val fileJson = files.getJSONObject(0)
            val modifiedTime = parseRfc3339(fileJson.optString("modifiedTime"))
            DriveFileInfo(
                fileId = fileJson.getString("id"),
                name = fileJson.getString("name"),
                modifiedTimeMs = modifiedTime
            ).also { Log.d(TAG, "최신 모델 발견: ${it.name} (${it.fileId})") }

        } catch (e: Exception) {
            Log.e(TAG, "findLatestModel 예외: ${e.message}")
            null
        }
    }

    // ─── 폴더 관리 ───

    /**
     * 폴더 조회 또는 생성.
     * @return folderId (실패 시 null)
     */
    fun getOrCreateFolder(name: String): String? {
        val token = authManager.getValidAccessToken() ?: return null

        return try {
            // 1. 기존 폴더 검색
            val query = "name = '$name' and mimeType = '$MIME_FOLDER' and trashed = false"
            val searchUrl = "$DRIVE_BASE/files?q=${query.urlEncode()}&fields=files(id,name)&pageSize=1"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val searchResponse = httpClient.newCall(searchRequest).execute()
            val searchBody = searchResponse.body?.string() ?: return null

            if (searchResponse.isSuccessful) {
                val files = JSONObject(searchBody).getJSONArray("files")
                if (files.length() > 0) {
                    val folderId = files.getJSONObject(0).getString("id")
                    Log.d(TAG, "기존 폴더 사용: $name ($folderId)")
                    return folderId
                }
            }

            // 2. 폴더 생성
            val metadata = JSONObject().apply {
                put("name", name)
                put("mimeType", MIME_FOLDER)
            }.toString()

            val createRequest = Request.Builder()
                .url("$DRIVE_BASE/files?fields=id")
                .addHeader("Authorization", "Bearer $token")
                .post(metadata.toRequestBody("application/json".toMediaType()))
                .build()

            val createResponse = httpClient.newCall(createRequest).execute()
            val createBody = createResponse.body?.string() ?: return null

            if (!createResponse.isSuccessful) {
                Log.e(TAG, "폴더 생성 실패 (${createResponse.code}): $createBody")
                return null
            }

            val folderId = JSONObject(createBody).getString("id")
            Log.i(TAG, "Drive 폴더 생성: $name ($folderId)")
            folderId

        } catch (e: Exception) {
            Log.e(TAG, "getOrCreateFolder 예외: ${e.message}")
            null
        }
    }

    // ─── 유틸리티 ───

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    /**
     * RFC 3339 날짜 → epoch millis.
     * 예: "2026-03-03T12:34:56.000Z"
     */
    private fun parseRfc3339(dateStr: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                // 밀리초 없는 형식
                val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf2.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf2.parse(dateStr)?.time ?: 0L
            } catch (e2: Exception) { 0L }
        }
    }
}
