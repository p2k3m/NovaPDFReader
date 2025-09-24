package com.novapdf.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.novapdf.reader.R
import com.novapdf.reader.ui.theme.NovaPdfTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: PdfViewerViewModel by viewModels()
    private val snackbarHost = SnackbarHostState()
    private val preferences by lazy { getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE) }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.openDocument(it) }
    }

    private val manageAllFilesPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleManageAllFilesResult()
        }

    private val appSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestStoragePermissionIfNeeded()
        }

    private val readStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            handleReadStoragePermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val manageStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val currentlyGranted = if (manageStorage) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        cachePermissionGrantState(currentlyGranted)
        if (currentlyGranted) return

        if (manageStorage) {
            val alreadyPrompted = preferences.getBoolean(KEY_MANAGE_PROMPTED, false)
            if (!alreadyPrompted) {
                preferences.edit().putBoolean(KEY_MANAGE_PROMPTED, true).apply()
                showStorageSnackbar(R.string.storage_permission_manage_explanation)
                launchManageAllFilesSettings()
            } else {
                showStorageSnackbar(
                    messageRes = R.string.storage_permission_denied,
                    actionRes = R.string.storage_permission_open_settings,
                    onAction = { launchManageAllFilesSettings() }
                )
            }
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            val alreadyPrompted = preferences.getBoolean(KEY_READ_PROMPTED, false)
            val shouldRequest = !alreadyPrompted || shouldShowRequestPermissionRationale(permission)
            if (shouldRequest) {
                preferences.edit().putBoolean(KEY_READ_PROMPTED, true).apply()
                showStorageSnackbar(R.string.storage_permission_read_explanation)
                readStoragePermissionLauncher.launch(permission)
            } else {
                showStorageSnackbar(
                    messageRes = R.string.storage_permission_denied,
                    actionRes = R.string.storage_permission_open_settings,
                    onAction = { openAppSettings() }
                )
            }
        }
    }

    private fun handleManageAllFilesResult() {
        val granted = Environment.isExternalStorageManager()
        cachePermissionGrantState(granted)
        showStorageSnackbar(
            if (granted) R.string.storage_permission_granted else R.string.storage_permission_denied
        )
    }

    private fun handleReadStoragePermissionResult(granted: Boolean) {
        cachePermissionGrantState(granted)
        showStorageSnackbar(
            if (granted) R.string.storage_permission_granted else R.string.storage_permission_denied
        )
    }

    private fun launchManageAllFilesSettings() {
        val packageUri = Uri.fromParts("package", packageName, null)
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = packageUri
        }
        val intent = if (packageManager.resolveActivity(appIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            appIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        manageAllFilesPermissionLauncher.launch(intent)
    }

    private fun showStorageSnackbar(
        @StringRes messageRes: Int,
        @StringRes actionRes: Int? = null,
        onAction: (() -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val result = snackbarHost.showSnackbar(
                message = getString(messageRes),
                actionLabel = actionRes?.let(::getString)
            )
            if (result == SnackbarResult.ActionPerformed) {
                onAction?.invoke()
            }
        }
    }

    private fun cachePermissionGrantState(granted: Boolean) {
        preferences.edit().apply {
            putBoolean(KEY_STORAGE_GRANTED, granted)
            if (granted) {
                putBoolean(KEY_MANAGE_PROMPTED, false)
                putBoolean(KEY_READ_PROMPTED, false)
            }
        }.apply()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        appSettingsLauncher.launch(intent)
    }

    companion object {
        private const val PERMISSION_PREFS = "novapdf_permissions"
        private const val KEY_STORAGE_GRANTED = "storage_granted"
        private const val KEY_MANAGE_PROMPTED = "manage_permission_prompted"
        private const val KEY_READ_PROMPTED = "read_permission_prompted"
    }
}
