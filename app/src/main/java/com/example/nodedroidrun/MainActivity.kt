package com.example.nodedroidrun

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val btnMenu      = findViewById<TextView>(R.id.btn_menu)
        val navView      = findViewById<NavigationView>(R.id.nav_view)

        // TODO: Ao clicar em Login GitHub → fluxo OAuth (Fase 3)
        // TODO: Ao clicar em Adicionar Projeto → diálogo de URL (Fase 3)

        btnMenu.setOnClickListener {
            drawerLayout.open()
        }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_login -> { /* TODO: Fase 3 */ }
                R.id.nav_add_project -> { /* TODO: Fase 3 */ }
            }
            drawerLayout.close()
            true
        }
    }
}
