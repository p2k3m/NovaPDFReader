package dagger.hilt.android.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.novapdf.reader.TestNovaPdfApp
import com.novapdf.reader.runHarnessEntry

/**
 * Custom instrumentation runner that swaps the application used in tests with [TestNovaPdfApp].
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String?, context: Context?): Application {
        return runHarnessEntry("HiltTestRunner", "newApplication") {
            super.newApplication(cl, TestNovaPdfApp::class.java.name, context)
        }
    }
}
