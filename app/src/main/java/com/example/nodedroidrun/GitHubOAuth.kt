package com.example.nodedroidrun

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GitHubOAuth {

    private const val AUTH_URL = "https://github.com/login/oauth/authorize"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val USER_URL = "https://api.github.com/user"
    private const val REDIRECT_URI = "nodeapp://oauth/github"
    private const val SCOPE = "repo"

    private const val PREF_NAME = "github_oauth"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_USER = "user_login"
    private const val KEY_NAME = "user_name"
    private const val KEY_EMAIL = "user_email"
    private const val KEY_STATE = "oauth_state"

    private var currentState: String? = null

    private fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREF_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun startLogin(context: Context) {
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        if (clientId.isEmpty()) {
            throw IllegalStateException("GITHUB_CLIENT_ID não configurado em local.properties")
        }

        currentState = java.util.UUID.randomUUID().toString()

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", currentState)
            .build()

        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTabsIntent.launchUrl(context, authUri)
    }

    fun extractCode(uri: Uri): String? {
        if (uri.scheme != "nodeapp" || uri.host != "oauth") return null

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        if (state != null && state != currentState) {
            currentState = null
            return null
        }
        currentState = null

        return if (code.isNullOrEmpty()) null else code
    }

    suspend fun exchangeCode(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        val clientSecret = BuildConfig.GITHUB_CLIENT_SECRET

        val body = "client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=$REDIRECT_URI"

        try {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = org.json.JSONObject(response)
            val token = if (json.has("access_token")) json.getString("access_token") else null
            if (token != null) {
                saveToken(context, token)
                fetchAndSaveUser(context, token)
                return@withContext true
            }
        } catch (_: Exception) { }
        false
    }

    private fun fetchAndSaveUser(context: Context, token: String) {
        try {
            val url = URL(USER_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = org.json.JSONObject(response)
            val login = if (json.has("login")) json.getString("login") else null
            val name  = if (json.has("name") && !json.isNull("name")) json.getString("name") else null
            val email = if (json.has("email") && !json.isNull("email")) json.getString("email") else null

            val editor = getSecurePrefs(context).edit()
            if (login != null) editor.putString(KEY_USER, login)
            if (name != null)  editor.putString(KEY_NAME, name)
            if (email != null) editor.putString(KEY_EMAIL, email)
            editor.apply()
        } catch (_: Exception) { }
    }

    private fun saveToken(context: Context, token: String) {
        getSecurePrefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(context: Context): String? {
        return getSecurePrefs(context).getString(KEY_TOKEN, null)
    }

    fun getUserLogin(context: Context): String? {
        return getSecurePrefs(context).getString(KEY_USER, null)
    }

    fun getUserName(context: Context): String? {
        return getSecurePrefs(context).getString(KEY_NAME, null)
            ?: getUserLogin(context)
    }

    fun getUserEmail(context: Context): String? {
        return getSecurePrefs(context).getString(KEY_EMAIL, null)
    }

    fun getGitEmail(context: Context): String {
        val login = getUserLogin(context) ?: return "nodeapp@localhost"
        return getUserEmail(context)
            ?: "${login}@users.noreply.github.com"
    }

    fun isLoggedIn(context: Context): Boolean {
        return getToken(context) != null
    }

    fun logout(context: Context) {
        getSecurePrefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .remove(KEY_NAME)
            .remove(KEY_EMAIL)
            .apply()
    }
}
