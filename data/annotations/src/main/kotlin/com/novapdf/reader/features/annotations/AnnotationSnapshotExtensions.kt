package com.novapdf.reader.features.annotations

import androidx.compose.ui.geometry.Offset
import com.novapdf.reader.model.PointSnapshot

fun Offset.toSnapshot(): PointSnapshot = PointSnapshot(x, y)
fun PointSnapshot.toOffset(): Offset = Offset(x, y)
