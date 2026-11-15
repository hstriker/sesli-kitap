package com.seslikitap.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.seslikitap.Kayit
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Kullanıcının Drive iznini yeniden onaylaması gerekiyor (ekrandan "Bağlan"a basmalı). */
class DriveAuthException(message: String) : IOException(message)

sealed interface AuthOutcome {
    data class Ok(val token: String) : AuthOutcome
    /** Onay ekranı gösterilmeli. */
    data class NeedsConsent(val intent: PendingIntent) : AuthOutcome
    data class Failed(val message: String) : AuthOutcome
}

/**
 * Google Drive salt okuma izni. Android OAuth istemcisi (paket adı + SHA-1) Google Cloud'da
 * tanımlı olmalı; uygulamada gizli anahtar bulunmaz.
 */
class DriveAuth(private val context: Context) {

    private val client get() = Identity.getAuthorizationClient(context)
    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    /** Arka planda (ekransız) yetki ister; onay gerekiyorsa NeedsConsent döner. Ana iş parçacığında çağırmayın. */
    fun authorizeBlocking(): AuthOutcome {
        val t0 = System.currentTimeMillis()
        val r = try {
            handle(Tasks.await(client.authorize(request), 30, TimeUnit.SECONDS))
        } catch (e: Exception) {
            Kayit.error("Google yetki hatası", e)
            AuthOutcome.Failed(explain(e))
        }
        Kayit.log("Google yetki: ${r.javaClass.simpleName} (${System.currentTimeMillis() - t0} ms)")
        return r
    }

    /** Onay ekranından dönen sonuç. */
    fun handleConsentResult(data: Intent?): AuthOutcome = try {
        handle(client.getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        AuthOutcome.Failed(explain(e))
    }

    private fun handle(r: AuthorizationResult): AuthOutcome {
        if (r.hasResolution()) {
            val pi = r.pendingIntent ?: return AuthOutcome.Failed("Google onay ekranı açılamadı")
            return AuthOutcome.NeedsConsent(pi)
        }
        val t = r.accessToken ?: return AuthOutcome.Failed("Google erişim anahtarı alınamadı")
        token = t
        tokenAt = System.currentTimeMillis()
        return AuthOutcome.Ok(t)
    }

    /**
     * Drive isteği için geçerli erişim anahtarı. [refresh]: sunucu anahtarı reddettiyse (401) eskisini atıp yenisini al.
     * Kullanıcı onayı gerekirse [DriveAuthException] fırlatır.
     */
    fun tokenBlocking(refresh: Boolean = false): String {
        val current = token
        if (!refresh && current != null && System.currentTimeMillis() - tokenAt < TOKEN_LIFETIME_MS) return current
        if (refresh && current != null) {
            try { GoogleAuthUtil.clearToken(context, current) } catch (_: Exception) {}
            token = null
        }
        return when (val r = authorizeBlocking()) {
            is AuthOutcome.Ok -> r.token
            is AuthOutcome.NeedsConsent -> throw DriveAuthException(
                "Google Drive bağlantısının yenilenmesi gerekiyor. Klasörler ekranından \"Google Drive'a bağlan\"a dokunun."
            )
            is AuthOutcome.Failed -> throw DriveAuthException(r.message)
        }
    }

    private fun explain(e: Exception): String {
        val api = (e as? ApiException) ?: (e.cause as? ApiException)
        return when (api?.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Google Cloud ayarı eksik ya da hatalı (kod 10). Android istemcisindeki paket adı (com.seslikitap) " +
                    "ve SHA-1 parmak izini kontrol edin."
            CommonStatusCodes.NETWORK_ERROR -> "İnternet bağlantısı yok"
            CommonStatusCodes.CANCELED -> "Google girişi iptal edildi"
            null -> e.message ?: "Google bağlantı hatası"
            else -> "Google bağlantı hatası (kod ${api?.statusCode}): ${api?.message}"
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_LIFETIME_MS = 45 * 60 * 1000L
    }
}
