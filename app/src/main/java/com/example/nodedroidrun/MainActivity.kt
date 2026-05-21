package com.example.nodedroidrun

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var navView: NavigationView
    private var pendingGitTest = false

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
                R.id.nav_login -> handleLoginClick()
                R.id.nav_test_git -> handleTestGitClick()
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

    // --- Login ---

    private fun handleLoginClick() {
        if (GitHubOAuth.isLoggedIn(this)) {
            AlertDialog.Builder(this)
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

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        val code = GitHubOAuth.extractCode(uri) ?: return

        lifecycleScope.launch {
            val success = GitHubOAuth.exchangeCode(this@MainActivity, code)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Conectado ao GitHub!", Toast.LENGTH_SHORT).show()
                    if (pendingGitTest) {
                        pendingGitTest = false
                        configureGitAndTest()
                    }
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

    // --- Testar Git ---

    private fun handleTestGitClick() {
        if (!GitHubOAuth.isLoggedIn(this)) {
            AlertDialog.Builder(this)
                .setTitle("Git sem login")
                .setMessage("O Git precisa de nome e email para commits.\nDeseja fazer login no GitHub?")
                .setPositiveButton("Sim") { _, _ ->
                    pendingGitTest = true
                    try {
                        GitHubOAuth.startLogin(this)
                    } catch (e: Exception) {
                        pendingGitTest = false
                        Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Testar sem login") { _, _ ->
                    runBasicGitTest()
                }
                .show()
        } else {
            configureGitAndTest()
        }
    }

    private fun configureGitAndTest() {
        lifecycleScope.launch {
            val output = StringBuilder()
            try {
                withContext(Dispatchers.IO) {
                    val name = GitHubOAuth.getUserName(this@MainActivity) ?: "Nodedroid User"
                    val email = GitHubOAuth.getGitEmail(this@MainActivity)

                    output.appendLine("=== Configurando Git ===")
                    output.appendLine("user.name  = $name")
                    output.appendLine("user.email = $email")
                    output.appendLine()

                    writeGitConfig(name, email)

                    runGitTests(output, name, email)
                }
            } catch (e: Exception) {
                output.appendLine("\nERRO: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                showOutputDialog("Teste do Git", output.toString())
            }
        }
    }

    private fun runBasicGitTest() {
        lifecycleScope.launch {
            val output = StringBuilder()
            try {
                withContext(Dispatchers.IO) {
                    output.appendLine("=== Teste básico do Git (sem commit) ===")
                    output.appendLine()
                    runGitTests(output, null, null)
                }
            } catch (e: Exception) {
                output.appendLine("\nERRO: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                showOutputDialog("Teste do Git", output.toString())
            }
        }
    }

    private fun writeGitConfig(name: String, email: String) {
        val gitconfig = File(filesDir, ".gitconfig")
        val content = """
[user]
	name = $name
	email = $email
""".trimStart()
        FileWriter(gitconfig).use { it.write(content) }
    }

    private fun runGitTests(output: StringBuilder, userName: String?, userEmail: String?) {
        val nativeDir = applicationInfo.nativeLibraryDir
        val libDir = File(filesDir, "lib")
        val ldPath = "$nativeDir:${libDir.absolutePath}"
        val gitPath = File(filesDir, "git-core/git").absolutePath

        output.appendLine("--- git --version ---")
        output.appendLine(runCmd(gitPath, ldPath, null, "--version"))
        output.appendLine()

        val testDir = File(cacheDir, "git-test-${System.currentTimeMillis()}")
        testDir.mkdirs()

        try {
            output.appendLine("--- git init ---")
            output.appendLine(runCmd(gitPath, ldPath, testDir, "init", "-b", "main"))
            output.appendLine()

            if (userName != null && userEmail != null) {
                output.appendLine("--- git config user.name ---")
                runCmd(gitPath, ldPath, testDir, "config", "user.name", userName)
                output.appendLine("--- git config user.email ---")
                runCmd(gitPath, ldPath, testDir, "config", "user.email", userEmail)
                output.appendLine("OK")
                output.appendLine()
            }

            output.appendLine("--- criando arquivo de teste ---")
            FileWriter(File(testDir, "hello.txt")).use { it.write("Hello from Nodedroid Run!\n") }
            output.appendLine("hello.txt criado")
            output.appendLine()

            output.appendLine("--- git status ---")
            output.appendLine(runCmd(gitPath, ldPath, testDir, "status", "--short"))
            output.appendLine()

            output.appendLine("--- git add ---")
            output.appendLine(runCmd(gitPath, ldPath, testDir, "add", "."))
            output.appendLine()

            output.appendLine("--- git status (staged) ---")
            output.appendLine(runCmd(gitPath, ldPath, testDir, "status", "--short"))
            output.appendLine()

            if (userName != null && userEmail != null) {
                output.appendLine("--- git commit ---")
                output.appendLine(runCmd(gitPath, ldPath, testDir, "commit", "-m", "test commit via Nodedroid Run"))
                output.appendLine()

                output.appendLine("--- git log ---")
                output.appendLine(runCmd(gitPath, ldPath, testDir, "log", "--oneline"))
                output.appendLine()
            } else {
                output.appendLine("(commit ignorado — sem user.name/email configurados)")
                output.appendLine()
            }

            output.appendLine("=== Todos os testes passaram! ===")
        } finally {
            testDir.deleteRecursively()
        }
    }

    private fun runCmd(gitPath: String, ldPath: String, workDir: File?, vararg args: String): String {
        val cmd = mutableListOf(gitPath)
        cmd.addAll(args)

        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            if (workDir != null) directory(workDir)
            environment().apply {
                put("LD_LIBRARY_PATH", ldPath)
                put("GIT_EXEC_PATH", File(filesDir, "git-core").absolutePath)
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
            }
        }

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        return output.ifEmpty { "(sem saída)" }
    }

    private fun showOutputDialog(title: String, message: String) {
        val scrollView = ScrollView(this).apply {
            val tv = TextView(context).apply {
                text = message
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#E8E8E8"))
                setPadding(24, 24, 24, 24)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            addView(tv)
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D0D"))
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
    }
}
