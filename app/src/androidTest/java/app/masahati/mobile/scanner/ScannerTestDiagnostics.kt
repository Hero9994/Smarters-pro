package app.masahati.mobile.scanner

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test-only public/synthetic diagnostics survive AGP uninstalling the test APK.
 * This class is never packaged into the application or used with user captures.
 */
internal object ScannerTestDiagnostics {
    fun publish(folder: File) {
        if(Build.VERSION.SDK_INT<31 || !folder.isDirectory) return
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val root="/data/local/tmp/masahati-scanner-diagnostics"
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p "+root)).use { it.readBytes() }
        for(file in folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
            require(file.name.matches(Regex("[A-Za-z0-9_.-]+")))
            val streams=automation.executeShellCommandRw("dd of="+root+"/"+file.name)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(streams[1]).use { output -> file.inputStream().use { it.copyTo(output) } }
                ParcelFileDescriptor.AutoCloseInputStream(streams[0]).use { it.readBytes() }
            } finally { streams.forEach { runCatching { it.close() } } }
        }
    }
}
