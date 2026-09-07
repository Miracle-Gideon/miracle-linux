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

/**
 * MiracleLinux MainActivity — v0.1 proof-of-concept only.
 *
 * Goal for this milestone (nothing more yet): prove that a real Debian/Kali
 * rootfs, fully bundled inside this app, can be extracted on first run and
 * booted into a real interactive bash session via PRoot — with zero
 * dependency on Termux being separately installed.
 *
 * KNOWN LIMITATION (intentional, for this milestone): this uses plain
 * process pipes for bash's stdin/stdout, NOT a real pseudo-terminal (pty).
 * That means: no colors, no line-editing, no job control, no ctrl+c yet.
 * Real interactive terminal behavior requires native (JNI) pty allocation —
 * this is the same problem Termux solves with its own native TerminalSession
 * code, and is the natural next step after this milestone proves the core
 * PRoot pipeline works at all.
 */
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

        Thread {
            if (!rootfsDir.exists()) {
                appendOutput("First run: extracting Debian rootfs...\n")
                extractRootfs()
            }
            appendOutput("Starting real bash inside PRoot...\n\n")
            startProotBash()
        }.start()
    }

    /** Builds a bare-bones scrollable terminal UI: output area + one input line. */
    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        outputView = TextView(this)
        outputView.setTextColor(0xFF23BAC2.toInt()) // Miracle Linux cyan accent
        outputView.setBackgroundColor(0xFF000000.toInt())
        outputView.textSize = 12f
        outputView.typeface = android.graphics.Typeface.MONOSPACE

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

    /** Extracts the bundled rootfs.tar.gz (assets) into app-private storage. */
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
                            // Best-effort symlink creation. A real rootfs has many of
                            // these (e.g. /bin -> usr/bin); missing them silently is
                            // safer here than crashing the whole extraction.
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    outFile.toPath(),
                                    java.io.File(entry.linkName).toPath()
                                )
                            } catch (e: Exception) {
                                // Ignore individual symlink failures; log for later review.
                            }
                        }

                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tarStream.copyTo(out)
                            }
                            // Preserve the executable bit — critical for /bin/bash and
                            // every other binary inside the rootfs to actually run.
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
    }

    /** Launches the bundled proot binary, chrooting into the rootfs and exec'ing bash. */
    private fun startProotBash() {
        val prootBinary = File(applicationInfo.nativeLibraryDir, "libproot.so")

        // proot's two Termux-specific dependencies (libtalloc.so.2,
        // libandroid-shmem.so) are bundled with their REAL original names
        // directly in jniLibs — nativeLibraryDir is the one place Android
        // still allows executing/linking native code from. (An earlier
        // version of this copied them into filesDir to rename libtalloc.so
        // back to libtalloc.so.2, but Android 10+ blocks executing anything
        // from an app's own writable private storage — that's what caused
        // the "library not found" crash.)
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
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        bashProcess = process

        // Reader thread: pipes bash's real output back into the UI.
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
