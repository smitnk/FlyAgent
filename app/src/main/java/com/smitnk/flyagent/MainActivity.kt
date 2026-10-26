package com.smitnk.flyagent

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smitnk.flyagent.ui.FlyApp
import com.smitnk.flyagent.ui.FlyTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Hosts the Compose UI and the Android pieces it needs: pickers, voice, email, notifications. */
class MainActivity : ComponentActivity() {
    // composer state (kept here so pickers can fill it)
    var input by mutableStateOf("")
    var image by mutableStateOf<Img?>(null)
    var file by mutableStateOf<Pair<String, String>?>(null)
    /** Progress/status line shown at the top of the screen for long jobs (downloads etc.). */
    var status by mutableStateOf<String?>(null)
    private var tts: TextToSpeech? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::loadImage) }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::loadFile) }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::installModel) }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it; send() }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        AgentHub.init(this)
        AgentHub.emailLauncher = { to, subject, body ->
            runCatching {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body))
            }.onFailure { toast("No email app found") }
        }
        AgentHub.installer = { apk ->
            if (!packageManager.canRequestPackageInstalls()) {
                toast("Allow FlyAgent to install apps, then ask again")
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", apk),
                "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        AgentHub.onReply = { if (AgentHub.settings.speakReplies) speak(it) }
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { FlyTheme { FlyApp(this) } }
    }

    override fun onStart() { super.onStart(); AgentHub.foreground = true }
    override fun onStop() { super.onStop(); AgentHub.foreground = false }

    // ---------- actions used by the UI ----------

    fun send() {
        val text = input.trim(); val img = image; val f = file
        if (text.isEmpty() && img == null && f == null) return
        val question = text.ifEmpty { if (img != null) "Describe this image and read any text in it." else "Summarise this file." }
        // the question goes last so it survives if a long attachment has to be cut to fit the model's context
        val task = if (f != null) "${f.second}\n\n$question" else question
        val shown = listOfNotNull(img?.let { "🖼 Image" }, f?.let { "📎 ${it.first}" }, question).joinToString("\n")
        input = ""; image = null; file = null
        AgentHub.send(question, task, img, shown)
    }

    fun attachPhoto() = pickImage.launch("image/*")
    fun attachFile() = pickFile.launch(arrayOf("*/*"))
    fun pickModelFile() = pickModel.launch(arrayOf("*/*"))
    fun voice() = runCatching {
        speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to FlyAgent"))
    }.onFailure { toast("Speech recognition isn't available on this phone") }

    private fun speak(text: String) {
        val clean = text.substringBefore("— Simulation output —").substringBefore("— Sources —").replace(Regex("[*#`>|_]"), "").take(1500)
        val t = tts
        if (t == null) tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) { tts?.language = Locale.getDefault(); tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "r") } }
        else t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "r")
    }
    fun stopSpeaking() { tts?.stop() }

    /** Runs a long job off the UI thread, reporting progress in the status line. */
    fun job(start: String, work: ((String) -> Unit) -> String) {
        status = start
        Thread {
            val msg = try { work { p -> runOnUiThread { status = p } } } catch (e: Exception) { "Failed: ${e.message}" }
            runOnUiThread { status = msg }
        }.start()
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- pickers ----------

    private fun displayName(uri: Uri) = contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } ?: "file"

    private fun loadImage(uri: Uri) {
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return toast("Not an image")
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height)) // keep cloud requests small
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        image = Img(Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg", jpg)
    }

    private fun loadFile(uri: Uri) {
        val name = displayName(uri)
        Thread {
            val text = runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 50_000_000) error("file is larger than 50 MB")
                Attachments.read(name, bytes)
            }
            runOnUiThread { text.onSuccess { file = name to it }.onFailure { toast("Can't attach: ${it.message}") } }
        }.start()
    }

    private fun installModel(uri: Uri) {
        val name = displayName(uri)
        if (!name.endsWith(".gguf")) return toast("Pick a .gguf model file (e.g. Qwen3-0.6B-Q4_0.gguf)")
        job("Copying model…") {
            val tmp = File(filesDir, "model.tmp")
            contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            LocalLlm.reset(); LocalLlm.file(this).delete(); tmp.renameTo(LocalLlm.file(this))
            "Model installed. Offline chat is ready."
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // free the big native allocations in the background — but never while a reply is still being generated
        if (level >= TRIM_MEMORY_BACKGROUND && !AgentHub.busy) { LocalLlm.reset(); ConnectomeBrain.reset() }
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}
