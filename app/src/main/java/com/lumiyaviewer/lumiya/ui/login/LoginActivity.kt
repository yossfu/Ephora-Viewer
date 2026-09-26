package com.lumiyaviewer.lumiya.ui.login

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityLoginBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.grids.GridManager
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import com.lumiyaviewer.lumiya.ui.main.MainActivity
import kotlinx.coroutines.launch

/** Login for the real Second Life main grid only. */
class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var navigatedToMain = false

    private val prefs by lazy {
        getSharedPreferences("lumiya_login", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        val grid = GridManager.defaultGrid()
        binding.gridNameEl.text = grid.name
        binding.firstNameInput.setText(prefs.getString("first", "") ?: "")
        binding.lastNameInput.setText(prefs.getString("last", "") ?: "")

        binding.connectBtn.setOnClickListener {
            val first = binding.firstNameInput.text.toString().trim()
            val last = binding.lastNameInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (first.isEmpty() || password.isEmpty()) {
                showStatus(getString(R.string.login_missing_fields))
                return@setOnClickListener
            }

            prefs.edit()
                .putString("first", first)
                .putString("last", last)
                .apply()

            SLClient.gridName = grid.name
            SLClient.loginUri = grid.loginUri
            SLClient.connection.connect(grid, first, last, password)
        }

        lifecycleScope.launch {
            SLClient.connection.state.collect { state ->
                binding.statusEl.text = SLClient.connection.status.value
                when (state) {
                    ConnectionState.LOGGING_IN, ConnectionState.CONNECTING -> {
                        binding.progressEl.visibility = View.VISIBLE
                        binding.statusEl.visibility = View.VISIBLE
                        binding.connectBtn.isEnabled = false
                    }
                    ConnectionState.CONNECTED -> {
                        binding.progressEl.visibility = View.GONE
                        binding.connectBtn.isEnabled = true
                        if (!navigatedToMain) {
                            navigatedToMain = true
                            startActivity(android.content.Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                    else -> {
                        binding.progressEl.visibility = View.GONE
                        binding.connectBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showStatus(text: String) {
        binding.statusEl.text = text
        binding.statusEl.visibility = View.VISIBLE
    }
}
