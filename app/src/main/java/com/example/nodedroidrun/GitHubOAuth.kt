package com.example.nodedroidrun

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GitHubOAuth {

    private const val AUTH_URL = "https://github.com/login/oauth/authorize"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val REDIRECT_URI = "nodeapp://oauth/github"
    private const val SCOPE = "repo"

    private const val PREF_NAME = "github_oauth"
    private const val KEY_TOKEN = "access_token"
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

    fun handleCallback(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "nodeapp" || uri.host != "oauth") return false

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        if (state != null && state != currentState) {
            currentState = null
            return false
        }
        currentState = null

        if (code.isNullOrEmpty()) return false

        exchangeCodeForToken(context, code)
        return true
    }

    private fun exchangeCodeForToken(context: Context, code: String) {
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        val clientSecret = BuildConfig.GITHUB_CLIENT_SECRET

        val body = "client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=$REDIRECT_URI"

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
        }
    }

    private fun saveToken(context: Context, token: String) {
        getSecurePrefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(context: Context): String? {
        return getSecurePrefs(context).getString(KEY_TOKEN, null)
    }

    fun isLoggedIn(context: Context): Boolean {
        return getToken(context) != null
    }

    fun logout(context: Context) {
        getSecurePrefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
