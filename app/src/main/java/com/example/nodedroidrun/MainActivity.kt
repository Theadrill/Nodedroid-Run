package com.example.nodedroidrun

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var txtTerminal: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var txtServerBadge: TextView
    private lateinit var txtStatusBar: TextView

    private val SERVER_ID   = "hello-world-server"
    private val SERVER_PORT = 4150

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtTerminal   = findViewById(R.id.txt_terminal)
        scrollView    = findViewById(R.id.scroll_terminal)
        txtServerBadge = findViewById(R.id.txt_server_badge)
        txtStatusBar  = findViewById(R.id.txt_status_bar)

        startForegroundService(Intent(this, NodeService::class.java))
        runStartupSequence()
    }

    // ── passo a passo visível na tela ─────────────────────────────────────
    private fun runStartupSequence() {
        lifecycleScope.launch(Dispatchers.IO) {

            log("╔══════════════════════════════════╗")
            log("║     NodeDroid — Startup Log      ║")
            log("╚══════════════════════════════════╝")
            log("")

            // ── PASSO 1: Info do dispositivo ────────────────────────────
            step("1", "Verificando dispositivo")
            val abi     = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "desconhecida"
            val sdk     = android.os.Build.VERSION.SDK_INT
            val model   = android.os.Build.MODEL
            log("  Modelo   : $model")
            log("  API      : $sdk (Android ${android.os.Build.VERSION.RELEASE})")
            log("  ABI      : $abi")
            log("")

            // ── PASSO 2: Verificar binários ──────────────────────────────
            step("2", "Verificando binários instalados")
            val binDir   = ProcessManager.getBinDir(this@MainActivity)
            val nodeFile = File(ProcessManager.getNodePath(this@MainActivity))
            log("  Pasta bin: ${binDir.absolutePath}")

            if (!nodeFile.exists()) {
                logError("  ERRO: Binário 'node' não encontrado!")
                logError("  Reinstale o app ou limpe os dados.")
                badge("✗ ERRO", "#FF4444")
                statusBar("node não encontrado em ${binDir.absolutePath}")
                return@launch
            }
            log("  node     : ${nodeFile.absolutePath}")
            log("  Tamanho  : ${nodeFile.length() / 1024} KB")
            log("  Exec?    : ${nodeFile.canExecute()}")

            // Listar todas as .so disponíveis
            val soFiles = binDir.listFiles { f -> f.name.endsWith(".so") || f.name.contains(".so.") }
            log("  Libs .so : ${soFiles?.size ?: 0} arquivo(s)")
            soFiles?.forEach { log("    • ${it.name}") }
            log("")

            // ── PASSO 3: Testar node --version ───────────────────────────
            step("3", "Testando 'node --version'")
            statusBar("Executando: node --version")

            // Usa sh -c para garantir captura de erro do linker no stderr
            val versionCmd = listOf(
                "sh", "-c",
                "${nodeFile.absolutePath} --version 2>&1; echo \"EXIT:\$?\""
            )
            val versionProcess = ProcessManager.start(
                context = this@MainActivity,
                command = versionCmd,
                workDir = filesDir,
                id      = "node-version"
            )

            val allOutput = versionProcess?.inputStream?.bufferedReader()?.readText()?.trim() ?: ""
            val versionExit = versionProcess?.waitFor() ?: -1
            val lines = allOutput.lines()

            lines.forEach { l ->
                if (l.startsWith("EXIT:")) {
                    log("  exit code: ${l.removePrefix("EXIT:")}")
                } else if (l.isNotEmpty()) {
                    log("  $l")
                }
            }

            val nodeVersion = lines.firstOrNull { it.startsWith("v") }
            if (nodeVersion != null) {
                log("  ✓ Node.js funcionando: $nodeVersion")
            } else {
                logWarn("  ⚠ node --version falhou. Veja o erro acima.")
            }
            log("")

            // ── PASSO 4: Escrever server.js ──────────────────────────────
            step("4", "Preparando server.js")
            val serverFile = writeServerJs()
            log("  Arquivo  : ${serverFile.absolutePath}")
            log("  Tamanho  : ${serverFile.length()} bytes")
            log("")

            // ── PASSO 5: Iniciar servidor ─────────────────────────────────
            step("5", "Iniciando servidor Hello World")
            statusBar("Aguardando servidor na porta $SERVER_PORT...")
            badge("● INICIANDO", "#F0A500")

            val serverProcess = ProcessManager.start(
                context = this@MainActivity,
                command = listOf(nodeFile.absolutePath, serverFile.absolutePath),
                workDir = filesDir,
                id      = SERVER_ID
            )

            if (serverProcess == null) {
                logError("  ERRO: Falha ao criar processo do servidor.")
                badge("✗ FALHA", "#FF4444")
                statusBar("Processo não iniciado.")
                return@launch
            }

            log("  PID do processo iniciado: OK")
            log("")
            log("─────────── stdout do servidor ───────────")

            // ── PASSO 6: Ler stdout do servidor ──────────────────────────
            var serverStarted = false
            BufferedReader(InputStreamReader(serverProcess.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    log("  $l")

                    if (!serverStarted && (l.contains("rodando") || l.contains("listen") || l.contains("$SERVER_PORT"))) {
                        serverStarted = true
                        badge("● ATIVO", "#5FA04E")
                        statusBar("Servidor ativo em http://localhost:$SERVER_PORT")
                        log("")
                        log("  ✓ Servidor disponível em: http://localhost:$SERVER_PORT")
                    }
                }
            }

            val exitCode = serverProcess.waitFor()
            log("─────────────────────────────────────────")
            log("")
            log("  Servidor encerrado. Exit code: $exitCode")
            badge("■ ENCERRADO", "#888888")
            statusBar("Processo encerrado (exit: $exitCode)")
        }
    }

    // ── Helpers de UI ─────────────────────────────────────────────────────
    private suspend fun log(line: String) = withContext(Dispatchers.Main) {
        val t = txtTerminal.text
        txtTerminal.text = if (t.isEmpty()) line else "$t\n$line"
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private suspend fun logError(line: String) = withContext(Dispatchers.Main) {
        // Prefixo vermelho via spannable seria ideal; por ora usamos texto puro
        val t = txtTerminal.text
        txtTerminal.text = if (t.isEmpty()) line else "$t\n$line"
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private suspend fun logWarn(line: String) = log(line)

    private suspend fun step(num: String, desc: String) {
        log("── PASSO $num: $desc")
        statusBar("Passo $num: $desc")
    }

    private suspend fun badge(text: String, colorHex: String) = withContext(Dispatchers.Main) {
        txtServerBadge.text = text
        txtServerBadge.setTextColor(android.graphics.Color.parseColor(colorHex))
    }

    private suspend fun statusBar(text: String) = withContext(Dispatchers.Main) {
        txtStatusBar.text = text
    }

    // ── server.js ─────────────────────────────────────────────────────────
    private fun writeServerJs(): File {
        val js = """
const http = require('http');
const PORT = $SERVER_PORT;

const server = http.createServer((req, res) => {
    const body = JSON.stringify({
        status:    'ok',
        message:   'Hello World from NodeDroid!',
        port:      PORT,
        timestamp: new Date().toISOString()
    }, null, 2);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(body);
});

server.listen(PORT, '0.0.0.0', () => {
    console.log('[NodeDroid] Servidor Hello World rodando na porta ' + PORT);
    console.log('[NodeDroid] Acesse: http://localhost:' + PORT);
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

    override fun onDestroy() {
        super.onDestroy()
        ProcessManager.stop(SERVER_ID)
    }
}