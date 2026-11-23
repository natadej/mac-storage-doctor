package com.natadej.storagedoctor.core

const val MEDIA_REQUEST_LIMIT = 2000

class MediaRequestBatches<T>(items: List<T>, private val sizeOf: (T) -> Long) {
    private val batches = items.chunked(MEDIA_REQUEST_LIMIT)
    private val totalCount = items.size
    private var index = 0
    private var stopped = false

    var completedCount = 0; private set
    var completedBytes = 0L; private set
    val remainingCount get() = totalCount - completedCount
    val current: List<T> get() = if (stopped) emptyList() else batches.getOrNull(index).orEmpty()

    fun confirmCurrent(): List<T> {
        val confirmed = current
        if (confirmed.isNotEmpty()) {
            completedCount += confirmed.size
            completedBytes += confirmed.sumOf(sizeOf)
            index++
        }
        return confirmed
    }

    fun stop() { stopped = true }
}
