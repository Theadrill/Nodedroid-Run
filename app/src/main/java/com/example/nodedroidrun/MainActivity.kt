package com.example.nodedroidrun

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var navView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerLayout.setStatusBarBackgroundColor(android.graphics.Color.parseColor("#FF1A1A1A"))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.parseColor("#FF1A1A1A")
        navView = findViewById(R.id.nav_view)
        navView.post {
            val header = navView.getHeaderView(0)
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
            val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
            header.setPadding(horizontalPadding, statusBarHeight, horizontalPadding, horizontalPadding)
        }
        val btnMenu = findViewById<TextView>(R.id.btn_menu)

        btnMenu.setOnClickListener { drawerLayout.open() }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_login -> {
                    if (GitHubOAuth.isLoggedIn(this)) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Desconectar")
                            .setMessage("Tem certeza que deseja sair da sua conta GitHub?")
                            .setPositiveButton("Sair") { _, _ ->
                                GitHubOAuth.logout(this)
                                Toast.makeText(this, "Desconectado do GitHub", Toast.LENGTH_SHORT).show()
                                updateMenuTitles()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    } else {
                        try {
                            GitHubOAuth.startLogin(this)
                        } catch (e: Exception) {
                            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                R.id.nav_add_project -> {
                    Toast.makeText(this, "Em breve...", Toast.LENGTH_SHORT).show()
                }
            }
            drawerLayout.close()
            updateMenuTitles()
            true
        }

        handleOAuthCallback(intent)
        updateMenuTitles()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        val code = GitHubOAuth.extractCode(uri) ?: return

        lifecycleScope.launch {
            val success = GitHubOAuth.exchangeCode(this@MainActivity, code)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Conectado ao GitHub!", Toast.LENGTH_SHORT).show()
                }
                updateMenuTitles()
            }
        }
    }

    private fun updateMenuTitles() {
        val menu = navView.menu
        val loginItem = menu.findItem(R.id.nav_login)
        val userName = GitHubOAuth.getUserLogin(this)
        loginItem.title = if (GitHubOAuth.isLoggedIn(this)) {
            if (userName != null) "Logado: $userName" else "Logado"
        } else {
            "Login GitHub"
        }
    }
}
