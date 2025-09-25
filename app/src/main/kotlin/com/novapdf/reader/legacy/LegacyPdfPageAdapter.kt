package com.novapdf.reader.legacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.util.forEach
import androidx.core.util.set
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.novapdf.reader.PdfViewerViewModel
import com.novapdf.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 3f

class LegacyPdfPageAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: PdfViewerViewModel
) : RecyclerView.Adapter<LegacyPdfPageViewHolder>() {

    private val pageIndices = mutableListOf<Int>()
    private val pageJobs = SparseArray<Job>()
    private val bitmaps = SparseArray<Bitmap>()
    private var currentDocumentId: String? = null

    fun onDocumentChanged(documentId: String?, pageCount: Int) {
        if (documentId == null) {
            clear()
            return
        }
        if (documentId != currentDocumentId) {
            clear()
            currentDocumentId = documentId
        }
        val newSize = max(0, pageCount)
        pageIndices.clear()
        repeat(newSize) { index ->
            pageIndices.add(index)
        }
        notifyDataSetChanged()
    }

    fun clear() {
        pageJobs.forEach { _, job -> job.cancel() }
        pageJobs.clear()
        bitmaps.clear()
        pageIndices.clear()
        currentDocumentId = null
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LegacyPdfPageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
        return LegacyPdfPageViewHolder(view)
    }

    override fun getItemCount(): Int = pageIndices.size

    override fun onViewRecycled(holder: LegacyPdfPageViewHolder) {
        super.onViewRecycled(holder)
        val page = holder.boundPage
        if (page != RecyclerView.NO_POSITION) {
            pageJobs[page]?.cancel()
        }
    }

    override fun onBindViewHolder(holder: LegacyPdfPageViewHolder, position: Int) {
        val pageIndex = pageIndices[position]
        holder.bind(pageIndex)
        val cached = bitmaps[pageIndex]
        if (cached != null && !cached.isRecycled) {
            holder.showBitmap(cached)
            return
        }
        holder.showLoading()
        pageJobs[pageIndex]?.cancel()
        val job = scope.launch {
            val bitmap = renderPageBitmap(pageIndex)
            withContext(Dispatchers.Main) {
                if (holder.boundPage == pageIndex) {
                    if (bitmap != null) {
                        bitmaps[pageIndex] = bitmap
                        holder.showBitmap(bitmap)
                    } else {
                        holder.showError(ContextCompat.getDrawable(context, R.drawable.ic_pdf))
                    }
                } else {
                    bitmap?.recycle()
                }
            }
        }
        pageJobs[pageIndex] = job
    }

    private suspend fun renderPageBitmap(pageIndex: Int): Bitmap? {
        val size = viewModel.pageSize(pageIndex) ?: return null
        val metrics = context.resources.displayMetrics
        val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.legacy_page_horizontal_margin) * 2
        val targetWidth = max(1, metrics.widthPixels - horizontalPadding)
        val scale = (targetWidth.toFloat() / size.width).coerceIn(MIN_SCALE, MAX_SCALE)
        val rect = Rect(0, 0, size.width, size.height)
        return viewModel.renderTile(pageIndex, rect, scale)
    }

    fun dispose() {
        clear()
    }
}

class LegacyPdfPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val imageView: ImageView = itemView.findViewById(R.id.legacy_page_image)
    private val progress: CircularProgressIndicator = itemView.findViewById(R.id.legacy_page_progress)
    private val label: TextView = itemView.findViewById(R.id.legacy_page_label)

    var boundPage: Int = RecyclerView.NO_POSITION
        private set

    fun bind(pageIndex: Int) {
        boundPage = pageIndex
        label.text = itemView.context.getString(R.string.legacy_pdf_page, pageIndex + 1)
        label.isVisible = false
    }

    fun showLoading() {
        progress.isVisible = true
        imageView.setImageDrawable(null)
        label.isVisible = false
    }

    fun showBitmap(bitmap: Bitmap) {
        progress.isVisible = false
        imageView.setImageBitmap(bitmap)
        label.isVisible = true
    }

    fun showError(drawable: android.graphics.drawable.Drawable?) {
        progress.isVisible = false
        imageView.setImageDrawable(drawable)
        label.isVisible = true
    }
}
