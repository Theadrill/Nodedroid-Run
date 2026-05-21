package com.example.nodedroidrun

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerLayout.setStatusBarBackgroundColor(android.graphics.Color.parseColor("#FF1A1A1A"))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.parseColor("#FF1A1A1A")
        val btnMenu      = findViewById<TextView>(R.id.btn_menu)
        val navView      = findViewById<NavigationView>(R.id.nav_view)

        btnMenu.setOnClickListener {
            drawerLayout.open()
        }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_login -> {
                    if (GitHubOAuth.isLoggedIn(this)) {
                        GitHubOAuth.logout(this)
                        Toast.makeText(this, "Desconectado do GitHub", Toast.LENGTH_SHORT).show()
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
            updateMenuTitles(navView)
            true
        }

        handleOAuthCallback(intent)
        updateMenuTitles(navView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (GitHubOAuth.handleCallback(this, uri)) {
            Toast.makeText(this, "Conectado ao GitHub!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMenuTitles(navView: NavigationView) {
        val menu = navView.menu
        val loginItem = menu.findItem(R.id.nav_login)
        loginItem.title = if (GitHubOAuth.isLoggedIn(this)) "Logout GitHub" else "Login GitHub"
    }
}
