package com.natadej.storagedoctor.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object Opener {
    fun mimeType(path: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase()) ?: "*/*"

    /** Content URI another app can read: the media-store entry if the file is indexed, else our FileProvider. */
    fun uriFor(ctx: Context, path: String): Uri =
        Cleaner.mediaUris(ctx, listOf(path))[path]
            ?: FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))

    /** Shows the file in the app the user picks for its type (gallery, video player, PDF viewer...). */
    fun open(ctx: Context, uri: Uri, path: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            ctx.startActivity(Intent.createChooser(view, File(path).name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
