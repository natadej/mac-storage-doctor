package com.natadej.storagedoctor.core

const val MB = 1_000_000L
const val GB = 1_000_000_000L

enum class Safety { SAFE, REVIEW, CAREFUL }

data class Category(val id: String, val label: String, val color: Long)

val CATEGORIES = listOf(
    Category("ai", "AI apps & models", 0xFFA855F7),
    Category("media", "Photos & videos", 0xFFF59E0B),
    Category("messaging", "Chat app media", 0xFFEC4899),
    Category("downloads", "Downloads & installers", 0xFFF97316),
    Category("audio", "Music & audio", 0xFF84CC16),
    Category("docs", "Documents", 0xFF22C55E),
    Category("apps", "Apps & app data", 0xFF6366F1),
    Category("cache", "Caches & leftovers", 0xFF14B8A6),
    Category("trash", "Trash", 0xFF78716C),
    Category("other", "Other files", 0xFF94A3B8),
    Category("system", "Android system & not scanned", 0xFF475569),
)

fun category(id: String) = CATEGORIES.firstOrNull { it.id == id } ?: CATEGORIES.first { it.id == "other" }

enum class Kind { PATH, DIRNAME, FILE_SUFFIX, FILE_PREFIX }

/**
 * A classification rule for shared storage. PATH rules match a path relative to the storage root
 * (case-insensitive). `container` rules only set the category for everything inside; other folder
 * rules become cleanup findings. `quiet` turns off runaway detection inside (camera folders etc.
 * always have machine-generated names).
 */
data class Rule(
    val kind: Kind,
    val match: String,
    val cat: String,
    val label: String? = null,
    val safety: Safety = Safety.REVIEW,
    val advice: String = "",
    val deletable: Boolean = true,
    val container: Boolean = false,
    val quiet: Boolean = false,
    val skip: Boolean = false,
    val minSize: Long = 50 * MB,
)

