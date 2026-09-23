package com.bingo.smartna.collector.data.model

enum class ClipUploadStatus {
    LOCAL,
    UPLOADING,
    SUCCESS,
    FAILED
}

data class ClipRecord(
    val id: String,
    val taskId: String,
    val clipIndex: Int,
    val status: ClipUploadStatus,
    val createdAt: Long,
    val durationMs: Long = 0L
)
