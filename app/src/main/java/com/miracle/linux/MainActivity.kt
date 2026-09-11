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
        // Trying external app-specific storage instead of internal filesDir —
        // internal storage's app_data_file SELinux context appears to still
        // block execution even with targetSdk lowered. External app-specific
        // storage (no special permission needed for the app's own directory
        // here) typically carries a different, less restrictive context.
        val externalBase = getExternalFilesDir(null)
        rootfsDir = if (externalBase != null) {
            File(externalBase, "rootfs")
        } else {
            File(filesDir, "rootfs") // fallback if external storage is unavailable
        }

        buildUi()

        try {
            appendOutput("Miracle Linux — GhostByte (v0.1 proof of concept)\n")
            appendOutput("[diag] versionName=${packageManager.getPackageInfo(packageName, 0).versionName}\n")
            val marker = File(filesDir, "rootfs_extraction_complete")
            appendOutput("[diag] rootfsDir=${rootfsDir.absolutePath} markerExists=${marker.exists()}\n")

            Thread {
                try {
                    if (!marker.exists()) {
                        appendOutput("Extracting Debian rootfs (fresh or previously incomplete)...\n")
                        rootfsDir.deleteRecursively() // clear any partial leftovers first
                        extractRootfs()
                        marker.writeText("done")
                    } else {
                        appendOutput("[diag] Skipping extraction — marker confirms it already completed\n")
                    }
                    appendOutput("Starting real bash inside PRoot...\n\n")

                    // Direct test, bypassing proot entirely: does Android even allow
                    // executing ANYTHING from our extracted rootfs? If this fails too,
                    // it confirms Android's exec-from-writable-storage restriction is
                    // the real blocker, not proot, symlinks, or the ELF interpreter.
                    try {
                        val directTest = ProcessBuilder(File(rootfsDir, "usr/bin/bash").absolutePath, "--version")
                            .redirectErrorStream(true)
                            .start()
                        val directOutput = directTest.inputStream.bufferedReader().readText()
                        appendOutput("[direct-exec-test] SUCCESS, output: $directOutput\n")
                    } catch (e: Exception) {
                        appendOutput("[direct-exec-test] FAILED: ${e.javaClass.simpleName}: ${e.message}\n")
                    }

                    startProotBash()
                } catch (e: Exception) {
                    appendOutput("[FATAL in background thread] ${e.javaClass.simpleName}: ${e.message}\n")
                    appendOutput(e.stackTraceToString() + "\n")
                }
            }.start()
        } catch (e: Exception) {
            appendOutput("[FATAL in onCreate] ${e.javaClass.simpleName}: ${e.message}\n")
            appendOutput(e.stackTraceToString() + "\n")
        }
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
        outputView.setTextIsSelectable(true) // lets you long-press to select/copy output

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
        val assetStream = assets.open("rootfs.rootfsblob")

        GZIPInputStream(assetStream).use { gzipStream ->
            TarArchiveInputStream(gzipStream).use { tarStream ->
                var entry: TarArchiveEntry? = tarStream.nextTarEntry
                while (entry != null) {
                    val outFile = File(rootfsDir, entry.name)

                    when {
                        entry.isDirectory -> outFile.mkdirs()

                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            // A real rootfs has many of these (e.g. /bin -> usr/bin) —
                            // these were previously failing silently, hiding exactly
                            // the kind of bug we're now chasing. Report failures now.
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

        // Diagnostic: check the exact paths proot complained about, so we know
        // for certain whether extraction produced them or not, instead of guessing.
        val checks = listOf(
            "bin", "usr/bin", "usr/bin/bash", "root", "bin/bash",
            "lib", "lib/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
        )
        for (path in checks) {
            val f = File(rootfsDir, path)
            appendOutput("[check] $path -> exists=${f.exists()} isSymlink=${java.nio.file.Files.isSymbolicLink(f.toPath())}\n")
        }
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
        // proot's Termux build has Termux's own tmp path hardcoded as default;
        // that path doesn't exist in our app's sandbox, so proot's own error
        // message tells us directly to override it via this env variable.
        val prootTmpDir = File(filesDir, "proot-tmp").apply { mkdirs() }
        processBuilder.environment()["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
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
