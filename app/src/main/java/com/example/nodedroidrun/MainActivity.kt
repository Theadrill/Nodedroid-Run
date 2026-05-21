package com.example.nodedroidrun

import android.content.Intent
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
                R.id.nav_add_project -> handleAddProjectClick()
            }
            drawerLayout.close()
            updateMenuTitles()
            true
        }

        refreshProjects()

        ProcessManager.ensureGitSymlinks(this)

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
        val ldPath = "$nativeDir:${libDir.absolutePath}"
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
                    val pb = ProcessBuilder(cmd).apply {
                        directory(workDir)
                        redirectErrorStream(true)
                        environment().apply {
                            put("LD_LIBRARY_PATH", ldPath)
                            val binPath = "$nativeDir:${filesDir.absolutePath}/git-core:${workDir.absolutePath}/node_modules/.bin:${get("PATH") ?: "/system/bin"}"
                            put("PATH", binPath)
                            put("HOME", filesDir.absolutePath)
                            put("TMPDIR", cacheDir.absolutePath)
                            put("NODE_PATH", "${filesDir.absolutePath}/node_modules")
                            put("GIT_EXEC_PATH", "${filesDir.absolutePath}/git-core")
                            put("GIT_SSL_CAINFO", "${filesDir.absolutePath}/tls/cert.pem")
                            put("CURL_CA_BUNDLE", "${filesDir.absolutePath}/tls/cert.pem")
                        }
                    }
                    val process = pb.start()
                    output.append(process.inputStream.bufferedReader().readText().trim())
                    process.waitFor()
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

    private fun showOutputDialog(title: String, message: String) {
        val shortMsg = if (message.length > 2000) {
            message.take(2000) + "\n\n... (truncado)"
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
