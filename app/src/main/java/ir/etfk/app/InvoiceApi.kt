package ir.etfk.app

import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class InvoiceApi(private val session: SessionManager) {

    private val client = OkHttpClient.Builder()
        .cookieJar(session)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val baseUrl = "https://etfk.ir"
    private val ua = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
    private var currentUrl = "$baseUrl/trusting_sms/froshgah_first.aspx"
    private var currentHtml = ""

    private val D = "${'$'}"

    fun startInvoice(): Result<Unit> = runCatching {
        load("$baseUrl/trusting_sms/froshgah_first.aspx")
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}bt_sabet_faktor" to "ثبت فاکتور جدید"
        )
        postAndFollow(currentUrl, form)

        if (!currentHtml.contains("ed_meli")) {
            val page = currentUrl.substringAfterLast("/").substringBefore("?")
            throw Exception("به صفحه ثبت فاکتور نرسیدیم. صفحه: $page")
        }
    }

    fun checkCustomer(meli: String, personel: String): Result<CustomerInfo> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to meli,
            "ctl00${D}ContentPlaceHolder1${D}ed_personel" to personel,
            "ctl00${D}ContentPlaceHolder1${D}bt_barrasi_customer" to "بررسی"
        )
        postAndFollow(currentUrl, form)
        val d = Jsoup.parse(currentHtml)

        // ⚡ پیام خطای سرور (هر پیامی که توی این span باشه)
        val errMsg = d.getElementById("ContentPlaceHolder1_l_user_error")?.text()?.trim().orEmpty()

        // حالت ۱: کاربر فاکتور موقت داره
        if (errMsg.contains("فاکتور موقت")) {
            val code = Regex("([a-z][a-z0-9]{5,10})\\s+می").find(errMsg)?.groupValues?.getOrNull(1)
                ?: Regex("رهگیری\\s+([a-zA-Z0-9]+)").find(errMsg)?.groupValues?.getOrNull(1)
                ?: ""
            return@runCatching CustomerInfo(
                name = "", family = "", personalCode = "",
                credit = "", phone = "", warning = errMsg,
                shopName = "", hasTempInvoice = true, tempInvoiceCode = code
            )
        }

        // حالت ۲: هر پیام خطای دیگه‌ای از سرور
        if (errMsg.isNotBlank()) {
            throw Exception(errMsg)
        }

        // حالت ۳: همه چیز اوکی بود
        val name = d.getElementById("ContentPlaceHolder1_l_name")?.text().orEmpty()
        if (name.isBlank()) {
            throw Exception("مشتری با این مشخصات یافت نشد")
        }

        val shopName = d.getElementById("ContentPlaceHolder1_l_froshgah_name")?.text().orEmpty()

        CustomerInfo(
            name = name,
            family = d.getElementById("ContentPlaceHolder1_l_f_name")?.text().orEmpty(),
            personalCode = d.getElementById("ContentPlaceHolder1_l_personeli")?.text().orEmpty(),
            credit = d.getElementById("ContentPlaceHolder1_l_mandeh")?.text().orEmpty(),
            phone = d.getElementById("ContentPlaceHolder1_l_have_mob")?.text().orEmpty(),
            warning = d.getElementById("ContentPlaceHolder1_l_taedod")?.text().orEmpty(),
            shopName = shopName
        )
    }

    // ====== پیگیری فاکتور موقت ======
    fun followTempInvoice(meli: String, personel: String, code: String): Result<Unit> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val b = FormBody.Builder()
        b.add("__EVENTTARGET", "ctl00${D}ContentPlaceHolder1${D}LinkButton1")
        b.add("__EVENTARGUMENT", "")
        b.add("__VIEWSTATE", doc.selectFirst("input[name=__VIEWSTATE]")?.attr("value").orEmpty())
        b.add("__VIEWSTATEGENERATOR", doc.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value").orEmpty())
        b.add("__SCROLLPOSITIONX", "0")
        b.add("__SCROLLPOSITIONY", "0")
        val pp = doc.selectFirst("input[name=__PREVIOUSPAGE]")?.attr("value").orEmpty()
        if (pp.isNotEmpty()) b.add("__PREVIOUSPAGE", pp)
        b.add("__EVENTVALIDATION", doc.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value").orEmpty())
        b.add("ctl00${D}ContentPlaceHolder1${D}ed_meli", meli)
        b.add("ctl00${D}ContentPlaceHolder1${D}ed_personel", personel)

        // ⚡ POST به URL جدید (sms_inv_item.aspx?id=code)
        val postUrl = "$baseUrl/trusting_sms/sms_inv_item.aspx?id=$code"
        postAndFollow(postUrl, b.build())

        // اگه فاکتور مربوط به فروشگاه دیگه‌ست
        if (currentHtml.contains("فاکتور مربوط به این فروشگاه")) {
            throw Exception("این فاکتور مربوط به فروشگاه شما نمی‌باشد")
        }

        // اگه صفحه تایید نیومد
        if (!currentHtml.contains("ed_code") && !currentHtml.contains("bt_without_sms")) {
            val page = currentUrl.substringAfterLast("/").substringBefore("?")
            throw Exception("صفحه تایید فاکتور موقت باز نشد. صفحه: $page")
        }
    }
    fun confirmCustomer(meli: String, personel: String): Result<Unit> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to meli,
            "ctl00${D}ContentPlaceHolder1${D}ed_personel" to personel,
            "ctl00${D}ContentPlaceHolder1${D}bt_ok_customer" to "تایید اطلاعات خریدار"
        )
        postAndFollow(currentUrl, form)
    }

    // ====== مرحله ۳: ثبت موقت فاکتور ======
    fun submitInvoice(
        meli: String, personel: String,
        factorId: String, description: String,
        prepayment: String, amount: String, installments: String
    ): Result<Unit> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to meli,
            "ctl00${D}ContentPlaceHolder1${D}ed_personel" to personel,
            "ctl00${D}ContentPlaceHolder1${D}ed_faktor_id" to factorId,
            "ctl00${D}ContentPlaceHolder1${D}ed_sharh" to description,
            "ctl00${D}ContentPlaceHolder1${D}ed_pish" to prepayment,
            "ctl00${D}ContentPlaceHolder1${D}ed_mablagh" to amount,
            "ctl00${D}ContentPlaceHolder1${D}dp_aghsat" to installments,
            "ctl00${D}ContentPlaceHolder1${D}bt_ok_faktor" to "تایید و ثبت موقت فاکتور",
            "ctl00${D}ContentPlaceHolder1${D}h_sms_trn_id" to ""
        )
        postAndFollow(currentUrl, form)

        // ⚡ چک خطای سقف فروشگاه
        val d = Jsoup.parse(currentHtml)
        val errMsg = d.getElementById("ContentPlaceHolder1_l_err_faktor")?.text()?.trim().orEmpty()
        if (errMsg.isNotBlank()) {
            throw Exception(errMsg)
        }

        // چک: اگه برگشت به صفحه ثبت موقت، یعنی خطایی رخ داده
        if (currentUrl.contains("sms_invoice", ignoreCase = true)) {
            // اگه هنوز توی صفحه ثبت موقتیم و خطا نداریم، یه چیزی غیرعادی رخ داده
            val anyErr = d.selectFirst("span[style*=color:Red]")?.text()?.trim().orEmpty()
            if (anyErr.isNotBlank()) {
                throw Exception(anyErr)
            }
        }
    }

    fun confirmWithSms(code: String): Result<InvoiceResult> = runCatching {
        doFinalConfirm(code, "bt_ok", "تایید")
    }

    fun confirmWithoutSms(): Result<InvoiceResult> = runCatching {
        doFinalConfirm("", "bt_without_sms", "تایید بدون ارسال پیامک")
    }

    private fun doFinalConfirm(code: String, buttonName: String, buttonValue: String): InvoiceResult {
        val d = Jsoup.parse(currentHtml)
        val hInvoiceId = d.getElementById("ContentPlaceHolder1_h_invoice_id")?.attr("value").orEmpty()
        val factorId = d.getElementById("ContentPlaceHolder1_ed_faktor_id")?.attr("value").orEmpty()
        val sharh = d.getElementById("ContentPlaceHolder1_ed_sharh")?.attr("value").orEmpty()
        val ghest = d.getElementById("ContentPlaceHolder1_ed_ghest")?.attr("value").orEmpty()

        val id = extractId(hInvoiceId)

        val form = buildForm(d,
            "ctl00${D}ContentPlaceHolder1${D}h_invoice_id" to hInvoiceId,
            "ctl00${D}ContentPlaceHolder1${D}ed_faktor_id" to factorId,
            "ctl00${D}ContentPlaceHolder1${D}ed_sharh" to sharh,
            "ctl00${D}ContentPlaceHolder1${D}ed_ghest" to ghest,
            "ctl00${D}ContentPlaceHolder1${D}ed_code" to code,
            "ctl00${D}ContentPlaceHolder1${D}$buttonName" to buttonValue
        )
        postAndFollow(currentUrl, form)

        // بعد از POST، اگه به print_page نرسیدیم، دستی می‌ریم
        if (!currentUrl.contains("print_page", ignoreCase = true) && id.isNotEmpty()) {
            load("$baseUrl/trusting_sms/print_page.aspx?id=$id")
        }

        return parseInvoiceResult()
    }

    private fun extractId(hInvoiceId: String): String {
        val fromUrl = currentUrl.substringAfter("?id=", "").substringBefore("&")
        return if (fromUrl.isNotEmpty() && fromUrl != currentUrl) fromUrl
        else hInvoiceId
    }

    private fun parseInvoiceResult(): InvoiceResult {
        val d = Jsoup.parse(currentHtml)

        var rahgiri = d.getElementById("ContentPlaceHolder1_l_rahgiri")?.text().orEmpty()
        if (rahgiri.isBlank() && currentUrl.contains("id=")) {
            rahgiri = currentUrl.substringAfter("id=").substringBefore("&")
        }

        if (rahgiri.isBlank()) {
            val page = currentUrl.substringAfterLast("/").substringBefore("?")
            throw Exception("کد رهگیری پیدا نشد. صفحه: $page")
        }

        val date = d.getElementById("ContentPlaceHolder1_l_date")?.text().orEmpty()
        val shopName = d.getElementById("ContentPlaceHolder1_l_froshgah_name")?.text().orEmpty()
        val shopPhone = d.getElementById("ContentPlaceHolder1_l_tel_froshgah")?.text().orEmpty()
        val buyerName = d.getElementById("ContentPlaceHolder1_l_name_kharidar")?.text().orEmpty()
        val personal = d.getElementById("ContentPlaceHolder1_l_personeli")?.text().orEmpty()
        val sharh = d.getElementById("ContentPlaceHolder1_ed_sharh")?.attr("value").orEmpty()
        val ghest = d.getElementById("ContentPlaceHolder1_ed_ghest")?.attr("value").orEmpty()
        val pish = d.getElementById("ContentPlaceHolder1_l_pish")?.text().orEmpty()
        val status = d.getElementById("ContentPlaceHolder1_l_status")?.text().orEmpty()

        var total = "—"
        val rows = d.select("table[id=ContentPlaceHolder1_GridView1] tr")
        for (row in rows) {
            val cells = row.select("td")
            if (cells.size >= 8 && cells[5].text().trim() == "جمع") {
                total = cells[7].text().trim()
            }
        }

        return InvoiceResult(
            trackingCode = rahgiri,
            date = date,
            shopName = shopName,
            shopPhone = shopPhone,
            buyerName = buyerName,
            personalCode = personal,
            description = sharh,
            installments = ghest,
            prepayment = pish,
            totalAmount = total,
            status = status
        )
    }

    private fun load(url: String) {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("Accept-Language", "fa-IR,fa;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            currentUrl = resp.request.url.toString()
            currentHtml = resp.body?.string().orEmpty()
        }
    }

    private fun postAndFollow(url: String, form: FormBody) {
        val req = Request.Builder().url(url).post(form)
            .header("User-Agent", ua)
            .header("Accept-Language", "fa-IR,fa;q=0.9")
            .header("Referer", url)
            .build()
        client.newCall(req).execute().use { resp ->
            currentUrl = resp.request.url.toString()
            currentHtml = resp.body?.string().orEmpty()
        }
    }

    private fun buildForm(doc: Document, vararg fields: Pair<String, String>): FormBody {
        val b = FormBody.Builder()
        b.add("__EVENTTARGET", "")
        b.add("__EVENTARGUMENT", "")
        b.add("__VIEWSTATE", doc.selectFirst("input[name=__VIEWSTATE]")?.attr("value").orEmpty())
        b.add("__VIEWSTATEGENERATOR", doc.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value").orEmpty())
        b.add("__SCROLLPOSITIONX", "0")
        b.add("__SCROLLPOSITIONY", "0")

        // فقط اگه توی صفحه وجود داشت
        val vse = doc.selectFirst("input[name=__VIEWSTATEENCRYPTED]")
        if (vse != null) {
            b.add("__VIEWSTATEENCRYPTED", vse.attr("value"))
        }

        b.add("__EVENTVALIDATION", doc.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value").orEmpty())
        for ((k, v) in fields) b.add(k, v)
        return b.build()
    }
    // ====== باز کردن لیست فاکتورها ======
    fun openInvoiceList(): Result<Unit> = runCatching {
        load("$baseUrl/trusting_sms/froshgah_first.aspx")
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}bt_list_faktor" to "مشاهده لیست فاکتورها اقساطی"
        )
        postAndFollow(currentUrl, form)

        // ⚡ چک می‌کنیم فقط که توی صفحه invoice_list باشیم (نه اینکه جدول خالی نباشه)
        if (!currentUrl.contains("invoice_list", ignoreCase = true)) {
            val page = currentUrl.substringAfterLast("/").substringBefore("?")
            throw Exception("صفحه لیست باز نشد. صفحه: $page")
        }
    }

    // ====== جستجوی فاکتورها ======
    fun searchInvoices(
        start: String, end: String, factorCode: String,
        shopInvoice: String, meli: String, shoghl: String, status: String
    ): Result<List<InvoiceRow>> = runCatching {
        val doc = Jsoup.parse(currentHtml)

        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}filter_n" to (doc.getElementById("ContentPlaceHolder1_filter_n")?.attr("value").orEmpty()),
            "ctl00${D}ContentPlaceHolder1${D}ed_start" to start,
            "ctl00${D}ContentPlaceHolder1${D}ed_end" to end,
            "ctl00${D}ContentPlaceHolder1${D}ed_id" to factorCode,
            "ctl00${D}ContentPlaceHolder1${D}ed_sh_invoice" to shopInvoice,
            "ctl00${D}ContentPlaceHolder1${D}ed_froshgah_id" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_froshgah_name" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_sharh" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_price" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to meli,
            "ctl00${D}ContentPlaceHolder1${D}ed_name" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_surename" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_shoghl" to shoghl,
            "ctl00${D}ContentPlaceHolder1${D}dp_status" to status,
            "ctl00${D}ContentPlaceHolder1${D}ImageButton1.x" to "10",
            "ctl00${D}ContentPlaceHolder1${D}ImageButton1.y" to "10"
        )
        postAndFollow(currentUrl, form)

        // ⚡ چک کنیم که آیا سرور فیلتر مشتری رو اعمال کرده یا نه
        val responseDoc = Jsoup.parse(currentHtml)
        val responseFilter = responseDoc.getElementById("ContentPlaceHolder1_filter_n")?.attr("value").orEmpty()
        val hasCustomerFilter = responseFilter.contains("customer_id", ignoreCase = true)

        // اگه کاربر کد ملی یا پرسنلی وارد کرده ولی سرور customer_id رو اعمال نکرده
        // یعنی کد نامعتبره → لیست خالی برگردون
        if ((meli.isNotEmpty() || shoghl.isNotEmpty()) && !hasCustomerFilter) {
            return@runCatching emptyList()
        }

        parseInvoiceRows()
    }

    // ====== پارس جدول فاکتورها ======
    private fun parseInvoiceRows(): List<InvoiceRow> {
        val d = Jsoup.parse(currentHtml)
        val table = d.getElementById("ContentPlaceHolder1_GridView1") ?: return emptyList()
        val rows = mutableListOf<InvoiceRow>()
        val trs = table.select("tr")
        for (i in 1 until trs.size) {
            val cells = trs[i].select("td")
            if (cells.size < 17) continue
            val id = cells[3].text().trim()
            if (id.isEmpty()) continue
            rows.add(
                InvoiceRow(
                    rowNumber = cells[2].text().trim(),
                    id = id,
                    terminal = cells[4].text().trim(),
                    shopInvoice = cells[5].text().trim(),
                    detail = cells[6].text().trim(),
                    price = cells[7].text().trim(),
                    installments = cells[8].text().trim(),
                    customer = cells[9].text().trim(),
                    meli = cells[10].text().trim(),
                    personel = cells[11].text().trim(),
                    mahalKhedmat = cells[12].text().trim(),
                    noeEstekhdam = cells[13].text().trim(),
                    status = cells[14].text().trim(),
                    date = cells[15].text().trim(),
                    prepayment = cells[16].text().trim()
                )
            )
        }
        return rows
    }
    // ====== باز کردن فرم برگشت فاکتور ======
    fun openReturnForm(): Result<Unit> = runCatching {
        load("$baseUrl/trusting_sms/froshgah_first.aspx")
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}bt_braghast" to "برگشت فاکتور"
        )
        postAndFollow(currentUrl, form)

        if (!currentHtml.contains("ed_faktor")) {
            val page = currentUrl.substringAfterLast("/").substringBefore("?")
            throw Exception("صفحه برگشت باز نشد. صفحه: $page")
        }
    }

    // ====== بررسی فاکتور برای برگشت ======
    fun checkReturnInvoice(meli: String, personel: String, faktor: String): Result<ReturnInvoiceInfo> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to meli,
            "ctl00${D}ContentPlaceHolder1${D}ed_personeli" to personel,
            "ctl00${D}ContentPlaceHolder1${D}ed_faktor" to faktor,
            "ctl00${D}ContentPlaceHolder1${D}bt_barrasi_customer" to "بررسی"
        )
        postAndFollow(currentUrl, form)
        val d = Jsoup.parse(currentHtml)

        val name = d.getElementById("ContentPlaceHolder1_l_name")?.text().orEmpty()
        if (name.isBlank()) throw Exception("فاکتور با این مشخصات یافت نشد")

        ReturnInvoiceInfo(
            name = name,
            family = d.getElementById("ContentPlaceHolder1_l_f_name")?.text().orEmpty(),
            personel = d.getElementById("ContentPlaceHolder1_l_personeli")?.text().orEmpty(),
            sharh = d.getElementById("ContentPlaceHolder1_l_sharh_faktor")?.text().orEmpty(),
            mablagh = d.getElementById("ContentPlaceHolder1_l_mablagh_faktor")?.text().orEmpty(),
            pish = d.getElementById("ContentPlaceHolder1_l_pish")?.text().orEmpty(),
            date = d.getElementById("ContentPlaceHolder1_l_date")?.text().orEmpty()
        )
    }

    // ====== ثبت برگشت فاکتور ======
    fun submitReturn(meli: String, personel: String, faktor: String): Result<String> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to meli,
            "ctl00${D}ContentPlaceHolder1${D}ed_personeli" to personel,
            "ctl00${D}ContentPlaceHolder1${D}ed_faktor" to faktor,
            "ctl00${D}ContentPlaceHolder1${D}bt_bargasht" to "برگشت اسناد فاکتور"
        )
        postAndFollow(currentUrl, form)

        val d = Jsoup.parse(currentHtml)
        val err = d.selectFirst("span[style*=color:Red]")?.text().orEmpty()
        if (err.isNotBlank()) err else "فاکتور با موفقیت برگشت داده شد"
    }
    // ====== باز کردن فرم تغییر رمز ======
    fun openChangePassword(): Result<Unit> = runCatching {
        load("$baseUrl/trusting_sms/froshgah_first.aspx")
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}bt_change_apss" to "تغییر رمز عبور"
        )
        postAndFollow(currentUrl, form)

        if (!currentHtml.contains("ed_pass")) {
            val page = currentUrl.substringAfterLast("/").substringBefore("?")
            throw Exception("صفحه تغییر رمز باز نشد. صفحه: $page")
        }
    }

    // ====== ثبت تغییر رمز ======
    fun submitPasswordChange(oldPass: String, newPass: String, confirmPass: String): Result<String> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}ed_pass" to oldPass,
            "ctl00${D}ContentPlaceHolder1${D}ed_new_pass" to newPass,
            "ctl00${D}ContentPlaceHolder1${D}ed_conf" to confirmPass,
            "ctl00${D}ContentPlaceHolder1${D}bt_add" to "ثبت"
        )
        postAndFollow(currentUrl, form)

        val d = Jsoup.parse(currentHtml)
        // خطا: معمولاً توی span قرمز رنگ
        val errMsg = d.selectFirst("span[style*=color:Red]")?.text()?.trim().orEmpty()
        if (errMsg.isNotBlank()) {
            errMsg
        } else if (currentHtml.contains("رمز شما تغییر کرد")) {
            "رمز عبور با موفقیت تغییر کرد"
        } else {
            // چک دیگه: اگه صفحه change باقی مونده، احتمالاً خطا
            if (currentUrl.contains("password_change")) {
                "تغییر رمز انجام نشد. رمز قدیم را بررسی کنید."
            } else {
                "رمز عبور با موفقیت تغییر کرد"
            }
        }
    }
    // ====== انصراف از فاکتور موقت ======
    fun cancelTempInvoice(): Result<Unit> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}bt_cancel" to "انصراف"
        )
        postAndFollow(currentUrl, form)
    }
    // ====== لغو فاکتور موقت از لیست (با کلیک روی آیکون ضربدر) ======
    fun cancelInvoiceAt(rowIndex: Int): Result<Unit> = runCatching {
        val doc = Jsoup.parse(currentHtml)
        val ctl = String.format("ctl%02d", rowIndex + 2)   // 0 → ctl02

        val filterN = doc.getElementById("ContentPlaceHolder1_filter_n")?.attr("value").orEmpty()
        val edStart = doc.getElementById("ContentPlaceHolder1_ed_start")?.attr("value").orEmpty()
        val edEnd = doc.getElementById("ContentPlaceHolder1_ed_end")?.attr("value").orEmpty()

        val form = buildForm(doc,
            "ctl00${D}ContentPlaceHolder1${D}filter_n" to filterN,
            "ctl00${D}ContentPlaceHolder1${D}ed_start" to edStart,
            "ctl00${D}ContentPlaceHolder1${D}ed_end" to edEnd,
            "ctl00${D}ContentPlaceHolder1${D}ed_id" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_sh_invoice" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_sharh" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_price" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_meli" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_name" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_surename" to "",
            "ctl00${D}ContentPlaceHolder1${D}ed_shoghl" to "",
            "ctl00${D}ContentPlaceHolder1${D}dp_status" to " ",
            "ctl00${D}ContentPlaceHolder1${D}GridView1${D}$ctl${D}cancelBT.x" to "8",
            "ctl00${D}ContentPlaceHolder1${D}GridView1${D}$ctl${D}cancelBT.y" to "9"
        )
        postAndFollow(currentUrl, form)

        // بعد از لغو، لیست رو دوباره لود کن
        // سرور دوباره همین صفحه رو برمی‌گردونه
    }
    // ====== خوندن گزینه‌های تعداد اقساط از صفحه ======
    fun getInstallmentOptions(): List<String> {
        return try {
            val doc = Jsoup.parse(currentHtml)
            val select = doc.getElementById("ContentPlaceHolder1_dp_aghsat") ?: return emptyList()
            val options = select.select("option")
            options.mapNotNull { it.attr("value").takeIf { v -> v.isNotBlank() && v != "0" } }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

data class CustomerInfo(
    val name: String,
    val family: String,
    val personalCode: String,
    val credit: String,
    val phone: String,
    val warning: String,
    val shopName: String = "",
    val hasTempInvoice: Boolean = false,
    val tempInvoiceCode: String = ""
)

data class InvoiceResult(
    val trackingCode: String,
    val date: String,
    val shopName: String,
    val shopPhone: String,
    val buyerName: String,
    val personalCode: String,
    val description: String,
    val installments: String,
    val prepayment: String,
    val totalAmount: String,
    val status: String
)
data class ReturnInvoiceInfo(
    val name: String,
    val family: String,
    val personel: String,
    val sharh: String,
    val mablagh: String,
    val pish: String,
    val date: String
)
data class InvoiceRow(
    val rowNumber: String,
    val id: String,
    val terminal: String,
    val shopInvoice: String,
    val detail: String,
    val price: String,
    val installments: String,
    val customer: String,
    val meli: String,
    val personel: String,
    val mahalKhedmat: String,
    val noeEstekhdam: String,
    val status: String,
    val date: String,
    val prepayment: String
)
