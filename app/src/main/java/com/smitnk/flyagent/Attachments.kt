package com.smitnk.flyagent

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Turns an attached file into text the model can read. ZIPs are listed and their text files extracted, with zip-bomb limits. */
object Attachments {
    const val MAX_FILE = 64 * 1024        // per extracted text file
    const val MAX_TOTAL = 200 * 1024      // all extracted text
    const val MAX_ENTRIES = 500
    private val textExt = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "yml", "yaml", "toml", "ini", "cfg", "log",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "sql", "gradle", "properties", "srt")

    fun isText(name: String) = name.substringAfterLast('.', "").lowercase() in textExt || !name.contains('.')

    /** Returns a readable description of the file, or throws with a user-facing message. */
    fun read(name: String, bytes: ByteArray): String = when {
        name.lowercase().endsWith(".zip") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) -> zip(name, bytes)
        isText(name) -> "File $name (${bytes.size} bytes):\n" + decode(bytes.copyOf(minOf(bytes.size, MAX_TOTAL))) +
            if (bytes.size > MAX_TOTAL) "\n…(truncated)" else ""
        else -> error("Can't read .${name.substringAfterLast('.')} files yet. Supported: text, code, CSV/JSON and ZIP archives (images via 📷).")
    }

    private fun decode(b: ByteArray): String {
        if (b.count { it == 0.toByte() } > b.size / 100 + 1) error("looks like a binary file")
        return String(b, Charsets.UTF_8)
    }

    fun zip(name: String, bytes: ByteArray): String {
        val listing = StringBuilder(); val contents = StringBuilder()
        var entries = 0; var total = 0; var skipped = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++entries > MAX_ENTRIES) { listing.append("…more than $MAX_ENTRIES entries, stopped\n"); break }
                // read with a hard cap: the declared size can lie (zip bombs)
                val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192); var n: Int
                while (z.read(chunk).also { n = it } > 0) { if (buf.size() + n > MAX_FILE) { buf.write(chunk, 0, MAX_FILE - buf.size()); break }; buf.write(chunk, 0, n) }
                val size = if (e.size >= 0) e.size else buf.size().toLong()
                listing.append(if (e.isDirectory) "📁 ${e.name}\n" else "📄 ${e.name} (${size} B)\n")
                if (!e.isDirectory && isText(e.name) && total < MAX_TOTAL) {
                    val text = runCatching { decode(buf.toByteArray()) }.getOrNull()
                    if (text != null) {
                        val part = text.take(MAX_TOTAL - total); total += part.length
                        contents.append("\n----- ${e.name} -----\n").append(part)
                        if (buf.size() >= MAX_FILE || part.length < text.length) contents.append("\n…(truncated)")
                    }
                } else if (!e.isDirectory) skipped++
            }
        }
        if (entries == 0) error("empty or unreadable ZIP")
        return "ZIP archive $name: $entries entries\n$listing" + (if (skipped > 0) "($skipped non-text files listed only)\n" else "") + contents
    }
}
