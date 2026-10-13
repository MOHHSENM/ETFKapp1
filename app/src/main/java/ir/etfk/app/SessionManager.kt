package ir.etfk.app

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * کوکی‌ها را به‌صورت پایدار در SharedPreferences ذخیره می‌کند
 * تا در اجراهای بعدی هم کاربر لاگین بماند.
 * همچنین اعتبارنامه (کد ملی + رمز) را برای "مرا به خاطر بسپار" ذخیره می‌کند.
 */
class SessionManager(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val cookies = mutableListOf<Cookie>()

    init { load() }

    override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) {
        cookies.removeAll { c -> cookieList.any { it.name == c.name && it.domain == c.domain } }
        cookies.addAll(cookieList)
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }

    fun clear() {
        cookies.clear()
        prefs.edit().remove("cookies").apply()
    }

    fun isLoggedIn(): Boolean = cookies.isNotEmpty()

    private fun persist() {
        val encoded = cookies.joinToString("\n") { it.toString() }
        prefs.edit().putString("cookies", encoded).apply()
    }

    private fun load() {
        val raw = prefs.getString("cookies", null) ?: return
        raw.split("\n").forEach { line ->
            parseCookie(line)?.let { cookies.add(it) }
        }
    }

    private fun parseCookie(line: String): Cookie? = try {
        Cookie.parse("https://etfk.ir".toHttpUrl(), line)
    } catch (e: Exception) { null }

    // ============ ذخیره اعتبارنامه ============
    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString("saved_username", username)
            .putString("saved_password", password)
            .putBoolean("remember", true)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove("saved_username")
            .remove("saved_password")
            .putBoolean("remember", false)
            .apply()
    }

    fun getSavedUsername(): String = prefs.getString("saved_username", "") ?: ""
    fun getSavedPassword(): String = prefs.getString("saved_password", "") ?: ""
    fun isRememberChecked(): Boolean = prefs.getBoolean("remember", false)
}