package com.xreal.nativear.learning

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * GoogleDriveAuthManager — Android OAuth2 클라이언트 기반 Drive 인증.
 *
 * ## Android 타입 클라이언트 (client_secret 불필요)
 * - 패키지명 + SHA-1 지문으로 앱 인증 (Google Cloud Console에 등록된 "Jarvis" 클라이언트)
 * - GoogleAuthUtil.getToken()이 토큰 만료 자동 처리
 * - requestServerAuthCode 미사용 (Web 애플리케이션 타입 전용)
 *
 * ## 흐름
 * 1. buildSignInIntent() → GoogleSignIn 화면 (Drive.FILE 권한 포함)
 * 2. handleSignInResult() → 로그인 상태 저장
 * 3. getValidAccessToken() → GoogleAuthUtil이 캐싱 + 만료 시 자동 갱신
 *    ※ 블로킹 호출 — CoroutineWorker(IO) 또는 withContext(IO)에서만 호출
 */
class GoogleDriveAuthManager(
    private val context: Context,
    private val httpClient: OkHttpClient  // DriveApiClient와 공유 (DI 호환성 유지)
) {
    companion object {
        private const val TAG = "GoogleDriveAuthManager"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val PREFS_NAME = "drive_auth_prefs"
        private const val KEY_SIGNED_IN = "signed_in"
    }

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences 생성 실패: ${e.message}")
            context.getSharedPreferences("drive_auth_fallback", Context.MODE_PRIVATE)
        }
    }

    // ─── 인증 상태 확인 ───

    /**
     * Drive.FILE 스코프가 부여된 로그인 계정이 있으면 true.
     */
    fun isAuthenticated(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE))
    }

    /**
     * Android 타입: 로그인 계정이 있으면 GoogleAuthUtil이 자동 갱신.
     * isAuthenticated()와 동일.
     */
    fun isRefreshable(): Boolean = isAuthenticated()

    // ─── Google Sign-In ───

    /**
     * Activity에서 호출: Drive.FILE 스코프 포함 로그인 화면.
     * requestCode = DriveTrainingScheduler.RC_SIGN_IN (9001)
     */
    fun buildSignInIntent(): Intent {
        return GoogleSignIn.getClient(context, buildGso()).signInIntent
    }

    /**
     * onActivityResult(RC_SIGN_IN)에서 호출.
     * @return true = 로그인 성공 + Drive 권한 획득
     */
    fun handleSignInResult(data: Intent?): Boolean {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
                ?: return false
            prefs.edit().putBoolean(KEY_SIGNED_IN, true).apply()
            Log.i(TAG, "Drive 로그인 성공: ${account.email}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in 실패: ${e.message}")
            false
        }
    }

    /**
     * 유효한 access_token 반환.
     * GoogleAuthUtil이 캐시 확인 → 만료 시 자동 갱신.
     *
     * ※ 블로킹 I/O — CoroutineWorker 또는 withContext(Dispatchers.IO) 에서만 호출.
     */
    fun getValidAccessToken(): String? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: run {
            Log.w(TAG, "로그인된 계정 없음 — Drive 로그인 필요")
            return null
        }
        val androidAccount = account.account ?: run {
            Log.w(TAG, "Android Account 정보 없음")
            return null
        }
        return try {
            val token = GoogleAuthUtil.getToken(
                context,
                androidAccount,
                "oauth2:$DRIVE_FILE_SCOPE"
            )
            Log.d(TAG, "access_token 획득 성공")
            token
        } catch (e: GoogleAuthException) {
            Log.e(TAG, "GoogleAuthUtil 인증 오류 — 재로그인 필요: ${e.message}")
            null
        } catch (e: IOException) {
            Log.e(TAG, "GoogleAuthUtil 네트워크 오류: ${e.message}")
            null
        }
    }

    fun signOut() {
        GoogleSignIn.getClient(context, buildGso()).signOut()
        prefs.edit().putBoolean(KEY_SIGNED_IN, false).apply()
        Log.i(TAG, "Drive 로그아웃 완료")
    }

    /**
     * Android OAuth2 클라이언트 — client_secret 불필요.
     * Web 애플리케이션 타입과의 API 호환성 유지용 (실질적 동작 없음).
     */
    fun setClientCredentials(clientId: String, clientSecret: String) {
        Log.i(TAG, "Android OAuth2 타입 — client_secret 불필요 (SHA-1 지문 인증)")
    }

    // ─── 내부 ───

    private fun buildGso() = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_FILE_SCOPE))
        .build()
}
