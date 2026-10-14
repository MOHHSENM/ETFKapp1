package ir.etfk.app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReturnInvoiceActivity : BaseActivity() {

    private lateinit var api: InvoiceApi
    private var meliValue = ""
    private var personelValue = ""
    private var faktorValue = ""

    // متغیرهای پیش‌پر کردن
    private var prefilledFactorCode = ""
    private var prefilledPersonel = ""
    private var prefilledMeli = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = InvoiceApi(session)

        // دریافت مقادیر از صفحه قبل
        prefilledFactorCode = intent.getStringExtra("factor_code") ?: ""
        prefilledPersonel = intent.getStringExtra("personel_code") ?: ""
        prefilledMeli = intent.getStringExtra("meli_code") ?: ""

        openForm()
    }

    private fun openForm() {
        setContentView(R.layout.activity_return_invoice)
        showProgress(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { api.openReturnForm() }
            showProgress(false)
            r.onSuccess {
                bindStep1()
            }.onFailure { e ->
                toast(e.message ?: "خطا در باز کردن فرم")
                finish()
            }
        }
    }

    private fun bindStep1() {
        val etMeli = findViewById<EditText>(R.id.etMeli)
        val etPersonel = findViewById<EditText>(R.id.etPersonel)
        val etFactorCode = findViewById<EditText>(R.id.etFactorCode)

        // ⚡ پر کردن خودکار فیلدها (فقط کد پرسنلی و کد فاکتور)
        if (prefilledFactorCode.isNotEmpty()) {
            etFactorCode.setText(prefilledFactorCode)
        }
        if (prefilledPersonel.isNotEmpty() && prefilledPersonel != "-") {
            etPersonel.setText(prefilledPersonel)
        }
        // فیلد کد ملی خالی می‌مونه

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSubmit).setOnClickListener {
            val meli = etMeli.text.toString().trim()
            val personel = etPersonel.text.toString().trim()
            val faktor = etFactorCode.text.toString().trim()

            hideError()

            if (meli.isEmpty() && personel.isEmpty()) {
                showError("کد ملی یا کد پرسنلی را وارد کنید"); return@setOnClickListener
            }
            if (faktor.isEmpty()) {
                showError("کد فاکتور را وارد کنید"); return@setOnClickListener
            }

            meliValue = meli
            personelValue = personel
            faktorValue = faktor

            showProgress(true)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    api.checkReturnInvoice(meli, personel, faktor)
                }
                showProgress(false)
                r.onSuccess { info -> showStep2(info) }
                    .onFailure { e -> showError(e.message ?: "فاکتور پیدا نشد") }
            }
        }
    }

    private fun showStep2(info: ReturnInvoiceInfo) {
        setContentView(R.layout.activity_return_confirm)

        findViewById<TextView>(R.id.txtBuyer).text = "${info.name} ${info.family}"
        findViewById<TextView>(R.id.txtPersonel).text = info.personel
        findViewById<TextView>(R.id.txtSharh).text = info.sharh
        findViewById<TextView>(R.id.txtMablagh).text = "${info.mablagh} ریال"
        findViewById<TextView>(R.id.txtPish).text = "${info.pish} ریال"
        findViewById<TextView>(R.id.txtDate).text = info.date

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("تایید برگشت")
                .setMessage("آیا از برگشت فاکتور مطمئن هستید؟")
                .setPositiveButton("بله، برگشت بزن") { _, _ -> doReturn() }
                .setNegativeButton("انصراف", null)
                .show()
        }
    }

    private fun doReturn() {
        showProgress(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                api.submitReturn(meliValue, personelValue, faktorValue)
            }
            showProgress(false)
            r.onSuccess { msg ->
                toast(msg)

                // ⚡ بازگشت مستقیم به صفحه اصلی (داشبورد)
                val intent = android.content.Intent(this@ReturnInvoiceActivity, MainActivity::class.java)
                // این فلگ‌ها باعث میشن صفحاتی که روی داشبورد باز بودن (مثل لیست فاکتورها) بسته بشن
                intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()

            }.onFailure { e ->
                showError(e.message ?: "خطا در برگشت فاکتور")
            }
        }
    }

    private fun showProgress(show: Boolean) {
        findViewById<ProgressBar?>(R.id.progress)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        val tv = findViewById<TextView?>(R.id.txtError) ?: return
        tv.text = msg
        tv.visibility = View.VISIBLE
    }

    private fun hideError() {
        findViewById<TextView?>(R.id.txtError)?.visibility = View.GONE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}