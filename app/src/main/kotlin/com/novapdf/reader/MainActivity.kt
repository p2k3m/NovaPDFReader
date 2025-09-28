package com.novapdf.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Entry point activity used by automated UI tooling. Adds runtime permission detection so
 * storage access prompts are issued automatically when needed.
 */
class MainActivity : ReaderActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifyStorageAccess()
    }

    private fun verifyStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasAccess) {
            requestStoragePermissionIfNeeded()
        }
    }
}
