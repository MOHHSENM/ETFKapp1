package ir.etfk.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import ir.etfk.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var api: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        api = ApiClient(session)

        // اگه قبلاً لاگین شده، مستقیم برو داشبورد
        if (session.isLoggedIn()) {
            goToNext()
            return
        }

        // پر کردن خودکار فیلدها از حافظه
        if (session.isRememberChecked()) {
            binding.etNationalId.setText(session.getSavedUsername())
            binding.etPassword.setText(session.getSavedPassword())
            binding.chkRemember.isChecked = true
        }

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false
        }
    }

    private fun doLogin() {
        val user = binding.etNationalId.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString().orEmpty()
        val remember = binding.chkRemember.isChecked

        hideError()

        if (user.isEmpty() || pass.isEmpty()) {
            showError(getString(R.string.err_empty))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.login(user, pass) }
            setLoading(false)

            result.onSuccess { loginResult ->
                when (loginResult) {
                    is LoginResult.Success -> {
                        if (remember) {
                            session.saveCredentials(user, pass)
                        } else {
                            session.clearCredentials()
                        }
                        goToNext()
                    }
                    is LoginResult.Error -> showError(loginResult.message)
                }
            }.onFailure {
                showError(getString(R.string.err_network))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility =
            if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text =
            if (loading) getString(R.string.logging_in) else getString(R.string.btn_login)
        binding.etNationalId.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.chkRemember.isEnabled = !loading
    }

    private fun showError(msg: String) {
        binding.txtError.text = msg
        binding.txtError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = View.GONE
    }

    private fun goToNext() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}