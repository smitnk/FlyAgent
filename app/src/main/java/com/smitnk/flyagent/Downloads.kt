package com.smitnk.flyagent

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Resumable system downloads with integrity checks. Call from a background thread. */
object Downloads {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(1 shl 20); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hugging Face publishes each LFS file's SHA-256 in the X-Linked-Etag header of the resolve redirect. */
    fun hfSha256(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.instanceFollowRedirects = false; c.connectTimeout = 15000
        c.getHeaderField("X-Linked-Etag")?.trim('"', ' ')?.takeIf { it.length == 64 }
    }.getOrNull()

    fun text(url: String): String = (URL(url).openConnection() as HttpURLConnection).run { connectTimeout = 15000; inputStream.bufferedReader().readText() }

    /** Downloads via DownloadManager (survives app pauses, shows a notification). Returns the file or throws. */
    fun fetch(ctx: Context, url: String, dest: File, title: String, progress: (Int) -> Unit): File {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val part = File(dest.parentFile, dest.name + ".part").apply { delete() }
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(url)).setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part)))
        while (true) {
            Thread.sleep(1000)
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) error("download cancelled")
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) progress((done * 100 / total).toInt())
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { dest.delete(); if (!part.renameTo(dest)) error("could not save file"); return dest }
                    DownloadManager.STATUS_FAILED -> { part.delete(); error("download failed (reason ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})") }
                }
            }
        }
    }

    /** Qwen3 GGUF from Hugging Face, verified against the published SHA-256. */
    fun installModel(ctx: Context, m: LocalLlm.Companion.ModelOption, progress: (String) -> Unit): File {
        val url = LocalLlm.REPO + m.file
        val expected = hfSha256(url)
        val tmp = File(LocalLlm.file(ctx).parentFile, "model.download")
        fetch(ctx, url, tmp, "FlyAgent: ${m.label}") { progress("Downloading model… $it%") }
        progress("Verifying…")
        val got = sha256(tmp)
        if (expected != null && got != expected) { tmp.delete(); error("checksum mismatch — download corrupted, please retry") }
        LocalLlm.reset()
        val f = LocalLlm.file(ctx); f.delete(); if (!tmp.renameTo(f)) error("could not install model")
        return f
    }

    /** Full FlyWire v783 connectome from this repo's GitHub Release, verified with its .sha256 file, then unpacked. */
    fun installFullBrain(ctx: Context, progress: (String) -> Unit): File {
        val expected = text(ConnectomeBrain.FULL_URL + ".sha256").trim().split(Regex("\\s+")).first()
        val dest = ConnectomeBrain.fullFile(ctx)
        val gz = File(dest.parentFile, "fly783_full.bin.gz")
        fetch(ctx, ConnectomeBrain.FULL_URL, gz, "FlyAgent: fly brain connectome") { progress("Downloading connectome… $it%") }
        progress("Verifying and unpacking…")
        if (sha256(gz) != expected) { gz.delete(); error("checksum mismatch — download corrupted, please retry") }
        val tmp = File(dest.parentFile, "fly783_full.tmp")
        GZIPInputStream(gz.inputStream().buffered()).use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
        gz.delete(); ConnectomeBrain.reset(); dest.delete(); if (!tmp.renameTo(dest)) error("could not install connectome")
        return dest
    }
}
