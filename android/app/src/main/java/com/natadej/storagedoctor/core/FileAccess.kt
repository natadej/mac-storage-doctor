package com.natadej.storagedoctor.core

enum class FileAccessMode { DISCLOSURE, LIMITED, FULL }

fun fileAccessMode(hasAccess: Boolean, continueWithoutAccess: Boolean): FileAccessMode = when {
    hasAccess -> FileAccessMode.FULL
    continueWithoutAccess -> FileAccessMode.LIMITED
    else -> FileAccessMode.DISCLOSURE
}
