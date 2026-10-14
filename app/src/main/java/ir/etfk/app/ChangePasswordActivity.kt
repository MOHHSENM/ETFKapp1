package ir.etfk.app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChangePasswordActivity : BaseActivity() {

    private lateinit var api: InvoiceApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)
        api = InvoiceApi(session)

        showProgress(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { api.openChangePassword() }
            showProgress(false)
            r.onSuccess {
                bindForm()
            }.onFailure { e ->
                showError(e.message ?: "خطا در باز کردن فرم")
            }
        }
    }

    private fun bindForm() {
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSubmit).setOnClickListener {
            val oldPass = findViewById<EditText>(R.id.etOldPass).text.toString()
            val newPass = findViewById<EditText>(R.id.etNewPass).text.toString()
            val confPass = findViewById<EditText>(R.id.etConfirmPass).text.toString()

            hideError()

            if (oldPass.isEmpty() || newPass.isEmpty() || confPass.isEmpty()) {
                showError("همه فیلدها را پر کنید"); return@setOnClickListener
            }
            if (newPass != confPass) {
                showError("رمز جدید و تکرار آن یکسان نیستند"); return@setOnClickListener
            }
            if (newPass.length < 4) {
                showError("رمز جدید باید حداقل ۴ کاراکتر باشد"); return@setOnClickListener
            }

            showProgress(true)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    api.submitPasswordChange(oldPass, newPass, confPass)
                }
                showProgress(false)
                r.onSuccess { msg ->
                    toast(msg)
                    if (msg.contains("موفق")) {
                        finish()
                    }
                }.onFailure { e ->
                    showError(e.message ?: "خطا در تغییر رمز")
                }
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