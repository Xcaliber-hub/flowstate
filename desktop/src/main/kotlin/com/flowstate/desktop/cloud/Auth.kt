package com.flowstate.desktop.cloud

import com.flowstate.desktop.FlowstateConfig
import com.flowstate.desktop.store.AppDirs
import com.flowstate.desktop.store.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The signed-in Firebase account, persisted in %APPDATA%\Flowstate\auth.json. */
@Serializable
data class AuthSession(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val idToken: String = "",
    val refreshToken: String = "",
    /** Epoch ms when [idToken] expires. */
    val expiresAt: Long = 0L,
)

/**
 * Firebase Authentication over REST: email/password sign-in, sign-up and password reset through the
 * Identity Toolkit API, ID-token refresh through the Secure Token API. The same accounts as Android.
 */
class AuthClient {
    private val key = FlowstateConfig.FIREBASE_API_KEY
    private val file = JsonFile(AppDirs.file("auth.json"), AuthSession.serializer()) { AuthSession() }
    private val mutex = Mutex()
    @Volatile var session: AuthSession? = file.load().takeIf { it.uid.isNotBlank() && it.refreshToken.isNotBlank() }
        private set

    val configured: Boolean get() = key.isNotBlank() && FlowstateConfig.FIREBASE_PROJECT_ID.isNotBlank()

    suspend fun signIn(email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("email", email.trim()); put("password", password); put("returnSecureToken", true) }.toString()
        val res = RestJson.parseToJsonElement(call("accounts:signInWithPassword", body)).jsonObject
        accept(res.str("localId")!!, res.str("email") ?: email.trim(), res.str("displayName").orEmpty(), res.str("idToken")!!, res.str("refreshToken")!!, res.lng("expiresIn") ?: 3600)
    }

    suspend fun signUp(name: String, email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("email", email.trim()); put("password", password); put("returnSecureToken", true) }.toString()
        val res = RestJson.parseToJsonElement(call("accounts:signUp", body)).jsonObject
        val idToken = res.str("idToken")!!
        // Same as Android: the display name is a fallback; the profile doc users/{uid} is authoritative.
        runCatching { call("accounts:update", buildJsonObject { put("idToken", idToken); put("displayName", name); put("returnSecureToken", false) }.toString()) }
        accept(res.str("localId")!!, res.str("email") ?: email.trim(), name, idToken, res.str("refreshToken")!!, res.lng("expiresIn") ?: 3600)
    }

    suspend fun sendPasswordReset(email: String) = withContext(Dispatchers.IO) {
        call("accounts:sendOobCode", buildJsonObject { put("requestType", "PASSWORD_RESET"); put("email", email.trim()) }.toString())
        Unit
    }

    fun signOut() {
        session = null
        file.save(AuthSession())
    }

    /**
     * A valid ID token, refreshed when it has less than five minutes left or when [forceRefresh] is set
     * (a request was just answered 401/403 with the cached token).
     */
    suspend fun idToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val s = session ?: throw CloudException(401, "UNAUTHENTICATED", "Sign in first.")
        if (s.uid.isBlank() || s.refreshToken.isBlank()) throw CloudException(401, "UNAUTHENTICATED", "Sign in first.")
        if (!forceRefresh && s.idToken.isNotBlank() && System.currentTimeMillis() < s.expiresAt - 5 * 60_000L) return@withLock s.idToken
        withContext(Dispatchers.IO) {
            val res = try {
                RestJson.parseToJsonElement(
                    Http.send("POST", "https://securetoken.googleapis.com/v1/token?key=$key",
                        Http.form("grant_type" to "refresh_token", "refresh_token" to s.refreshToken), contentType = "application/x-www-form-urlencoded")
                ).jsonObject
            } catch (e: CloudException) {
                // Only a refresh token the server has definitely rejected (password changed, account disabled
                // or deleted) signs this PC out. Any other 4xx (a proxy, a quota hiccup, a captive portal) is
                // treated like a network error: the session is kept and the next poll tries again.
                if (isRevoked(e)) signOut()
                throw e
            }
            val fresh = res.str("id_token")?.takeIf { it.isNotBlank() }
                ?: throw CloudException(502, "MALFORMED_RESPONSE", "Token refresh returned no ID token.")
            val next = s.copy(
                idToken = fresh, refreshToken = res.str("refresh_token")?.takeIf { it.isNotBlank() } ?: s.refreshToken,
                expiresAt = System.currentTimeMillis() + (res.lng("expires_in") ?: 3600) * 1000,
            )
            session = next
            file.save(next)
            next.idToken
        }
    }

    fun rememberName(name: String) {
        val s = session ?: return
        session = s.copy(displayName = name).also { file.save(it) }
    }

    private fun accept(uid: String, email: String, name: String, idToken: String, refresh: String, expiresIn: Long): AuthSession {
        val s = AuthSession(uid, email, name, idToken, refresh, System.currentTimeMillis() + expiresIn * 1000)
        session = s
        file.save(s)
        return s
    }

    private fun call(method: String, body: String): String =
        Http.send("POST", "https://identitytoolkit.googleapis.com/v1/$method?key=$key", body)

    companion object {
        private val REVOKED = listOf("TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_DISABLED", "USER_NOT_FOUND")

        /** True when the Secure Token API rejected the refresh token itself, so signing in again is the only fix. */
        fun isRevoked(e: CloudException): Boolean =
            e.status in 400..403 && REVOKED.any { e.message.orEmpty().startsWith(it) || e.reason.startsWith(it) }

        /** Identity Toolkit error codes to the messages Android shows. */
        fun friendly(e: Throwable): String {
            val reason = (e as? CloudException)?.message.orEmpty()
            return when {
                reason.startsWith("INVALID_LOGIN_CREDENTIALS") || reason.startsWith("INVALID_PASSWORD") || reason.startsWith("EMAIL_NOT_FOUND") ->
                    "The email or password is incorrect."
                reason.startsWith("EMAIL_EXISTS") -> "An account already exists for this email. Sign in instead."
                reason.startsWith("WEAK_PASSWORD") -> "Password must be at least 8 characters."
                reason.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Wait a few minutes and try again."
                reason.startsWith("USER_DISABLED") -> "This account has been disabled."
                reason.contains("API key not valid") || reason.contains("blocked") ->
                    "Firebase rejected this app's API key. In Google Cloud Console, allow the key for desktop use (Application restrictions: None)."
                e is CloudException -> e.message ?: "Something went wrong. Please try again."
                e is java.io.IOException -> "Network unavailable. Check your connection and try again."
                else -> e.message ?: "Something went wrong. Please try again."
            }
        }
    }
}
