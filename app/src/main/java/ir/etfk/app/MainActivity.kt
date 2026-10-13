package ir.etfk.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import ir.etfk.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var api: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.txtVersion.text = "ورژن ${BuildConfig.VERSION_NAME}"

        api = ApiClient(session)

        if (!session.isLoggedIn()) {
            goToLogin()
            return
        }

        setupButtons()
        loadDashboard()
    }

    private fun setupButtons() {
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnNewInvoice.setOnClickListener {
            startActivity(Intent(this@MainActivity, NewInvoiceActivity::class.java))
        }

        binding.btnChangePassword.setOnClickListener {
            startActivity(Intent(this@MainActivity, ChangePasswordActivity::class.java))
        }
        binding.btnInstallmentInvoices.setOnClickListener {
            startActivity(Intent(this@MainActivity, InvoiceListActivity::class.java))
        }
        binding.btnReturnInvoice.setOnClickListener {
            startActivity(Intent(this@MainActivity, ReturnInvoiceActivity::class.java))
        }
    }

    private fun loadDashboard() {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.fetchDashboard() }
            binding.progress.visibility = View.GONE

            result.onSuccess { info ->
                if (!info.isLoggedIn) {
                    session.clear()
                    goToLogin()
                    return@onSuccess
                }
                binding.txtHeader.text = info.headerTitle
                binding.txtShopCode.text = info.shopCode
                binding.txtManagerName.text = info.managerName
                binding.txtShopName.text = info.shopName
                binding.txtCredit.text = info.credit
            }.onFailure {
                toast("خطا در دریافت اطلاعات از سرور")
            }
        }
    }

    private fun logout() {
        session.clear()
        goToLogin()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}