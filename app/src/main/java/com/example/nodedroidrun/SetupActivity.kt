package com.example.nodedroidrun

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL

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

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
                return
            }
        }
        setupAndProceed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            setupAndProceed()
        }
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
                ProcessManager.ensureGitSymlinks(this@SetupActivity)

                step("Verificando Git...")
                val gitCoreDir = File(filesDir, "git-core")
                testGitVersion(nativeDir, libDir, gitCoreDir)

                step("Instalando npm...")
                setupNpm()

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

    private fun setupNpm() {
        val npmDir = File(filesDir, "node_modules/npm")
        if (npmDir.isDirectory && npmDir.listFiles()?.isNotEmpty() == true) return
        npmDir.mkdirs()

        try {
            assets.open("npm.zip").use { zipInput ->
                java.util.zip.ZipInputStream(zipInput).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(npmDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            log("npm instalado (${npmDir.listFiles()?.size ?: 0} itens)")
        } catch (_: Exception) {
            log("npm.zip não encontrado em assets — npm não disponível")
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
