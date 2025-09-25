package com.novapdf.reader.model

data class PdfOutlineNode(
    val title: String,
    val pageIndex: Int,
    val children: List<PdfOutlineNode> = emptyList()
)

