package com.example.nodedroidrun

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

class SetupActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var txtDetails: TextView
    private lateinit var logContainer: ScrollView
    private lateinit var txtLog: TextView

    private val SERVER_PORT = 4150

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.parseColor("#FF1A1A1A")

        progressBar  = findViewById(R.id.progress_bar)
        txtStatus    = findViewById(R.id.txt_status)
        txtDetails   = findViewById(R.id.txt_details)
        logContainer = findViewById(R.id.log_container)
        txtLog       = findViewById(R.id.txt_log)

        setupAndProceed()
    }

    private fun setupAndProceed() {
        progressBar.isIndeterminate = true
        txtStatus.text  = "Preparando ambiente..."
        txtDetails.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = File(applicationInfo.nativeLibraryDir)
                val libDir = File(filesDir, "lib").also { it.mkdirs() }

                step("Preparando bibliotecas nativas...")
                setupNativeLibs(nativeDir, libDir)

                step("Preparando certificados TLS...")
                setupCerts()

                step("Verificando Node.js...")
                testNodeVersion(nativeDir, libDir)

                step("Configurando Git...")
                val gitCoreDir = File(filesDir, "git-core")
                setupGit(nativeDir, gitCoreDir)

                step("Verificando Git...")
                testGitVersion(nativeDir, libDir, gitCoreDir)

                step("Preparando servidor de validação...")
                val serverFile = writeServerJs()

                step("Iniciando servidor de teste...")
                val serverProcess = startServer(serverFile, nativeDir, libDir)

                step("Verificando servidor na porta $SERVER_PORT...")
                checkServerHealth()

                step("Finalizando servidor de teste...")
                serverProcess.destroyForcibly()
                serverProcess.waitFor()

                runOnUiThread {
                    txtStatus.text  = "Ambiente pronto!"
                    txtDetails.text = "Iniciando..."
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                fail(e.message ?: "Erro desconhecido")
            }
        }
    }

    private val GIT_SUBCOMMANDS = arrayOf(
        "git", "git-add", "git-am", "git-annotate", "git-apply", "git-archive",
        "git-backfill", "git-bisect", "git-blame", "git-branch", "git-bugreport",
        "git-bundle", "git-cat-file", "git-check-attr", "git-check-ignore",
        "git-check-mailmap", "git-check-ref-format", "git-checkout", "git-checkout--worker",
        "git-checkout-index", "git-cherry", "git-cherry-pick", "git-clean", "git-clone",
        "git-column", "git-commit", "git-commit-graph", "git-commit-tree", "git-config",
        "git-count-objects", "git-credential", "git-credential-cache",
        "git-credential-cache--daemon", "git-credential-store", "git-daemon",
        "git-describe", "git-diagnose", "git-diff", "git-diff-files", "git-diff-index",
        "git-diff-pairs", "git-diff-tree", "git-difftool", "git-fast-export",
        "git-fast-import", "git-fetch", "git-fetch-pack", "git-fmt-merge-msg",
        "git-for-each-ref", "git-for-each-repo", "git-format-patch", "git-fsck",
        "git-fsck-objects", "git-fsmonitor--daemon", "git-gc", "git-get-tar-commit-id",
        "git-grep", "git-hash-object", "git-help", "git-history", "git-hook",
        "git-http-backend", "git-http-fetch", "git-http-push", "git-imap-send",
        "git-index-pack", "git-init", "git-init-db", "git-interpret-trailers",
        "git-last-modified", "git-log", "git-ls-files", "git-ls-remote", "git-ls-tree",
        "git-mailinfo", "git-mailsplit", "git-maintenance", "git-merge", "git-merge-base",
        "git-merge-file", "git-merge-index", "git-merge-ours", "git-merge-recursive",
        "git-merge-subtree", "git-merge-tree", "git-mktag", "git-mktree",
        "git-multi-pack-index", "git-mv", "git-name-rev", "git-notes",
        "git-pack-objects", "git-pack-redundant", "git-pack-refs", "git-patch-id",
        "git-prune", "git-prune-packed", "git-pull", "git-push", "git-range-diff",
        "git-read-tree", "git-rebase", "git-receive-pack", "git-reflog", "git-refs",
        "git-remote", "git-remote-ext", "git-remote-fd", "git-remote-ftp",
        "git-remote-ftps", "git-remote-http", "git-remote-https", "git-repack",
        "git-replace", "git-replay", "git-repo", "git-rerere", "git-reset",
        "git-restore", "git-rev-list", "git-rev-parse", "git-revert", "git-rm",
        "git-send-pack", "git-sh-i18n--envsubst", "git-shell", "git-shortlog",
        "git-show", "git-show-branch", "git-show-index", "git-show-ref",
        "git-sparse-checkout", "git-stage", "git-stash", "git-status", "git-stripspace",
        "git-submodule--helper", "git-switch", "git-symbolic-ref", "git-tag",
        "git-unpack-file", "git-unpack-objects", "git-update-index", "git-update-ref",
        "git-update-server-info", "git-upload-archive", "git-upload-pack", "git-var",
        "git-verify-commit", "git-verify-pack", "git-verify-tag", "git-version",
        "git-whatchanged", "git-worktree", "git-write-tree", "scalar"
    )

    private fun setupCerts() {
        val certDir = File(filesDir, "tls").also { it.mkdirs() }
        val certFile = File(certDir, "cert.pem")
        if (!certFile.exists()) {
            try {
                assets.open("cert.pem").use { input ->
                    certFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                log("cert.pem instalado (${certFile.length()} bytes)")
            } catch (_: Exception) {
                log("cert.pem não encontrado em assets — HTTPS pode falhar")
            }
        }
    }

    private fun setupNativeLibs(nativeDir: File, libDir: File) {
        createVersionedAlias(nativeDir, libDir, "libz.so",        "libz.so.1")
        createVersionedAlias(nativeDir, libDir, "libssl.so",      "libssl.so.3")
        createVersionedAlias(nativeDir, libDir, "libcrypto.so",   "libcrypto.so.3")
        createVersionedAlias(nativeDir, libDir, "libicui18n.so",  "libicui18n.so.78")
        createVersionedAlias(nativeDir, libDir, "libicuuc.so",    "libicuuc.so.78")
        createVersionedAlias(nativeDir, libDir, "libicudata.so",  "libicudata.so.78")
        createVersionedAlias(nativeDir, libDir, "libsqlite3.so",  "libsqlite3.so.0")
        createVersionedAlias(nativeDir, libDir, "libexpat.so",    "libexpat.so.1")

        val nodeFile = File(nativeDir, "libnode.so")
        if (!nodeFile.exists()) {
            error("libnode.so não encontrado em ${nodeFile.absolutePath}")
        }
    }

    private val GIT_SPECIAL_BINARIES = mapOf(
        "git-remote-https" to "libgit_remote_https.so",
        "git-remote-http"  to "libgit_remote_http.so",
        "git-remote-ftp"   to "libgit_remote_ftp.so",
        "git-remote-ftps"  to "libgit_remote_ftps.so",
        "git-http-fetch"   to "libgit_http_fetch.so",
        "git-http-push"    to "libgit_http_push.so",
        "git-imap-send"    to "libgit_imap_send.so"
    )

    private fun setupGit(nativeDir: File, gitCoreDir: File) {
        val gitSrc  = File(nativeDir, "libgit.so")
        if (!gitSrc.exists()) {
            error("libgit.so não encontrado em ${gitSrc.absolutePath}\n\nReconstrua o APK: Build → Clean → Rebuild")
        }
        gitCoreDir.mkdirs()

        val gitBin = File(gitCoreDir, "git")
        val gitSrcPath: Path = gitSrc.toPath()

        if (!gitBin.exists()) {
            try {
                Files.createSymbolicLink(gitBin.toPath(), gitSrcPath)
            } catch (_: Exception) {
                gitSrc.copyTo(gitBin, overwrite = true)
                gitBin.setExecutable(true)
            }
        }

        for (cmd in GIT_SUBCOMMANDS) {
            val linkPath = gitCoreDir.toPath().resolve(cmd)
            if (!linkPath.toFile().exists()) {
                val targetPath: Path = if (cmd in GIT_SPECIAL_BINARIES) {
                    val soName = GIT_SPECIAL_BINARIES[cmd]!!
                    val soFile = File(nativeDir, soName)
                    if (!soFile.exists()) gitSrcPath else soFile.toPath()
                } else {
                    gitSrcPath
                }
                try {
                    Files.createSymbolicLink(linkPath, targetPath)
                } catch (_: Exception) {
                    gitBin.copyTo(linkPath.toFile(), overwrite = false)
                    linkPath.toFile().setExecutable(true)
                }
            }
        }
        log("git-core: ${GIT_SUBCOMMANDS.size} comandos configurados")
    }

    private fun testGitVersion(nativeDir: File, libDir: File, gitCoreDir: File) {
        val gitPath = File(gitCoreDir, "git").absolutePath
        val ldPath  = "${nativeDir.absolutePath}:${libDir.absolutePath}"

        val pb = ProcessBuilder(gitPath, "--version")
        pb.directory(gitCoreDir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", ldPath)
            put("GIT_EXEC_PATH", gitCoreDir.absolutePath)
            put("GIT_SSL_CAINFO", "${filesDir.absolutePath}/tls/cert.pem")
            put("HOME", filesDir.absolutePath)
            put("TMPDIR", cacheDir.absolutePath)
        }

        val process = pb.start()
        val output  = process.inputStream.bufferedReader().readText().trim()
        val exit    = process.waitFor()

        if (exit != 0) {
            error("git --version falhou (exit $exit):\n$output")
        }
        log(output)
    }

    private fun testNodeVersion(nativeDir: File, libDir: File) {
        val nodePath = File(nativeDir, "libnode.so").absolutePath
        val ldPath   = "${nativeDir.absolutePath}:${libDir.absolutePath}"

        val pb = ProcessBuilder("sh", "-c", "$nodePath --version 2>&1; echo \"EXIT:\$?\"")
        pb.environment().apply {
            put("LD_LIBRARY_PATH", ldPath)
            put("HOME", filesDir.absolutePath)
            put("TMPDIR", cacheDir.absolutePath)
        }

        val process = pb.start()
        val output  = process.inputStream.bufferedReader().readText().trim()
        val exit    = process.waitFor()

        if (exit != 0) {
            error("node --version falhou (exit $exit):\n$output")
        }
        val version = output.lines().firstOrNull { it.startsWith("v") }
            ?: error("node --version não retornou versão:\n$output")
        log("Node.js $version")
    }

    private fun writeServerJs(): File {
        val js = """
const http = require('http');
const PORT = $SERVER_PORT;
const server = http.createServer((req, res) => {
    res.writeHead(200);
    res.end('ok');
});
server.listen(PORT, '0.0.0.0', () => {
    console.log('[NodeDroid] OK');
});
server.on('error', (err) => {
    console.error('[NodeDroid] ERRO: ' + err.message);
    process.exit(1);
});
""".trimIndent()

        val file = File(filesDir, "server.js")
        FileWriter(file).use { it.write(js) }
        return file
    }

    private fun startServer(serverFile: File, nativeDir: File, libDir: File): Process {
        val nodePath = File(nativeDir, "libnode.so").absolutePath
        val ldPath   = "${nativeDir.absolutePath}:${libDir.absolutePath}"

        val pb = ProcessBuilder(nodePath, serverFile.absolutePath)
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", ldPath)
            put("HOME", filesDir.absolutePath)
            put("TMPDIR", cacheDir.absolutePath)
        }

        val process = pb.start()
        Thread.sleep(2000)
        if (!process.isAlive) {
            val err = process.inputStream.bufferedReader().readText()
            error("Servidor morreu ao iniciar:\n$err")
        }
        return process
    }

    private fun checkServerHealth() {
        val url = URL("http://127.0.0.1:$SERVER_PORT/")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        conn.disconnect()
        if (code != 200) {
            error("Servidor respondeu HTTP $code (esperado 200)")
        }
    }

    private fun createVersionedAlias(nativeDir: File, libDir: File, srcName: String, dstName: String) {
        val src = File(nativeDir, srcName)
        val dst = File(libDir, dstName)
        if (src.exists() && !dst.exists()) {
            src.copyTo(dst, overwrite = false)
        }
    }

    private fun step(desc: String) {
        runOnUiThread {
            txtStatus.text = desc
            txtDetails.text = ""
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            if (txtLog.text.isEmpty()) txtLog.text = line else txtLog.append("\n$line")
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            progressBar.isIndeterminate = false
            txtStatus.text  = "Erro na configuração"
            txtDetails.text = "Verifique os detalhes abaixo."
            logContainer.visibility = ScrollView.VISIBLE
            txtLog.append("\n\n$message")
        }
    }
}
