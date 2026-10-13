package ir.etfk.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher

class NewInvoiceActivity : BaseActivity() {

    private lateinit var api: InvoiceApi
    private var meliValue = ""
    private var personelValue = ""
    private var customerName = ""
    private var customerCredit = ""
    private var shopName = ""
    private var lastResult: InvoiceResult? = null
    private var lastAmount = ""
    private var lastInstallments = ""
    private var customerCreditAmount: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        api = InvoiceApi(session)
        startFlow()
    }

    // ============ شروع ============
    private fun startFlow() {
        setContentView(R.layout.activity_invoice_step1)
        showProgress(true)

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { api.startInvoice() }
            showProgress(false)
            r.onSuccess { bindStep1() }
                .onFailure { toast("خطا: ${it.message}"); finish() }
        }
    }

    // ============ مرحله ۱ ============
    private fun bindStep1() {
        val etNational = findViewById<EditText>(R.id.etNationalId)
        val etPersonal = findViewById<EditText>(R.id.etPersonalCode)
        val btnCheck = findViewById<Button>(R.id.btnCheck)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val txtError = findViewById<TextView>(R.id.txtError)

        txtError.visibility = View.GONE

        btnBack.setOnClickListener { finish() }
        btnCheck.setOnClickListener {
            val nid = etNational.text.toString().trim()
            val pid = etPersonal.text.toString().trim()

            txtError.visibility = View.GONE

            if (nid.isEmpty() && pid.isEmpty()) {
                txtError.text = "کد ملی یا شماره پرسنلی را وارد کنید"
                txtError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val meliToSend = nid
            val personelToSend = pid

            showProgress(true)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { api.checkCustomer(meliToSend, personelToSend) }
                showProgress(false)
                r.onSuccess { c ->
                    meliValue = meliToSend
                    personelValue = personelToSend

                    if (c.hasTempInvoice) {
                        showTempInvoiceDialog(c)
                    } else {
                        customerName = "${c.name} ${c.family}"
                        customerCredit = c.credit
                        shopName = c.shopName.ifEmpty { "غرفه" }
                        customerCreditAmount = c.credit
                            .replace(",", "")
                            .replace(" ", "")
                            .filter { it.isDigit() }
                            .toLongOrNull() ?: 0L
                        showStep2(c)
                    }
                }.onFailure { e ->
                    txtError.text = e.message ?: "خطا در بررسی"
                    txtError.visibility = View.VISIBLE
                }
            }
        }
    }

    // ============ دیالوگ فاکتور موقت ============
    private fun showTempInvoiceDialog(c: CustomerInfo) {
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ فاکتور موقت")
            .setMessage("این مشتری دارای فاکتور موقت به شماره رهگیری «${c.tempInvoiceCode}» می‌باشد.\n\nآیا می‌خواهید این فاکتور را بررسی و تایید کنید؟")
            .setPositiveButton("رهگیری و تایید") { _, _ ->
                showProgress(true)
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        api.followTempInvoice(meliValue, personelValue, c.tempInvoiceCode)
                    }
                    showProgress(false)
                    r.onSuccess { showStep4() }
                        .onFailure { toast(it.message ?: "خطا در پیگیری فاکتور") }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ============ مرحله ۲ ============
    private fun showStep2(c: CustomerInfo) {
        setContentView(R.layout.activity_invoice_step2)
        findViewById<TextView>(R.id.txtName).text = "${c.name} ${c.family}"
        findViewById<TextView>(R.id.txtPersonal).text = "کد پرسنلی: ${c.personalCode}"
        findViewById<TextView>(R.id.txtCredit).text = "مانده اعتبار: ${c.credit}"
        findViewById<TextView>(R.id.txtPhone).text = c.phone

        val warning = findViewById<TextView>(R.id.txtWarning)
        if (c.warning.isBlank()) warning.visibility = View.GONE
        else {
            warning.visibility = View.VISIBLE
            warning.text = c.warning
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            showProgress(true)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    api.confirmCustomer(meliValue, personelValue)
                }
                showProgress(false)
                r.onSuccess { showStep3() }
                    .onFailure { toast("خطا در تایید مشتری") }
            }
        }
    }

    // ============ فرمت خودکار با کاما ============
    private fun attachThousandSeparator(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                isFormatting = true

                val original = s?.toString().orEmpty()
                val cleaned = original.replace(",", "")

                if (cleaned.isEmpty()) {
                    isFormatting = false
                    return
                }

                if (cleaned.all { it.isDigit() }) {
                    val formatted = java.lang.String.format(
                        java.util.Locale.US, "%,d", cleaned.toLong()
                    )
                    if (formatted != original) {
                        editText.setText(formatted)
                        editText.setSelection(formatted.length)
                    }
                }

                isFormatting = false
            }
        })
    }

    // ============ مرحله ۳ ============
    private fun showStep3() {
        setContentView(R.layout.activity_invoice_step3)

        findViewById<TextView>(R.id.txtCustomerName).text = customerName
        findViewById<TextView>(R.id.txtCustomerCredit).text = "مانده اعتبار: $customerCredit"

        val etFactorId = findViewById<EditText>(R.id.etFactorId)
        val etDesc = findViewById<EditText>(R.id.etDescription)
        val etPre = findViewById<EditText>(R.id.etPrepayment)
        val etAmount = findViewById<EditText>(R.id.etAmount)
        val spGhest = findViewById<Spinner>(R.id.spInstallments)

        attachThousandSeparator(etPre)
        attachThousandSeparator(etAmount)

        setupAutoScroll(etFactorId)
        setupAutoScroll(etDesc)
        setupAutoScroll(etPre)
        setupAutoScroll(etAmount)
        setupAutoScroll(spGhest)

        etDesc.setText("خرید از $shopName")
        etDesc.setSelection(etDesc.text.length)

        val options = api.getInstallmentOptions()
        val items = listOf("انتخاب کنید") + options
        spGhest.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            items
        )
        if (options.isEmpty()) {
            toast("گزینه‌های تعداد اقساط از سرور دریافت نشد")
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSubmit).setOnClickListener {
            val fid = etFactorId.text.toString().trim()
            val desc = etDesc.text.toString().trim().ifEmpty { "خرید از $shopName" }
            val pre = etPre.text.toString().trim().replace(",", "").ifEmpty { "0" }
            val amt = etAmount.text.toString().trim().replace(",", "")
            val ghest = spGhest.selectedItem.toString()

            if (fid.isEmpty()) {
                toast("شماره فاکتور را وارد کنید"); return@setOnClickListener
            }
            if (amt.isEmpty() || ghest == "انتخاب کنید") {
                toast("مبلغ فاکتور و تعداد اقساط را وارد کنید"); return@setOnClickListener
            }

            val amtLong = amt.toLongOrNull() ?: 0L

            if (customerCreditAmount > 0 && amtLong > customerCreditAmount) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("⚠️ اعتبار کافی نیست")
                    .setMessage(
                        "مانده اعتبار مشتری: ${customerCredit}\n\n" +
                                "مبلغ فاکتور: ${"%,d".format(amtLong)} ریال\n\n" +
                                "مبلغ فاکتور از اعتبار مشتری بیشتر است و امکان ثبت فاکتور وجود ندارد."
                    )
                    .setPositiveButton("باشه", null)
                    .show()
                return@setOnClickListener
            }

            lastAmount = amt
            lastInstallments = ghest

            showProgress(true)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    api.submitInvoice(meliValue, personelValue, fid, desc, pre, amt, ghest)
                }
                showProgress(false)
                r.onSuccess { showStep4() }
                    .onFailure { e ->
                        android.app.AlertDialog.Builder(this@NewInvoiceActivity)
                            .setTitle("⚠️ خطا در ثبت فاکتور")
                            .setMessage(e.message ?: "خطای نامشخص")
                            .setPositiveButton("باشه", null)
                            .show()
                    }
            }
        }
    }

    // ============ مرحله ۴ ============
    private fun showStep4() {
        setContentView(R.layout.activity_invoice_step4)

        findViewById<TextView>(R.id.txtBuyer).text = customerName
        val amountLong = lastAmount.replace(",", "").toLongOrNull() ?: 0L
        val formattedAmount = java.lang.String.format(java.util.Locale.US, "%,d", amountLong)
        findViewById<TextView>(R.id.txtAmount).text = "$formattedAmount ریال"
        findViewById<TextView>(R.id.txtInstallments).text = "$lastInstallments قسط"

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            android.app.AlertDialog.Builder(this@NewInvoiceActivity)
                .setTitle("انصراف از فاکتور")
                .setMessage(
                    "این فاکتور در وضعیت مناسبی جهت انصراف نمی باشد.\n\n" +
                            "برای لغو این فاکتور، لطفاً از بخش «مشاهده لیست فاکتورهای اقساطی» " +
                            "و گزینه «برگشت فاکتور» استفاده کنید."
                )
                .setPositiveButton("باشه") { _, _ -> finish() }
                .setNeutralButton("برو به لیست") { _, _ ->
                    startActivity(android.content.Intent(this, InvoiceListActivity::class.java))
                    finish()
                }
                .show()
        }

        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            showProgress(true)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { api.confirmWithoutSms() }
                showProgress(false)
                r.onSuccess { showStep5(it) }
                    .onFailure { e ->
                        android.app.AlertDialog.Builder(this@NewInvoiceActivity)
                            .setTitle("خطا در تایید فاکتور")
                            .setMessage(e.message ?: "خطای نامشخص")
                            .setPositiveButton("باشه", null)
                            .show()
                    }
            }
        }
    }

    // ============ مرحله ۵ ============
    private fun showStep5(result: InvoiceResult) {
        lastResult = result
        setContentView(R.layout.activity_invoice_step5)

        findViewById<TextView>(R.id.txtTrackingCode).text = "کد رهگیری: ${result.trackingCode}"
        findViewById<TextView>(R.id.txtDate).text = "تاریخ: ${result.date}"
        findViewById<TextView>(R.id.txtBuyer).text = "خریدار: ${result.buyerName}"
        findViewById<TextView>(R.id.txtAmount).text = "مبلغ کل: ${result.totalAmount} ریال"
        findViewById<TextView>(R.id.txtInstallments).text = "تعداد اقساط: ${result.installments}"
        findViewById<TextView>(R.id.txtStatus).text = "وضعیت: ${result.status}"

        findViewById<Button>(R.id.btnPrint).setOnClickListener {
            printInvoice()
        }

        findViewById<Button>(R.id.btnBackToPanel).setOnClickListener {
            finish()
        }
    }

    // ============ چاپ فاکتور ============
    @SuppressLint("SetJavaScriptEnabled")
    private fun printInvoice() {
        val r = lastResult ?: return

        val html = try {
            assets.open("print_invoice.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            toast("خطا در بارگذاری قالب چاپ")
            return
        }

        val filled = html
            .replace("id=\"shopName\">فروشگاه", "id=\"shopName\">${r.shopName}")
            .replace("id=\"shopPhone\"></p>", "id=\"shopPhone\">تلفن: ${r.shopPhone}</p>")
            .replace("id=\"trackingCode\"></span>", "id=\"trackingCode\">${r.trackingCode}</span>")
            .replace("id=\"date\"></span>", "id=\"date\">${r.date}</span>")
            .replace("id=\"factorId\"></span>", "id=\"factorId\">—</span>")
            .replace("id=\"buyerName\"></span>", "id=\"buyerName\">${r.buyerName}</span>")
            .replace("id=\"personalCode\"></span>", "id=\"personalCode\">${r.personalCode}</span>")
            .replace("id=\"description\"></td>", "id=\"description\">${r.description}</td>")
            .replace("id=\"totalAmount\"></td>", "id=\"totalAmount\">${r.totalAmount}</td>")
            .replace("id=\"totalAmount2\"></td>", "id=\"totalAmount2\">${r.totalAmount}</td>")
            .replace("id=\"prepayment\"></span>", "id=\"prepayment\">${r.prepayment} ریال</span>")
            .replace("id=\"installments\"></span>", "id=\"installments\">${r.installments}</span>")
            .replace("id=\"status\"></span>", "id=\"status\">${r.status}</span>")

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                val printAdapter = webView.createPrintDocumentAdapter("invoice_${r.trackingCode}")
                val jobName = "فاکتور ${r.trackingCode}"
                printManager.print(
                    jobName,
                    printAdapter,
                    PrintAttributes.Builder().build()
                )
            }
        }
        webView.loadDataWithBaseURL(null, filled, "text/html", "UTF-8", null)
    }

    // ============ کمکی ============
    private fun showProgress(show: Boolean) {
        val p = findViewById<ProgressBar?>(R.id.progress)
        p?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ⚡ اسکرول خودکار به فیلد فعال (داخل کلاس)
    private fun setupAutoScroll(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.postDelayed({
                    var parent: android.view.ViewParent? = v.parent
                    while (parent != null) {
                        if (parent is android.widget.ScrollView) {
                            val location = IntArray(2)
                            v.getLocationInWindow(location)
                            val scrollViewLocation = IntArray(2)
                            (parent as android.widget.ScrollView).getLocationInWindow(scrollViewLocation)
                            val relativeTop = location[1] - scrollViewLocation[1]
                            val targetScroll = (parent as android.widget.ScrollView).scrollY + relativeTop - 150
                            (parent as android.widget.ScrollView).smoothScrollTo(0, targetScroll.coerceAtLeast(0))
                            break
                        }
                        parent = parent.parent
                    }
                }, 300)
            }
        }
    }
}