package com.novapdf.reader.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AnnotationCommand {
    abstract val pageIndex: Int

    @Serializable
    @SerialName("stroke")
    data class Stroke(
        override val pageIndex: Int,
        val points: List<PointSnapshot>,
        val color: Long,
        val strokeWidth: Float
    ) : AnnotationCommand()

    @Serializable
    @SerialName("highlight")
    data class Highlight(
        override val pageIndex: Int,
        val rect: RectSnapshot,
        val color: Long
    ) : AnnotationCommand()

    @Serializable
    @SerialName("text")
    data class Text(
        override val pageIndex: Int,
        val text: String,
        val position: PointSnapshot,
        val color: Long
    ) : AnnotationCommand()
}

@Serializable
data class PointSnapshot(val x: Float, val y: Float)

@Serializable
data class RectSnapshot(val left: Float, val top: Float, val right: Float, val bottom: Float)
