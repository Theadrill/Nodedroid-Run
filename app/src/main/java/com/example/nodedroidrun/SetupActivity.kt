package com.example.nodedroidrun

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SetupActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var txtDetails: TextView
    private lateinit var txtPercentage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        progressBar   = findViewById(R.id.progress_bar)
        txtStatus     = findViewById(R.id.txt_status)
        txtDetails    = findViewById(R.id.txt_details)
        txtPercentage = findViewById(R.id.txt_percentage)

        setupAndProceed()
    }

    private fun setupAndProceed() {
        progressBar.isIndeterminate = true
        txtStatus.text  = "Preparando bibliotecas..."
        txtDetails.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            val nativeDir = File(applicationInfo.nativeLibraryDir)
            val libDir    = File(filesDir, "lib").also { it.mkdirs() }

            // Aliases versionados — jniLibs só aceita lib*.so sem sufixo.
            // dlopen() funciona em filesDir mesmo com noexec (apenas execve é bloqueado).
            createVersionedAlias(nativeDir, libDir, "libz.so",        "libz.so.1")
            createVersionedAlias(nativeDir, libDir, "libssl.so",      "libssl.so.3")
            createVersionedAlias(nativeDir, libDir, "libcrypto.so",   "libcrypto.so.3")
            createVersionedAlias(nativeDir, libDir, "libicui18n.so",  "libicui18n.so.78")
            createVersionedAlias(nativeDir, libDir, "libicuuc.so",    "libicuuc.so.78")
            createVersionedAlias(nativeDir, libDir, "libicudata.so",  "libicudata.so.78")
            createVersionedAlias(nativeDir, libDir, "libsqlite3.so",  "libsqlite3.so.0")


            withContext(Dispatchers.Main) {
                val nodeFile = File(nativeDir, "libnode.so")
                if (nodeFile.exists()) {
                    txtStatus.text  = "Node.js pronto!"
                    txtDetails.text = "Iniciando..."
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                } else {
                    progressBar.isIndeterminate = false
                    txtStatus.text  = "Erro: libnode.so não encontrado"
                    txtDetails.text = "Caminho: ${nodeFile.absolutePath}"
                }
            }
        }
    }

    /**
     * Copia [srcName] de [nativeDir] para [libDir] com o nome [dstName].
     * Isso cria os aliases versionados que o linker procura (ex: libz.so.1).
     * Usa filesDir (noexec está ok para dlopen, apenas execve é bloqueado).
     */
    private fun createVersionedAlias(nativeDir: File, libDir: File, srcName: String, dstName: String) {
        val src = File(nativeDir, srcName)
        val dst = File(libDir, dstName)
        if (src.exists() && !dst.exists()) {
            src.copyTo(dst, overwrite = false)
        }
    }
}