val RULES = listOf(
    // Unreadable since Android 11 and counted in per-app sizes instead.
    Rule(Kind.PATH, "Android/data", "apps", skip = true),
    Rule(Kind.PATH, "Android/obb", "apps", skip = true),

    Rule(Kind.PATH, "Android/media/com.whatsapp", "messaging", "WhatsApp media", Safety.CAREFUL, deletable = false, quiet = true,
        advice = "In WhatsApp: Settings > Storage and data > Manage storage. Review large files and forwarded media there."),
    Rule(Kind.PATH, "Android/media/com.whatsapp.w4b", "messaging", "WhatsApp Business media", Safety.CAREFUL, deletable = false, quiet = true,
        advice = "In WhatsApp Business: Settings > Storage and data > Manage storage."),
    Rule(Kind.PATH, "WhatsApp", "messaging", "WhatsApp media (old location)", Safety.CAREFUL, deletable = false, quiet = true,
        advice = "Old WhatsApp folder. Check that your chats are backed up before removing media."),
    Rule(Kind.PATH, "Android/media/org.telegram.messenger", "messaging", "Telegram files", Safety.REVIEW, quiet = true,
        advice = "Cached copies of cloud files. Telegram > Settings > Data and Storage > Storage Usage > Clear cache."),
    Rule(Kind.PATH, "Telegram", "messaging", "Telegram files (old location)", Safety.REVIEW, quiet = true,
        advice = "Telegram keeps these in the cloud; they download again when opened."),
    Rule(Kind.PATH, "Android/media/jp.naver.line.android", "messaging", "LINE media", Safety.CAREFUL, deletable = false, quiet = true,
        advice = "In LINE: Settings > Photos & videos / Chats > Delete data. Save photos you want to keep first."),
    Rule(Kind.PATH, "Android/media/org.thoughtcrime.securesms", "messaging", "Signal media", Safety.CAREFUL, deletable = false, quiet = true,
        advice = "In Signal: Settings > Data and storage > Manage storage."),
    Rule(Kind.PATH, "Android/media/com.facebook.orca", "messaging", "Messenger media", Safety.REVIEW, quiet = true),
    Rule(Kind.PATH, "Android/media/com.discord", "messaging", "Discord media", Safety.REVIEW, quiet = true),

    Rule(Kind.PATH, "DCIM/Camera", "media", "Camera photos & videos", Safety.CAREFUL, deletable = false, quiet = true,
        advice = "Back them up first (Google Photos or a computer), then use Google Photos > Free up space."),
    Rule(Kind.PATH, "DCIM/Screenshots", "media", "Screenshots", Safety.REVIEW, quiet = true,
        advice = "Old screenshots are usually safe to remove after a quick look."),
    Rule(Kind.PATH, "Pictures/Screenshots", "media", "Screenshots", Safety.REVIEW, quiet = true,
        advice = "Old screenshots are usually safe to remove after a quick look."),
    Rule(Kind.PATH, "DCIM/ScreenRecorder", "media", "Screen recordings", Safety.REVIEW, quiet = true),
    Rule(Kind.PATH, "Movies/Screen recordings", "media", "Screen recordings", Safety.REVIEW, quiet = true),
    Rule(Kind.PATH, "DCIM", "media", container = true),
    Rule(Kind.PATH, "Pictures", "media", container = true),
    Rule(Kind.PATH, "Movies", "media", container = true),
    Rule(Kind.PATH, "Download", "downloads", container = true),
    Rule(Kind.PATH, "Downloads", "downloads", container = true),
    Rule(Kind.PATH, "Documents", "docs", container = true),
    Rule(Kind.PATH, "Music", "audio", container = true, quiet = true),
    Rule(Kind.PATH, "Podcasts", "audio", container = true, quiet = true),
    Rule(Kind.PATH, "Audiobooks", "audio", container = true, quiet = true),
    Rule(Kind.PATH, "Recordings", "audio", container = true, quiet = true),
    Rule(Kind.PATH, "Ringtones", "audio", container = true, quiet = true),

    Rule(Kind.DIRNAME, ".thumbnails", "cache", "Gallery thumbnail cache", Safety.SAFE, quiet = true, minSize = 10 * MB,
        advice = "Rebuilt automatically by the gallery app."),
    Rule(Kind.DIRNAME, ".cache", "cache", "Cache folder", Safety.SAFE, quiet = true, minSize = 20 * MB,
        advice = "Cache files are recreated when needed."),
    Rule(Kind.DIRNAME, "cache", "cache", "Cache folder", Safety.REVIEW, quiet = true, minSize = 20 * MB,
        advice = "Probably a cache. Check which app made it."),
    Rule(Kind.DIRNAME, "node_modules", "cache", "node_modules (developer)", Safety.SAFE, quiet = true, minSize = 20 * MB,
        advice = "JavaScript dependencies; reinstall with npm install."),
    Rule(Kind.DIRNAME, ".venv", "cache", "Python virtualenv", Safety.SAFE, quiet = true, minSize = 20 * MB),
    Rule(Kind.DIRNAME, "__pycache__", "cache", "Python cache", Safety.SAFE, quiet = true, minSize = 10 * MB),

    Rule(Kind.FILE_SUFFIX, ".gguf", "ai", "AI model (GGUF)", minSize = 20 * MB,
        advice = "Local AI model. Delete it if you no longer use the app that downloaded it."),
    Rule(Kind.FILE_SUFFIX, ".safetensors", "ai", "AI model (safetensors)", minSize = 20 * MB,
        advice = "Local AI model weights."),
    Rule(Kind.FILE_SUFFIX, ".tflite", "ai", "AI model (TensorFlow Lite)", minSize = 20 * MB),
    Rule(Kind.FILE_SUFFIX, ".litertlm", "ai", "AI model (LiteRT)", minSize = 20 * MB),
    Rule(Kind.FILE_SUFFIX, ".task", "ai", "AI model (MediaPipe)", minSize = 20 * MB),
    Rule(Kind.FILE_SUFFIX, ".onnx", "ai", "AI model (ONNX)", minSize = 20 * MB),
    Rule(Kind.FILE_SUFFIX, ".apk", "downloads", "App installer", Safety.SAFE, minSize = 10 * MB,
        advice = "Installer file. Deleting it does not uninstall the app."),
    Rule(Kind.FILE_SUFFIX, ".apks", "downloads", "App installer", Safety.SAFE, minSize = 10 * MB),
    Rule(Kind.FILE_SUFFIX, ".xapk", "downloads", "App installer", Safety.SAFE, minSize = 10 * MB),
    Rule(Kind.FILE_SUFFIX, ".zip", "downloads", "Archive", minSize = 50 * MB, advice = "Archive. Delete it if it is already extracted or no longer needed."),
    Rule(Kind.FILE_SUFFIX, ".rar", "downloads", "Archive", minSize = 50 * MB),
    Rule(Kind.FILE_SUFFIX, ".7z", "downloads", "Archive", minSize = 50 * MB),
    Rule(Kind.FILE_SUFFIX, ".iso", "downloads", "Disk image", minSize = 50 * MB),
    Rule(Kind.FILE_PREFIX, ".trashed-", "trash", "In the system trash", Safety.SAFE, minSize = 10 * MB,
        advice = "Android removes trashed items after 30 days. Empty the trash in Files or Photos to free the space now."),
    Rule(Kind.FILE_PREFIX, ".pending-", "cache", "Unfinished download/save", Safety.SAFE, minSize = 10 * MB),
)

private val IMAGE = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "raw", "bmp")
private val VIDEO = setOf("mp4", "mov", "mkv", "3gp", "webm", "avi", "m4v")
private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr")
private val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "odt")

fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()
fun isMediaFile(name: String) = extOf(name).let { it in IMAGE || it in VIDEO || it in AUDIO }

fun categoryByExtension(name: String): String? = when (extOf(name)) {
    in IMAGE, in VIDEO -> "media"
    in AUDIO -> "audio"
    in DOCS -> "docs"
    else -> null
}

class RuleSet(rules: List<Rule> = RULES) {
    private val byPath = rules.filter { it.kind == Kind.PATH }.associateBy { it.match.lowercase() }
    private val byDirname = rules.filter { it.kind == Kind.DIRNAME }.associateBy { it.match.lowercase() }
    private val suffix = rules.filter { it.kind == Kind.FILE_SUFFIX }
    private val prefix = rules.filter { it.kind == Kind.FILE_PREFIX }

    fun matchDir(rel: String, name: String): Rule? = byPath[rel.lowercase()] ?: byDirname[name.lowercase()]

    fun matchFile(name: String): Rule? {
        val low = name.lowercase()
        return prefix.firstOrNull { low.startsWith(it.match) } ?: suffix.firstOrNull { low.endsWith(it.match) }
    }
}
