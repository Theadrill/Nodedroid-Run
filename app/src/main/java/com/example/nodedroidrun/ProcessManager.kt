package com.example.nodedroidrun

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * ProcessManager — Singleton responsável por iniciar e gerenciar processos Node.js.
 *
 * Uso obrigatório de LD_LIBRARY_PATH para que o dynamic linker do Android
 * encontre as bibliotecas .so extraídas na pasta privada do app,
 * em vez de procurar nos caminhos originais do Termux.
 */
object ProcessManager {

    private val activeProcesses = mutableMapOf<String, Process>()
    var onCountChanged: ((Int) -> Unit)? = null

    private fun notifyCount() {
        onCountChanged?.invoke(activeProcesses.size)
    }

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

    val GIT_SUBCOMMANDS = arrayOf(
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

    val GIT_SPECIAL_BINARIES = mapOf(
        "git-remote-https" to "libgit_remote_https.so",
        "git-remote-http"  to "libgit_remote_http.so",
        "git-remote-ftp"   to "libgit_remote_ftp.so",
        "git-remote-ftps"  to "libgit_remote_ftps.so",
        "git-http-fetch"   to "libgit_http_fetch.so",
        "git-http-push"    to "libgit_http_push.so",
        "git-imap-send"    to "libgit_imap_send.so"
    )

    /**
     * Garante que os symlinks do git-core em filesDir/git-core/ apontem
     * para o nativeLibraryDir atual.  Recreate sempre, porque o
     * nativeLibraryDir muda entre versões do APK (Android incrementa o
     * caminho), quebrando symlinks antigos.
     */
    fun ensureGitSymlinks(context: Context) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val gitCoreDir = getGitCoreDir(context)
        gitCoreDir.mkdirs()

        val gitSrc = File(nativeDir, "libgit.so")
        if (!gitSrc.exists()) return  // SetupActivity reportará o erro

        val gitSrcPath = gitSrc.toPath()
        for (cmd in GIT_SUBCOMMANDS) {
            val link = gitCoreDir.toPath().resolve(cmd)
            Files.deleteIfExists(link)
            val target = if (cmd in GIT_SPECIAL_BINARIES) {
                val soName = GIT_SPECIAL_BINARIES[cmd]!!
                val soFile = File(nativeDir, soName)
                if (soFile.exists()) soFile.toPath() else gitSrcPath
            } else {
                gitSrcPath
            }
            try {
                Files.createSymbolicLink(link, target)
            } catch (_: Exception) {
                // fallback: hard copy
                target.toFile().copyTo(link.toFile(), overwrite = true)
                link.toFile().setExecutable(true)
            }
        }
    }

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
                    put("GIT_SSL_CAINFO", "${context.filesDir.absolutePath}/tls/cert.pem")
                    put("CURL_CA_BUNDLE", "${context.filesDir.absolutePath}/tls/cert.pem")
                }
            }
            val process = pb.start()
            activeProcesses[id] = process
            notifyCount()
            process
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun stop(id: String) {
        activeProcesses[id]?.destroyForcibly()
        activeProcesses.remove(id)
        notifyCount()
    }

    fun stopAll() {
        activeProcesses.values.forEach { it.destroyForcibly() }
        activeProcesses.clear()
        notifyCount()
    }

    fun isRunning(id: String): Boolean {
        val p = activeProcesses[id] ?: return false
        return p.isAlive
    }
}
