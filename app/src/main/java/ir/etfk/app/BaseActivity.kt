package ir.etfk.app

import android.content.Intent
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    protected val session: SessionManager by lazy { SessionManager(this) }

    companion object {
        private const val TIMEOUT_MS = 5 * 60 * 1000L  // ۵ دقیقه
        private var lastBackgroundTime = 0L
        private var activityCount = 0
    }

    override fun onStart() {
        super.onStart()
        activityCount++
    }

    override fun onResume() {
        super.onResume()

        if (this is LoginActivity) {
            lastBackgroundTime = 0L
            return
        }

        if (lastBackgroundTime > 0 && session.isLoggedIn()) {
            val elapsed = SystemClock.elapsedRealtime() - lastBackgroundTime
            if (elapsed > TIMEOUT_MS) {
                session.clear()
                lastBackgroundTime = 0L
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                return
            }
        }
        lastBackgroundTime = 0L
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        activityCount--
        if (activityCount <= 0) {
            activityCount = 0
            lastBackgroundTime = SystemClock.elapsedRealtime()
        }
    }
}