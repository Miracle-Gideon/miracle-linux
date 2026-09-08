package com.miracle.linux

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var outputView: TextView
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bashProcess: Process? = null
    private lateinit var rootfsDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootfsDir = File(filesDir, "rootfs")

        buildUi()
        appendOutput("Miracle Linux — GhostByte (v0.1 proof of concept)\n")
        appendOutput("[diag] versionName=${packageManager.getPackageInfo(packageName, 0).versionName}\n")
        appendOutput("[diag] rootfsDir=${rootfsDir.absolutePath} exists=${rootfsDir.exists()}\n")

        Thread {
            if (!rootfsDir.exists()) {
                appendOutput("First run: extracting Debian rootfs...\n")
                extractRootfs()
            } else {
                appendOutput("[diag] Skipping extraction — rootfsDir already existed\n")
            }
            appendOutput("Starting real bash inside PRoot...\n\n")
            startProotBash()
        }.start()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        outputView = TextView(this)
        outputView.setTextColor(0xFF23BAC2.toInt())
        outputView.setBackgroundColor(0xFF000000.toInt())
        outputView.textSize = 12f
        outputView.typeface = android.graphics.Typeface.MONOSPACE
        outputView.setTextIsSelectable(true)

        scrollView = ScrollView(this)
        scrollView.addView(outputView)
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        inputField = EditText(this)
        inputField.setBackgroundColor(0xFF111111.toInt())
        inputField.setTextColor(0xFFFFFFFF.toInt())
        inputField.hint = "type a command..."
        inputField.setOnEditorActionListener { _, _, _ ->
            sendCommand(inputField.text.toString())
            inputField.setText("")
            true
        }

        root.addView(scrollView)
        root.addView(inputField)
        setContentView(root)
    }

    private fun extractRootfs() {
        rootfsDir.mkdirs()
        val assetStream = assets.open("rootfs.tar.gz")

        GZIPInputStream(assetStream).use { gzipStream ->
            TarArchiveInputStream(gzipStream).use { tarStream ->
                var entry: TarArchiveEntry? = tarStream.nextTarEntry
                while (entry != null) {
                    val outFile = File(rootfsDir, entry.name)

                    when {
                        entry.isDirectory -> outFile.mkdirs()

                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    outFile.toPath(),
                                    java.io.File(entry.linkName).toPath()
                                )
                            } catch (e: Exception) {
                                appendOutput("[symlink failed: ${entry.name} -> ${entry.linkName} (${e.message})]\n")
                            }
                        }

                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tarStream.copyTo(out)
                            }
                            val ownerExecuteBit = 0b001000000
                            if (entry.mode and ownerExecuteBit != 0) {
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                    entry = tarStream.nextTarEntry
                }
            }
        }
        appendOutput("Rootfs extracted to: ${rootfsDir.absolutePath}\n")

        val checks = listOf("bin", "usr/bin", "usr/bin/bash", "root", "bin/bash")
        for (path in checks) {
            val f = File(rootfsDir, path)
            appendOutput("[check] $path -> exists=${f.exists()} isSymlink=${java.nio.file.Files.isSymbolicLink(f.toPath())}\n")
        }
    }

    private fun startProotBash() {
        val prootBinary = File(applicationInfo.nativeLibraryDir, "libproot.so")

        val command = listOf(
            prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-w", "/root",
            "/bin/bash"
        )

        val processBuilder = ProcessBuilder(command)
        processBuilder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
        val prootTmpDir = File(filesDir, "proot-tmp").apply { mkdirs() }
        processBuilder.environment()["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        bashProcess = process

        Thread {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                appendOutput(line + "\n")
            }
            appendOutput("\n[process exited]\n")
        }.start()
    }

    private fun sendCommand(command: String) {
        val process = bashProcess ?: return
        appendOutput("$ $command\n")
        Thread {
            try {
                val writer = OutputStreamWriter(process.outputStream)
                writer.write("$command\n")
                writer.flush()
            } catch (e: Exception) {
                appendOutput("[error: process is not running — ${e.message}]\n")
            }
        }.start()
    }

    private fun appendOutput(text: String) {
        mainHandler.post {
            outputView.append(text)
            scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bashProcess?.destroy()
    }
}
