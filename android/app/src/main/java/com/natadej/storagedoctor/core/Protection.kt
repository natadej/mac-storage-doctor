package com.natadej.storagedoctor.core

/**
 * Decides what the app may delete. Never: anything outside shared storage, the storage root,
 * top-level folders (DCIM, Download, ...), Android/ internals (except app media folders a rule
 * marks deletable, such as Telegram's cache), folders whose rule says
 * "manage it in the app" (Camera, WhatsApp...), or anything inside chat-app media folders.
 */
class Protection(root: String, rules: List<Rule> = RULES) {
    private val root = root.trimEnd('/')
    private val lockedFolders = rules.filter { it.kind == Kind.PATH && (!it.deletable || it.container) }
        .map { "${this.root}/${it.match}".lowercase() }.toSet()
    private val allowedFolders = rules.filter { it.kind == Kind.PATH && it.deletable && !it.container && !it.skip }
        .map { "${this.root}/${it.match}".lowercase() }.toSet()
    private val lockedTrees = rules.filter { it.kind == Kind.PATH && !it.deletable && it.cat == "messaging" }
        .map { "${this.root}/${it.match}".lowercase() }

    fun canDelete(path: String): Boolean {
        val p = path.trimEnd('/')
        if (!p.startsWith("$root/")) return false
        val rel = p.removePrefix("$root/")
        if ('/' !in rel) return false
        val low = p.lowercase()
        if (low in lockedFolders) return false
        if (low in allowedFolders) return true
        if (lockedTrees.any { low == it || low.startsWith("$it/") }) return false
        val parts = rel.split('/')
        if (parts[0].equals("Android", ignoreCase = true)) {
            return parts.size >= 4 && parts[1].equals("media", ignoreCase = true)
        }
        return true
    }
}
