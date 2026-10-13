package ir.etfk.app

import okhttp3.*
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class ApiClient(private val session: SessionManager) {

    private val client = OkHttpClient.Builder()
        .cookieJar(session)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val baseUrl = "https://etfk.ir"
    private val loginPath = "/trusting_sms/f_login.aspx"

    /**
     * ورود دو مرحله‌ای مخصوص ASP.NET Web Forms:
     *  1. GET صفحه لاگین برای دریافت __VIEWSTATE و سایر توکن‌ها
     *  2. POST فرم با تمام فیلدهای مخفی
     */
    fun login(username: String, password: String): Result<LoginResult> {
        return try {
            // مرحله ۱: دریافت توکن‌های مخفی
            val loginPage = getLoginPage()
                ?: return Result.success(LoginResult.Error("خطا در دریافت صفحه ورود"))

            // مرحله ۲: ارسال فرم
            val form = FormBody.Builder()
                .add("__VIEWSTATE", loginPage.viewState)
                .add("__VIEWSTATEGENERATOR", loginPage.viewStateGenerator)
                .add("__EVENTVALIDATION", loginPage.eventValidation)
                .add("__EVENTTARGET", "")
                .add("__EVENTARGUMENT", "")
                .add("ed_fusername", username)
                .add("ed_passf", password)
                .add("bt_login", "ورود")
                .build()

            val request = Request.Builder()
                .url("$baseUrl$loginPath")
                .post(form)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fa-IR,fa;q=0.9,en;q=0.8")
                .header("Referer", "$baseUrl$loginPath")
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val finalUrl = resp.request.url.toString()

                // تشخیص موفقیت: اگر از صفحه لاگین خارج شده باشیم
                val stillOnLogin = finalUrl.contains("f_login", ignoreCase = true) ||
                        body.contains("ed_fusername", ignoreCase = true)

                if (stillOnLogin) {
                    val errorMsg = extractErrorFromPage(body)
                    Result.success(LoginResult.Error(errorMsg ?: "کد ملی یا رمز عبور نادرست است"))
                } else {
                    Result.success(LoginResult.Success)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** دریافت صفحه لاگین و استخراج توکن‌های ASP.NET */
    private fun getLoginPage(): LoginPageTokens? {
        val request = Request.Builder()
            .url("$baseUrl$loginPath")
            .get()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null
            val doc = Jsoup.parse(html)

            return LoginPageTokens(
                viewState = doc.selectFirst("input[name=__VIEWSTATE]")?.attr("value") ?: "",
                viewStateGenerator = doc.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value") ?: "",
                eventValidation = doc.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value") ?: ""
            )
        }
    }

    /** تلاش برای استخراج پیام خطای سرور از HTML پاسخ */
    private fun extractErrorFromPage(html: String): String? {
        val doc = Jsoup.parse(html)
        val candidates = listOf(
            "span[id*=Label]",
            "span[style*=color:Red]",
            "span[style*=color:red]",
            "div.alert-danger",
            "div.validation-summary-errors"
        )
        for (sel in candidates) {
            doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
    /**
     * دریافت اطلاعات فروشگاه از صفحه داشبورد
     */
    fun fetchDashboard(): Result<StoreInfo> {
        return try {
            val url = "$baseUrl/trusting_sms/froshgah_first.aspx"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .header("Accept-Language", "fa-IR,fa;q=0.9")
                .build()

            client.newCall(request).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(html)

                // اگه برگشت به صفحه لاگین، یعنی نشست منقضی شده
                if (html.contains("ed_fusername")) {
                    return Result.success(StoreInfo(isLoggedIn = false))
                }

                Result.success(parseStoreInfo(html, doc))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseStoreInfo(html: String, doc: org.jsoup.nodes.Document): StoreInfo {
        val text = doc.body().text()

        fun valueAfterLabel(label: String): String {
            val idx = text.indexOf(label)
            if (idx < 0) return ""
            return text.substring(idx + label.length)
                .trimStart(':', ' ', '\u200e', '\u200f', '\u00a0', '\n', '\t')
                .take(60)
        }

        val header = doc.select("h1, h2, h3").firstOrNull()?.text()?.trim().orEmpty()

        val rawManager = valueAfterLabel("نام مدیر فروشگاه")
        val managerClean = rawManager.split("نام فروشگاه").first().trim()

        val rawShopName = valueAfterLabel("نام فروشگاه")
        val shopNameClean = rawShopName.split("مانده").first().trim()

        return StoreInfo(
            isLoggedIn = true,
            headerTitle = header.ifEmpty { "پنل فروشگاه" },
            shopCode = Regex("\\d+").find(valueAfterLabel("کد فروشگاه"))?.value ?: "—",
            managerName = managerClean.ifEmpty { "—" },
            shopName = shopNameClean.ifEmpty { "—" },
            credit = Regex("\\d+").find(valueAfterLabel("مانده اعتبار"))?.value ?: "—"
        )
    }
}
data class StoreInfo(
    val isLoggedIn: Boolean,
    val headerTitle: String = "",
    val shopCode: String = "",
    val managerName: String = "",
    val shopName: String = "",
    val credit: String = ""
)

data class LoginPageTokens(
    val viewState: String,
    val viewStateGenerator: String,
    val eventValidation: String
)

sealed class LoginResult {
    object Success : LoginResult()
    data class Error(val message: String) : LoginResult()
}