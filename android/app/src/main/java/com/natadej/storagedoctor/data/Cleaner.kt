package com.natadej.storagedoctor.data

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

object Cleaner {
    private val files: Uri get() = MediaStore.Files.getContentUri("external")

    /** Trash/delete requests only accept items from the image, video and audio collections. */
    private fun mediaUri(type: Int, id: Long): Uri? = when (type) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> null
    }?.let { ContentUris.withAppendedId(it, id) }

    private val COLUMNS = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
        MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.SIZE)

    /** Media-collection URIs for these file paths (only files the media scanner has indexed). */
    fun mediaUris(ctx: Context, paths: Collection<String>): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        paths.chunked(200).forEach { chunk ->
            val sel = "${MediaStore.MediaColumns.DATA} IN (${chunk.joinToString(",") { "?" }})"
            ctx.contentResolver.query(files, COLUMNS, sel, chunk.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) mediaUri(c.getInt(2), c.getLong(0))?.let { out[c.getString(1)] = it }
            }
        }
        return out
    }

    /** Ask the media scanner to index these files now; returns when it has processed all of them. */
    suspend fun index(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val left = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                    if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    /** System dialog that moves media to the trash (Android 11+). Restorable for 30 days. */
    fun trashRequest(ctx: Context, uris: Collection<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty())
            runCatching { MediaStore.createTrashRequest(ctx.contentResolver, uris, true).intentSender }.getOrNull()
        else null

    /** Items currently in the system trash: path to (uri, size). */
    fun trashed(ctx: Context): Map<String, Pair<Uri, Long>> {
        if (Build.VERSION.SDK_INT < 30) return emptyMap()
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        val out = HashMap<String, Pair<Uri, Long>>()
        ctx.contentResolver.query(files, COLUMNS, args, null)?.use { c ->
            while (c.moveToNext()) mediaUri(c.getInt(2), c.getLong(0))?.let { out[c.getString(1)] = it to maxOf(c.getLong(3), File(c.getString(1)).length()) }
        }
        return out
    }

    /** System dialog that permanently deletes the given media (used to empty the trash). */
    fun deleteRequest(ctx: Context, uris: Collection<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty())
            runCatching { MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender }.getOrNull()
        else null

    /** Permanently delete a file or folder. Returns bytes actually removed. */
    fun deleteNow(ctx: Context, path: String): Long {
        val f = File(path)
        val size = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()
        val ok = f.deleteRecursively()
        MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
        return if (ok) size else size - (if (f.exists()) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L)
    }
}
