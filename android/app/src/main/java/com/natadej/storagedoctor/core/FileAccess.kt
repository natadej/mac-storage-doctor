package com.natadej.storagedoctor.core

enum class FileAccessMode { DISCLOSURE, LIMITED, FULL }

fun fileAccessMode(hasAccess: Boolean, disclosureAnswered: Boolean, requestDisclosure: Boolean = false): FileAccessMode = when {
    hasAccess -> FileAccessMode.FULL
    requestDisclosure -> FileAccessMode.DISCLOSURE
    disclosureAnswered -> FileAccessMode.LIMITED
    else -> FileAccessMode.DISCLOSURE
}
