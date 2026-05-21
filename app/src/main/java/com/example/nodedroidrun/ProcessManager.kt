package com.example.nodedroidrun

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * ProcessManager — Singleton responsável por iniciar e gerenciar processos Node.js.
 *
 * Uso obrigatório de LD_LIBRARY_PATH para que o dynamic linker do Android
 * encontre as bibliotecas .so extraídas na pasta privada do app,
 * em vez de procurar nos caminhos originais do Termux.
 */
object ProcessManager {

    private val activeProcesses = mutableMapOf<String, Process>()

    /** Diretório onde o Android instalou as libs nativas (sempre executável). */
    fun getBinDir(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir)

    /** Caminho para o executável node (empacotado como libnode.so no jniLibs). */
    fun getNodePath(context: Context): String =
        File(getBinDir(context), "libnode.so").absolutePath

    /** Caminho para o executável git (hardlink em filesDir/git-core/git). */
    fun getGitPath(context: Context): String =
        File(context.filesDir, "git-core/git").absolutePath

    /** Diretório git-core com os sub-comandos (hardlinks). */
    fun getGitCoreDir(context: Context): File =
        File(context.filesDir, "git-core")

    /**
     * Inicia um processo com as variáveis de ambiente corretas para os binários do Termux.
     * @param context  Contexto do Android para resolver os caminhos.
     * @param command  Comando completo a executar (ex: ["node", "server.js"]).
     * @param workDir  Diretório de trabalho do processo.
     * @param id       Identificador único para rastrear este processo.
     * @return O Process iniciado, ou null em caso de erro.
     */
    fun start(
        context: Context,
        command: List<String>,
        workDir: File,
        id: String
    ): Process? {
        return try {
            val binDir  = getBinDir(context)
            val libDir  = File(context.filesDir, "lib") // aliases versionados (libz.so.1 etc.)
            val pb = ProcessBuilder(command).apply {
                directory(workDir)
                redirectErrorStream(true)
                environment().apply {
                    // nativeLibraryDir: onde estão as libs principais (libnode.so, libssl.so...)
                    // filesDir/lib: aliases versionados que o linker procura (libz.so.1, libssl.so.3...)
                    val ldPath = "${binDir.absolutePath}:${libDir.absolutePath}"
                    put("LD_LIBRARY_PATH", ldPath)
                    put("PATH", "${binDir.absolutePath}:${get("PATH") ?: "/system/bin"}")
                    put("HOME", context.filesDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("NODE_PATH", "${context.filesDir.absolutePath}/node_modules")
                    put("GIT_EXEC_PATH", "${context.filesDir.absolutePath}/git-core")
                }
            }
            val process = pb.start()
            activeProcesses[id] = process
            process
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun stop(id: String) {
        activeProcesses[id]?.destroyForcibly()
        activeProcesses.remove(id)
    }

    fun stopAll() {
        activeProcesses.values.forEach { it.destroyForcibly() }
        activeProcesses.clear()
    }

    fun isRunning(id: String): Boolean {
        val p = activeProcesses[id] ?: return false
        return p.isAlive
    }
}
