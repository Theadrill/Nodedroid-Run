package com.example.nodedroidrun

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
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

    companion object {
        private const val GROUP_PROJECTS = 999
    }

    private lateinit var navView: NavigationView
    private lateinit var projectsList: LinearLayout
    private lateinit var placeholder: TextView
    private lateinit var projectsScroll: ScrollView
    private var pendingGitTest = false
    private var projects = listOf<Project>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerLayout.setStatusBarBackgroundColor(android.graphics.Color.parseColor("#FF1A1A1A"))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.parseColor("#FF1A1A1A")

        projectsList = findViewById(R.id.projects_list)
        placeholder = findViewById(R.id.placeholder)
        projectsScroll = findViewById(R.id.projects_scroll)

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
                R.id.nav_test_termux -> handleSetupTermuxClick()
                R.id.nav_add_project -> handleAddProjectClick()
            }
            drawerLayout.close()
            updateMenuTitles()
            true
        }

        refreshProjects()

        ProcessManager.ensureGitSymlinks(this)

        ensureEnvShim()
        ensureNodeShim()

        startService(Intent(this, NodeService::class.java))

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

    // --- Projetos ---

    private fun refreshProjects() {
        projects = ProjectManager.load(this)
        rebuildDrawerProjects()
        rebuildContentProjects()
    }

    private fun rebuildDrawerProjects() {
        val menu = navView.menu
        menu.removeGroup(GROUP_PROJECTS)

        menu.add(GROUP_PROJECTS, View.generateViewId(), 1, "Projetos")
            .setEnabled(false)

        if (projects.isEmpty()) {
            menu.add(GROUP_PROJECTS, View.generateViewId(), 2, "  (nenhum)")
                .setEnabled(false)
        } else {
            for (p in projects) {
                menu.add(GROUP_PROJECTS, View.generateViewId(), 2, "  ${p.name}")
                    .setEnabled(false)
            }
        }
    }

    private fun rebuildContentProjects() {
        projectsList.removeAllViews()
        if (projects.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            projectsScroll.visibility = View.GONE
        } else {
            placeholder.visibility = View.GONE
            projectsScroll.visibility = View.VISIBLE
            for (p in projects) {
                projectsList.addView(createProjectCard(p))
            }
        }
    }

    private fun createProjectCard(project: Project): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp }
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val nameView = TextView(this).apply {
            text = project.name
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#E8E8E8"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(nameView)

        val playBtn = TextView(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            setPadding(24.dp, 8.dp, 8.dp, 8.dp)
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        topRow.addView(playBtn)
        card.addView(topRow)

        val metaView = TextView(this).apply {
            text = "${project.source.uppercase()} · ${project.cloneUrl}"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp }
        }
        card.addView(metaView)

        playBtn.setOnClickListener { view ->
            val projectDir = File(project.path)
            val cfg = NodedroidJson.load(projectDir)
            val popup = PopupMenu(this, view, android.view.Gravity.END)
            if (cfg.commands.isEmpty()) {
                popup.menu.add("npm install")
                popup.menu.add("npm start")
            } else {
                for (entry in cfg.commands) {
                    popup.menu.add(0, 1, popup.menu.size(), entry.label)
                }
            }
            popup.menu.add("Gerenciar comandos")
            popup.setOnMenuItemClickListener { item ->
                val title = item.title.toString()
                when {
                    title == "Gerenciar comandos" -> showCommandManagerDialog(project, projectDir)
                    title == "npm install" -> runProjectCommand(project, "npm install")
                    title == "npm start" -> runProjectCommand(project, "npm start")
                    else -> {
                        val entry = cfg.commands.find { it.label == title }
                        if (entry != null) runProjectCommand(project, entry.command)
                    }
                }
                true
            }
            popup.show()
        }

        card.setOnClickListener {
            Toast.makeText(this, "Abrindo: ${project.name}", Toast.LENGTH_SHORT).show()
        }

        card.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Remover projeto")
                .setMessage("Remover \"${project.name}\"? A pasta local será deletada.")
                .setPositiveButton("Remover") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        ProjectManager.remove(this@MainActivity, project)
                        withContext(Dispatchers.Main) { refreshProjects() }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }

        return card
    }

    private fun runProjectCommand(project: Project, label: String) {
        val nativeDir = applicationInfo.nativeLibraryDir
        val libDir = File(filesDir, "lib")
        val nodePath = File(nativeDir, "libnode.so").absolutePath
        val npmCliJs = File(filesDir, "node_modules/npm/bin/npm-cli.js").absolutePath
        val workDir = File(project.path)
        val gitPath = File(filesDir, "git-core/git").absolutePath

        val cmd: List<String> = when {
            label.startsWith("npm ") -> {
                val args = label.removePrefix("npm ")
                listOf(nodePath, npmCliJs) + args.split(" ")
            }
            label.startsWith("npx ") -> {
                val args = label.removePrefix("npx ")
                val npxCliJs = File(filesDir, "node_modules/npm/bin/npx-cli.js").absolutePath
                listOf(nodePath, npxCliJs) + args.split(" ")
            }
            label.startsWith("node ") -> {
                val args = label.removePrefix("node ")
                listOf(nodePath) + args.split(" ")
            }
            label.startsWith("git ") -> {
                val args = label.removePrefix("git ")
                listOf(gitPath) + args.split(" ")
            }
            else -> listOf("sh", "-c", "cd \"${workDir.absolutePath}\" && $label 2>&1")
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(project.name)
            .setMessage("Executando: $label...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val output = StringBuilder()
            try {
                withContext(Dispatchers.IO) {
                    val ldPath = "$nativeDir:${libDir.absolutePath}"
                    val selfBin = File(filesDir, "bin").absolutePath
                    val binPath = buildList {
                        add(selfBin)
                        add(nativeDir)
                        add("${filesDir.absolutePath}/git-core")
                        add("${workDir.absolutePath}/node_modules/.bin")
                        add(System.getenv("PATH") ?: "/system/bin")
                    }.joinToString(":")

                    val ldPathFull = buildList {
                        add(nativeDir)
                        add(libDir.absolutePath)
                        add("/system/lib64")
                        add("/system/lib")
                        add("/vendor/lib64")
                        add("/vendor/lib")
                    }.joinToString(":")

                    val env = mapOf(
                        "LD_LIBRARY_PATH" to ldPathFull,
                        "PATH" to binPath,
                        "HOME" to filesDir.absolutePath,
                        "TMPDIR" to cacheDir.absolutePath,
                        "NODE_PATH" to "${filesDir.absolutePath}/node_modules:${workDir.absolutePath}/node_modules",
                        "GIT_EXEC_PATH" to "${filesDir.absolutePath}/git-core",
                        "GIT_SSL_CAINFO" to "${filesDir.absolutePath}/tls/cert.pem",
                        "CURL_CA_BUNDLE" to "${filesDir.absolutePath}/tls/cert.pem"
                    )

                    val pb = ProcessBuilder(cmd).apply {
                        directory(workDir)
                        redirectErrorStream(true)
                        environment().putAll(env)
                    }
                    val process = pb.start()
                    val text = process.inputStream.bufferedReader().readText().trim()
                    val exit = process.waitFor()
                    output.append(text)
                    if (exit != 0) output.append("\n(exit: $exit)")
                }
            } catch (e: Exception) {
                output.append("\nERRO: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                showOutputDialog("$label — ${project.name}", output.toString().ifEmpty { "(sem saída)" })
            }
        }
    }

    private fun showCommandManagerDialog(project: Project, projectDir: File) {
        var cfg = NodedroidJson.load(projectDir)
        val items = mutableListOf<String>()
        for (entry in cfg.commands) {
            items.add("${entry.label} → ${entry.command}")
        }
        if (items.isEmpty()) items.add("(nenhum comando personalizado)")

        AlertDialog.Builder(this)
            .setTitle("Gerenciar comandos — ${project.name}")
            .setItems(items.toTypedArray()) { _, which ->
                if (cfg.commands.isEmpty()) return@setItems
                val entry = cfg.commands[which]
                AlertDialog.Builder(this)
                    .setTitle("Editar comando")
                    .setMessage("${entry.label}\n${entry.command}")
                    .setPositiveButton("Excluir") { _, _ ->
                        cfg = cfg.copy(commands = cfg.commands.toMutableList().apply { removeAt(which) })
                        NodedroidJson.save(projectDir, cfg)
                        Toast.makeText(this, "Comando removido", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Editar", null)
                    .show()
            }
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Adicionar") { _, _ ->
                showAddCommandDialog(project, projectDir)
            }
            .show()
    }

    private fun showAddCommandDialog(project: Project, projectDir: File) {
        val input = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40.dp, 16.dp, 40.dp, 0)
        }
        val labelInput = EditText(this).apply {
            hint = "Nome do comando (ex: Iniciar servidor)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#888888"))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp }
        }
        val cmdInput = EditText(this).apply {
            hint = "Comando (ex: npm run dev)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#888888"))
            setSingleLine(true)
        }
        input.addView(labelInput)
        input.addView(cmdInput)

        AlertDialog.Builder(this)
            .setTitle("Adicionar comando")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val label = labelInput.text.toString().trim()
                val cmd = cmdInput.text.toString().trim()
                if (label.isEmpty() || cmd.isEmpty()) {
                    Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cfg = NodedroidJson.load(projectDir)
                val updated = cfg.copy(commands = cfg.commands + CommandEntry(label, cmd))
                NodedroidJson.save(projectDir, updated)
                Toast.makeText(this, "Comando adicionado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // --- Adicionar Projeto ---

    private fun handleAddProjectClick() {
        val items = if (GitHubOAuth.isLoggedIn(this)) {
            arrayOf("Clonar do GitHub (lista de repositórios)", "Clonar via URL HTTP")
        } else {
            arrayOf("Clonar via URL HTTP")
        }

        AlertDialog.Builder(this)
            .setTitle("Adicionar Projeto")
            .setItems(items) { _, which ->
                if (GitHubOAuth.isLoggedIn(this) && which == 0) {
                    showGitHubRepoList()
                } else {
                    showCloneUrlDialog()
                }
            }
            .show()
    }

    private fun showGitHubRepoList() {
        val progress = AlertDialog.Builder(this)
            .setMessage("Carregando repositórios...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val repos = withContext(Dispatchers.IO) {
                GitHubOAuth.fetchRepos(this@MainActivity)
            }
            progress.dismiss()

            if (repos.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nenhum repositório encontrado ou erro de conexão", Toast.LENGTH_LONG).show()
                return@launch
            }

            val listLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scrollView = ScrollView(this@MainActivity).apply {
                addView(listLayout)
            }

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Seus repositórios")
                .setView(scrollView)
                .setNegativeButton("Cancelar", null)
                .create()

            for (repo in repos) {
                val item = TextView(this@MainActivity).apply {
                    text = repo.fullName
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#E8E8E8"))
                    setPadding(24, 16, 24, 16)
                    setOnClickListener {
                        dialog.dismiss()
                        startClone(repo.cloneUrl, useToken = true)
                    }
                }
                listLayout.addView(item)
                val divider = View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#2A2A2A"))
                }
                listLayout.addView(divider)
            }

            try {
                dialog.show()
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Erro ao abrir lista", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCloneUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://github.com/usuario/repo.git"
            setTextColor(android.graphics.Color.parseColor("#E8E8E8"))
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            setPadding(24, 16, 24, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("URL do repositório:")
            .setView(input)
            .setPositiveButton("Clonar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    startClone(url, false)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startClone(cloneUrl: String, useToken: Boolean) {
        val output = StringBuilder()
        val mainHandler = Handler(Looper.getMainLooper())
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Clonando...")
            .setMessage("Conectando ao repositório...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    val token = if (useToken) GitHubOAuth.getToken(this@MainActivity) else null
                    cloneRepo(cloneUrl, token) { line ->
                        mainHandler.post {
                            output.appendLine(line)
                            val lastLine = output.lines().lastOrNull { it.isNotBlank() } ?: line
                            progressDialog.setMessage(lastLine.take(80))
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (project != null) {
                        ProjectManager.add(this@MainActivity, project)
                        refreshProjects()
                        Toast.makeText(this@MainActivity, "Projeto \"${project.name}\" clonado!", Toast.LENGTH_SHORT).show()
                    } else {
                        showOutputDialog("Erro no clone", output.toString().ifEmpty { "Falha ao clonar. Verifique a URL." })
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                showOutputDialog("Erro no clone", "ERRO: ${e.message}")
            }
        }
    }

    private fun cloneRepo(
        cloneUrl: String,
        token: String?,
        onLine: (String) -> Unit
    ): Project? {
        val name = Project.nameFromUrl(cloneUrl)
        val id = java.util.UUID.randomUUID().toString().take(8)
        val projectDir = File(ProjectManager.getProjectsDir(this), id)
        projectDir.mkdirs()

        val url = if (token != null && cloneUrl.contains("github.com")) {
            cloneUrl.replace("https://", "https://${token}@")
        } else {
            cloneUrl
        }

        val gitPath = File(filesDir, "git-core/git").absolutePath
        val nativeDir = applicationInfo.nativeLibraryDir
        val libDir = File(filesDir, "lib")
        val ldPath = "$nativeDir:${libDir.absolutePath}"

        val pb = ProcessBuilder(gitPath, "clone", "--progress", url, projectDir.absolutePath).apply {
            redirectErrorStream(true)
            environment().apply {
                put("LD_LIBRARY_PATH", ldPath)
                put("GIT_EXEC_PATH", File(filesDir, "git-core").absolutePath)
                put("GIT_SSL_CAINFO", "${filesDir.absolutePath}/tls/cert.pem")
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                if (token != null) {
                    put("GIT_TERMINAL_PROMPT", "0")
                    put("GCM_INTERACTIVE", "never")
                }
            }
        }

        val process = pb.start()
        process.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                onLine(line)
                line = reader.readLine()
            }
        }
        val exit = process.waitFor()

        return if (exit == 0) {
            Project(
                id = id,
                name = name,
                path = projectDir.absolutePath,
                cloneUrl = cloneUrl,
                source = if (token != null) "github" else "url"
            )
        } else {
            projectDir.deleteRecursively()
            null
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
                put("GIT_SSL_CAINFO", "${filesDir.absolutePath}/tls/cert.pem")
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
            }
        }

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        return output.ifEmpty { "(sem saída)" }
    }

    // --- Setup Termux ---

    private var termuxHost = "127.0.0.1"
    private var termuxPort = 9876

    private fun isTermuxSetupDone(): Boolean {
        return getSharedPreferences("nodedroid_setup", Context.MODE_PRIVATE).getBoolean("termux_setup_done", false)
    }

    private fun markTermuxSetupDone() {
        getSharedPreferences("nodedroid_setup", Context.MODE_PRIVATE).edit().putBoolean("termux_setup_done", true).apply()
    }

    private fun handleSetupTermuxClick() {
        val termuxInstalled = try { packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }
        if (!termuxInstalled) {
            AlertDialog.Builder(this)
                .setTitle("Termux não encontrado")
                .setMessage("Instale o Termux via F-Droid para continuar.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (isTermuxSetupDone()) {
            checkTermuxStatus()
        } else {
            showTermuxGuide()
        }
    }

    private fun showTermuxGuide() {
        val setupCmd = "echo y | termux-setup-storage && " +
            "pkg update -y && " +
            "pkg install -y busybox && " +
            "echo '' && " +
            "echo '==============================' && " +
            "echo '  SERVIDOR PRONTO!' && " +
            "echo '  Deixe este terminal aberto.' && " +
            "echo '  Volte ao NodedroidRun e' && " +
            "echo '  clique em Próximo passo.' && " +
            "echo '==============================' && " +
            "nc -lk -p 9876 -e /bin/bash"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val guideText = TextView(this).apply {
            text = "1. Copie o comando abaixo\n" +
                "2. Abra o app Termux\n" +
                "3. Cole o comando e pressione Enter\n" +
                "4. Quando aparecer \"SERVIDOR PRONTO\"\n" +
                "   o Termux está configurado\n" +
                "5. Deixe o Termux aberto e volte aqui"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#E8E8E8"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        }
        container.addView(guideText)

        val cmdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        }

        val cmdBox = EditText(this).apply {
            setText(setupCmd)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D0D"))
            setPadding(16, 12, 16, 12)
            isFocusable = false
            inputType = android.text.InputType.TYPE_NULL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("termux_setup", setupCmd))
                Toast.makeText(this@MainActivity, "Comando copiado!", Toast.LENGTH_SHORT).show()
            }
        }
        cmdRow.addView(cmdBox)

        val copyBtn = TextView(this).apply {
            text = "📋"
            textSize = 20f
            setPadding(12.dp, 8.dp, 4.dp, 8.dp)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("termux_setup", setupCmd))
                Toast.makeText(this@MainActivity, "Comando copiado!", Toast.LENGTH_SHORT).show()
            }
        }
        cmdRow.addView(copyBtn)
        container.addView(cmdRow)

        AlertDialog.Builder(this)
            .setTitle("Setup Termux — Passo 1")
            .setView(container)
            .setPositiveButton("Próximo passo") { _, _ ->
                val progressDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Setup Termux")
                    .setMessage("Verificando conexão com o Termux...")
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                lifecycleScope.launch {
                    var connected = false
                    try {
                        withContext(Dispatchers.IO) {
                            val s = java.net.Socket(termuxHost, termuxPort)
                            s.close()
                            connected = true
                        }
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        if (connected) {
                            runTermuxSetup()
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Servidor não encontrado")
                                .setMessage("Não foi possível conectar ao Termux na porta 9876.\n\nCertifique-se de que o comando foi executado e o terminal mostra:\n\n  SERVIDOR PRONTO!\n\nTente novamente.")
                                .setPositiveButton("Tentar novamente") { _, _ -> runTermuxSetup() }
                                .setNegativeButton("Voltar", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkTermuxStatus() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Setup Termux")
            .setMessage("Checando servidor...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            val summary = mutableListOf<String>()

            fun update(msg: String) {
                lines.add(msg)
                runOnUiThread { progressDialog.setMessage(lines.joinToString("\n")) }
            }

            try {
                withContext(Dispatchers.IO) {
                    fun tcpExec(cmd: String, timeout: Int = 15000): String {
                        val socket = java.net.Socket(termuxHost, termuxPort)
                        socket.soTimeout = timeout
                        val writer = socket.getOutputStream().bufferedWriter()
                        val reader = socket.getInputStream().bufferedReader()
                        writer.write(cmd)
                        writer.newLine()
                        writer.write("echo '___END___'")
                        writer.newLine()
                        writer.flush()
                        val sb = StringBuilder()
                        var line = reader.readLine()
                        while (line != null) {
                            if (line.contains("___END___")) break
                            sb.appendLine(line)
                            line = reader.readLine()
                        }
                        socket.close()
                        return sb.toString().trim()
                    }

                    // Check 1: servidor
                    try {
                        tcpExec("echo OK")
                        update("✅ Servidor ligado na porta $termuxPort")
                        summary.add("✅ Servidor ligado na porta $termuxPort")
                    } catch (e: java.net.ConnectException) {
                        update("❌ Servidor NÃO encontrado na porta $termuxPort")
                        summary.add("❌ Servidor NÃO encontrado na porta $termuxPort")
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            showTermuxGuide()
                        }
                        return@withContext
                    }

                    // Check 2: pacotes
                    update("Checando pacotes necessários...")
                    val checkCmd = "echo 'NODE='$(node --version 2>&1);" +
                        "echo 'NPM='$(npm --version 2>&1);" +
                        "echo 'PYTHON='$(python3 --version 2>&1);" +
                        "echo 'CLANG='$(clang --version 2>&1 | head -1);" +
                        "echo 'MAKE='$(make --version 2>&1 | head -1);" +
                        "echo 'BINUTILS='$(ld --version 2>&1 | head -1)"

                    val output = tcpExec(checkCmd)
                    val missing = mutableListOf<String>()
                    val pkgs = mapOf("NODE" to "nodejs", "NPM" to "nodejs", "PYTHON" to "python3", "CLANG" to "clang", "MAKE" to "make", "BINUTILS" to "binutils")
                    val foundPkgs = mutableSetOf<String>()

                    for (line in output.lines()) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0]
                            val version = parts[1].trim()
                            val pkg = pkgs[key]
                            if (version.isNotBlank() && !version.contains("not found", true) && pkg != null) {
                                foundPkgs.add(pkg)
                            }
                        }
                    }
                    for (pkg in pkgs.values) {
                        if (pkg !in foundPkgs) missing.add(pkg)
                    }

                    if (missing.isNotEmpty()) {
                        update("⚠️ Pacotes faltando: ${missing.joinToString(", ")}")
                        update("Instalando automaticamente...")
                        val installCmd = "pkg install -y ${missing.joinToString(" ")} 2>&1"
                        tcpExec(installCmd, timeout = 300000)
                        update("✅ Pacotes instalados")
                    }

                    update("✅ Pacotes necessários instalados")
                    summary.add("✅ Pacotes necessários instalados")
                    summary.add("")
                    summary.add("A configuração do Termux foi feita com sucesso!")
                    summary.add("Mantenha-o aberto executando em segundo plano.")
                }
            } catch (e: Exception) {
                update("ERRO: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                val finalMsg = summary.joinToString("\n")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Setup Termux")
                    .setMessage(finalMsg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun runTermuxSetup() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Setup Termux")
            .setMessage("Conectando...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val logLines = mutableListOf<String>()

                    fun updateDialog(text: String) {
                        logLines.add(text)
                        // Keep last 12 lines visible
                        val display = logLines.takeLast(12).joinToString("\n")
                        runOnUiThread {
                            progressDialog.setMessage(display.ifEmpty { "..." })
                        }
                    }

                    fun tcpExec(cmd: String, onLine: (String) -> Unit) {
                        val socket = java.net.Socket(termuxHost, termuxPort)
                        socket.soTimeout = 300000
                        val writer = socket.getOutputStream().bufferedWriter()
                        val reader = socket.getInputStream().bufferedReader()
                        writer.write(cmd)
                        writer.newLine()
                        writer.write("echo '___END___'")
                        writer.newLine()
                        writer.flush()
                        var line = reader.readLine()
                        while (line != null) {
                            if (line.contains("___END___")) break
                            onLine(line)
                            line = reader.readLine()
                        }
                        socket.close()
                    }

                    // Testa conexão
                    updateDialog("Conectando ao Termux...")
                    try {
                        tcpExec("echo OK") { }
                    } catch (e: java.net.ConnectException) {
                        runOnUiThread {
                            progressDialog.dismiss()
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Erro")
                                .setMessage("Não foi possível conectar ao Termux.\n\nExecute no Termux e mantenha rodando:\n\n  nc -lk -p 9876 -e /bin/bash")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        return@withContext
                    }

                    // Instala dependências
                    val installCmd = "pkg update -y 2>&1 && pkg install -y nodejs python3 clang make binutils busybox 2>&1"
                    updateDialog("Instalando dependências...")

                    tcpExec(installCmd) { line ->
                        updateDialog(line)
                    }

                    // Verifica instalação
                    updateDialog("Verificando...")
                    val verifyResults = mutableMapOf<String, String>()
                    val verifyCmd = "echo 'NODE='$(node --version 2>&1);" +
                        "echo 'NPM='$(npm --version 2>&1);" +
                        "echo 'PYTHON='$(python3 --version 2>&1 | sed 's/Python //');" +
                        "echo 'CLANG='$(clang --version 2>&1 | head -1 | grep -o '[0-9.]\\+' | head -1);" +
                        "echo 'MAKE='$(make --version 2>&1 | head -1 | grep -o '[0-9.]\\+' | head -1);" +
                        "echo 'BINUTILS='$(ld --version 2>&1 | head -1 | grep -o '[0-9.]\\+' | head -1)"

                    tcpExec(verifyCmd) { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            verifyResults[parts[0]] = parts[1]
                        }
                    }

                    // Monta tabela de resultados
                    fun pad(s: String, len: Int) = (s + " ".repeat(len)).take(len)
                    val table = buildString {
                        appendLine("╔════════════╤═══════════════╤══════╗")
                        appendLine("║ Ferramenta │ Versão        │  OK  ║")
                        appendLine("╠════════════╪═══════════════╪══════╣")
                        fun row(name: String, version: String?) {
                            val v = version?.trim() ?: ""
                            val ok = v.isNotBlank() && !v.contains("nao", true) && v != "—"
                            appendLine("║ " + pad(name, 10) + " │ " + pad(v.take(13), 13) + " │ " + (if (ok) " ✅ " else " ❌ ") + " ║")
                        }
                        row("Node.js", verifyResults["NODE"])
                        row("npm", verifyResults["NPM"])
                        row("Python", verifyResults["PYTHON"])
                        row("Clang", verifyResults["CLANG"])
                        row("Make", verifyResults["MAKE"])
                        row("Binutils", verifyResults["BINUTILS"])
                        appendLine("╚════════════╧═══════════════╧══════╝")
                    }

                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        markTermuxSetupDone()
                        showOutputDialog("Setup Termux", table)
                    }
                    return@withContext
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showOutputDialog("Setup Termux", "ERRO: ${e.message}")
                }
            }
        }
    }

    private fun ensureEnvShim() {
        val binDir = File(filesDir, "bin").also { it.mkdirs() }
        val envShim = File(binDir, "env")
        if (!envShim.exists()) {
            envShim.writeText("""#!/system/bin/sh
while [ $# -gt 0 ]; do
  case "$1" in
    *=*) shift ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] && exec "$@"
""")
            envShim.setExecutable(true)
        }
    }

    private fun ensureNodeShim() {
        val binDir = File(filesDir, "bin").also { it.mkdirs() }
        val link = File(binDir, "node")
        val target = File(applicationInfo.nativeLibraryDir, "libnode_shim.so")
        if (target.exists() && !link.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            } catch (_: Exception) {
                target.copyTo(link, overwrite = true)
                link.setExecutable(true)
            }
        }
    }

    private fun showOutputDialog(title: String, message: String) {
        val shortMsg = if (message.length > 20000) {
            message.take(20000) + "\n\n... (truncado)"
        } else {
            message
        }

        val scrollView = ScrollView(this).apply {
            val tv = TextView(context).apply {
                text = shortMsg
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#E8E8E8"))
                setPadding(24, 24, 24, 24)
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            addView(tv)
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D0D"))
        }

        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(shortMsg.take(500))
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
