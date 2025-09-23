package com.novapdf.reader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.ContextCompat
import com.novapdf.reader.ui.theme.NovaPdfTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PdfViewerViewModel by viewModels()

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.openDocument(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snackbarHost = SnackbarHostState()
        setContent {
            NovaPdfTheme {
                PdfViewerRoute(
                    viewModel = viewModel,
                    snackbarHost = snackbarHost,
                    onOpenDocument = { openDocumentLauncher.launch("application/pdf") }
                )
            }
        }
        requestStoragePermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        (application as? NovaPdfApp)?.adaptiveFlowManager?.start()
    }

    override fun onPause() {
        super.onPause()
        (application as? NovaPdfApp)?.adaptiveFlowManager?.stop()
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 100)
        }
    }
}
