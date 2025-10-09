package com.novapdf.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novapdf.reader.presentation.viewer.R
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.ui.theme.NovaPdfTheme
import com.novapdf.reader.legacy.LegacyPdfPageAdapter
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.domain.usecase.RemoteDocumentUseCase
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

open class ReaderActivity : ComponentActivity() {
    private val viewModel: PdfViewerViewModel by viewModels()
    private val snackbarHost = SnackbarHostState()
    private var useComposeUi = true

    private var legacyAdapter: LegacyPdfPageAdapter? = null
    private var legacyRecyclerView: RecyclerView? = null
    private var legacyStatusContainer: View? = null
    private var legacyStatusText: TextView? = null
    private var legacyRetryButton: MaterialButton? = null
    private var legacyProgress: CircularProgressIndicator? = null
    private var legacyToolbar: MaterialToolbar? = null
    private var legacyRoot: View? = null
    private var legacyLayoutManager: LinearLayoutManager? = null
    private var legacyLastFocusedPage: Int = -1

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.openDocument(it) }
    }

    private val cloudDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            val takeFlags = (result.data?.flags ?: 0) and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            runCatching {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
            viewModel.openDocument(uri)
        }

    private val dependencies: NovaPdfDependencies
        get() = (application as? NovaPdfDependencies)
            ?: throw IllegalStateException("Application must implement NovaPdfDependencies")

    private val useCases: PdfViewerUseCases
        get() = dependencies.pdfViewerUseCases

    private val remoteDocumentUseCase: RemoteDocumentUseCase
        get() = useCases.remoteDocuments

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useComposeUi = resources.getBoolean(R.bool.config_use_compose)
        if (useComposeUi) {
            setContent {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NovaPdfTheme(
                    useDarkTheme = uiState.isNightMode,
                    dynamicColor = uiState.dynamicColorEnabled,
                    highContrast = uiState.highContrastEnabled,
                    seedColor = Color(uiState.themeSeedColor)
                ) {
                    PdfViewerRoute(
                        viewModel = viewModel,
                        snackbarHost = snackbarHost,
                        onOpenLocalDocument = { openDocumentLauncher.launch("application/pdf") },
                        onOpenCloudDocument = { launchCloudDocumentPicker() },
                        onOpenRemoteDocument = { url -> openRemoteDocument(url) },
                        onDismissError = { viewModel.dismissError() }
                    )
                }
            }
        } else {
            setContentView(R.layout.activity_main)
            setupLegacyUi()
        }
    }

    override fun onResume() {
        super.onResume()
        useCases.adaptiveFlow.start()
    }

    override fun onPause() {
        super.onPause()
        useCases.adaptiveFlow.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        legacyAdapter?.dispose()
    }

    private fun launchCloudDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf"))
        }
        cloudDocumentLauncher.launch(intent)
    }

    private fun openRemoteDocument(url: String) {
        if (useComposeUi) {
            showUserSnackbar(getString(R.string.remote_pdf_download_started))
            viewModel.openRemoteDocument(url)
        } else {
            lifecycleScope.launch {
                showUserSnackbar(getString(R.string.remote_pdf_download_started))
                val result = remoteDocumentUseCase.download(url)
                result.onSuccess { uri ->
                    viewModel.openDocument(uri)
                }.onFailure { error ->
                    val messageRes = if (error is RemotePdfException &&
                        error.reason == RemotePdfException.Reason.CORRUPTED
                    ) {
                        R.string.error_pdf_corrupted
                    } else {
                        R.string.remote_pdf_download_failed
                    }
                    showUserSnackbar(getString(messageRes))
                    viewModel.reportRemoteOpenFailure(error, url)
                }
            }
        }
    }

    private fun showUserSnackbar(message: String) {
        if (useComposeUi) {
            lifecycleScope.launch {
                snackbarHost.showSnackbar(message)
            }
        } else {
            val root = legacyRoot ?: return
            Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun openDocumentForTest(uri: Uri) {
        if (!useComposeUi) {
            viewModel.openDocument(uri)
            return
        }

        lifecycleScope.launch {
            lifecycle.withStarted {
                viewModel.openDocument(uri)
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun currentDocumentStateForTest(): PdfViewerUiState {
        return viewModel.uiState.value
    }

    private fun setupLegacyUi() {
        legacyRoot = findViewById(R.id.legacy_root)
        val recyclerView = findViewById<RecyclerView>(R.id.legacy_page_list).also { legacyRecyclerView = it }
        val toolbar = findViewById<MaterialToolbar>(R.id.legacy_toolbar).also { legacyToolbar = it }
        val statusContainer = findViewById<View>(R.id.legacy_status_container).also { legacyStatusContainer = it }
        legacyStatusText = findViewById<TextView>(R.id.legacy_status_text)
        legacyRetryButton = findViewById<MaterialButton>(R.id.legacy_retry_button).apply {
            this?.setOnClickListener { openDocumentLauncher.launch("application/pdf") }
        }
        legacyProgress = findViewById<CircularProgressIndicator>(R.id.legacy_progress)
        toolbar.inflateMenu(R.menu.fallback_viewer_actions)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> {
                    openDocumentLauncher.launch("application/pdf")
                    true
                }

                R.id.action_export -> {
                    val exported = viewModel.exportDocument(this)
                    if (!exported) {
                        showUserSnackbar(getString(R.string.export_failed))
                    }
                    true
                }

                else -> false
            }
        }

        val layoutManager = LinearLayoutManager(this)
        legacyLayoutManager = layoutManager
        recyclerView.layoutManager = layoutManager
        val adapter = LegacyPdfPageAdapter(this, lifecycleScope, viewModel)
        legacyAdapter = adapter
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                notifyVisiblePage()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    notifyVisiblePage()
                }
            }

            private fun notifyVisiblePage() {
                val manager = legacyLayoutManager ?: return
                val firstComplete = manager.findFirstCompletelyVisibleItemPosition()
                val candidate = if (firstComplete != RecyclerView.NO_POSITION) {
                    firstComplete
                } else {
                    manager.findFirstVisibleItemPosition()
                }
                if (candidate != RecyclerView.NO_POSITION && candidate != legacyLastFocusedPage) {
                    legacyLastFocusedPage = candidate
                    viewModel.onPageFocused(candidate)
                }
            }
        })

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val status = state.documentStatus
                legacyProgress?.isVisible = status is DocumentStatus.Loading
                adapter.onDocumentChanged(state.documentId, state.pageCount)
                updateLegacyToolbar(state)
                updateLegacyStatus(state, statusContainer)
                maybeScrollToPage(state)
            }
        }
    }

    private fun updateLegacyToolbar(state: PdfViewerUiState) {
        legacyToolbar?.subtitle = if (state.documentId != null && state.pageCount > 0) {
            getString(R.string.legacy_pdf_page, state.currentPage + 1) + " / " + state.pageCount
        } else {
            null
        }
    }

    private fun updateLegacyStatus(state: PdfViewerUiState, statusContainer: View?) {
        if (statusContainer == null) return
        val hasDocument = state.documentId != null
        val statusText = legacyStatusText
        val retry = legacyRetryButton
        when (val status = state.documentStatus) {
            is DocumentStatus.Loading -> {
                statusContainer.isVisible = true
                statusText?.text = status.messageRes?.let(::getString)
                    ?: getString(R.string.loading_document)
                retry?.isVisible = false
            }

            is DocumentStatus.Error -> {
                statusContainer.isVisible = true
                statusText?.text = status.message
                retry?.isVisible = true
            }

            DocumentStatus.Idle -> {
                when {
                    hasDocument && state.pageCount == 0 -> {
                        statusContainer.isVisible = true
                        statusText?.text = getString(R.string.legacy_select_document)
                        retry?.isVisible = false
                    }

                    hasDocument -> {
                        statusContainer.isVisible = false
                        retry?.isVisible = false
                    }

                    else -> {
                        statusContainer.isVisible = true
                        statusText?.text = getString(R.string.legacy_select_document)
                        retry?.isVisible = true
                    }
                }
            }
        }
    }

    private fun maybeScrollToPage(state: PdfViewerUiState) {
        if (state.documentId == null || state.pageCount == 0) {
            legacyLastFocusedPage = -1
            return
        }
        val recyclerView = legacyRecyclerView ?: return
        val manager = legacyLayoutManager ?: return
        if (state.currentPage == RecyclerView.NO_POSITION) return
        if (state.currentPage == legacyLastFocusedPage) return
        val firstVisible = manager.findFirstVisibleItemPosition()
        val lastVisible = manager.findLastVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION && state.currentPage in firstVisible..lastVisible) {
            legacyLastFocusedPage = state.currentPage
            return
        }
        recyclerView.post {
            manager.scrollToPositionWithOffset(state.currentPage, 0)
            legacyLastFocusedPage = state.currentPage
        }
    }
}
