package com.smitnk.flyagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Chat screen: bubbles, input bar at the bottom, settings in the ⋮ menu. */
class MainActivity : Activity() {
    private lateinit var memory: MemoryDb
    private lateinit var settings: Settings
    private lateinit var agent: Agent
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var attach: ImageButton
    private var image: Img? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val dp get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (dark) Color.parseColor("#ECECEC") else Color.parseColor("#1F1F1F")
    private val muted get() = if (dark) Color.parseColor("#9A9A9A") else Color.parseColor("#6B6B6B")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        memory = MemoryDb(this); settings = Settings(this)
        agent = Agent(memory, Tools(File(filesDir, "workspace"), memory) { ConnectomeBrain.get(this) }::run, settings::llms, Vision::describe) { LocalLlm.get(this) }
        val bg = if (dark) Color.parseColor("#1E1E1E") else Color.parseColor("#FAF9F5")

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), px(8)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        input = EditText(this).apply {
            hint = "Message FlyAgent…"; setTextColor(fg); setHintTextColor(muted); maxLines = 5; background = round(if (dark) "#2A2A2A" else "#FFFFFF", 22f, "#D0D0D0")
            setPadding(px(16), px(10), px(16), px(10)); imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        attach = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_gallery); background = null; contentDescription = "Attach image"
            setOnClickListener { if (image != null) clearImage() else startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK_IMAGE) }
        }
        send = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send); background = round("#D97757", 22f); setColorFilter(Color.WHITE); contentDescription = "Send"
            setOnClickListener { if (busy) stopRun() else submit() }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(8), px(6), px(8), px(10))
            addView(attach, LinearLayout.LayoutParams(px(44), px(44)))
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = px(4); marginEnd = px(8) })
            addView(send, LinearLayout.LayoutParams(px(44), px(44)))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)); addView(bar)
        })
        updateSubtitle()
        restoreRecent()
        if (list.childCount == 0) bubble(false, "Hi! I'm FlyAgent. Ask me anything, attach an image with 📎, or give me a task like \"search blender rigs\".\n\nMode: ${settings.mode.label()}. Change it in the ⋮ menu. Try \"brain sugar\" to run the fruit-fly connectome.")
    }

    private fun Mode.label() = when (this) { Mode.OFFLINE -> "Offline (on-device only)"; Mode.CLOUD -> "Cloud only"; Mode.HYBRID -> "Hybrid (auto)" }
    private fun updateSubtitle() {
        val brain = settings.llms().firstOrNull()?.name ?: if (LocalLlm.file(this).exists()) "Qwen3 on device" else "basic offline replies"
        actionBar?.subtitle = "${settings.mode.label()} · $brain"
    }

    private fun restoreRecent() = memory.recent(6).reversed().forEach {
        bubble(true, it.task); bubble(false, it.outcome, it.id); history += "user" to it.task; history += "assistant" to it.outcome
    }

    private fun submit() {
        val text = input.text.toString().trim()
        val img = image
        if (text.isEmpty() && img == null) return
        val task = text.ifEmpty { "Describe this image and read any text in it." }
        input.setText(""); clearImage(); setBusy(true)
        bubble(true, if (img != null) "🖼 $task" else task)
        val (reply, details) = pending()
        val hist = history.toList()
        val log = StringBuilder()
        Thread {
            var lastPost = 0L
            val r = agent.run(task, img, settings.mode, settings.controller, { line -> log.appendLine(line); runOnUiThread { details.text = log.toString().trim() } }, hist) { partial ->
                val now = System.currentTimeMillis()
                if (now - lastPost > 80 && partial.isNotBlank()) { // throttle UI updates while streaming
                    lastPost = now
                    runOnUiThread { reply.text = partial; reply.setTextColor(fg); scrollDown() }
                }
                true
            }
            runOnUiThread {
                history += "user" to task; history += "assistant" to r.answer
                reply.text = r.answer; reply.setTextColor(fg)
                (reply.parent as LinearLayout).addView(actions(r.episodeId, r.usedCloud))
                setBusy(false); scrollDown()
            }
        }.start()
    }

    @Volatile private var busy = false
    /** While working, the send button becomes a Stop button. */
    private fun setBusy(b: Boolean) {
        busy = b
        send.setImageResource(if (b) android.R.drawable.ic_media_pause else android.R.drawable.ic_menu_send)
        send.contentDescription = if (b) "Stop" else "Send"
    }
    private fun stopRun() {
        agent.stop()
        runCatching { NativeBrain.cancel() } // also ends a running brain simulation
        toast("Stopping…")
    }

    /** Adds a message bubble; returns its column (assistant) so we can append actions. */
    private fun bubble(user: Boolean, text: String, episode: Long = -1): LinearLayout {
        val tv = TextView(this).apply { this.text = text; setTextColor(fg); textSize = 16f; setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(10), px(14), px(10))
            background = if (user) round(if (dark) "#3A3A3A" else "#EDE9DF", 18f) else null
            addView(tv)
            if (!user && episode > 0) addView(actions(episode, false))
        }
        list.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START; topMargin = px(6)
            if (user) marginStart = px(48) else marginEnd = px(16)
        })
        scrollDown(); return col
    }

    /** Assistant bubble with "Thinking…" and a collapsible details line (router, tools, memory). */
    private fun pending(): Pair<TextView, TextView> {
        val col = bubble(false, "Thinking…")
        val reply = col.getChildAt(0) as TextView; reply.setTextColor(muted)
        val details = TextView(this).apply { textSize = 12f; setTextColor(muted); typeface = Typeface.MONOSPACE; visibility = View.GONE }
        val toggle = TextView(this).apply {
            text = "▸ details"; textSize = 12f; setTextColor(muted); setPadding(0, px(4), 0, 0)
            setOnClickListener { val show = details.visibility != View.VISIBLE; details.visibility = if (show) View.VISIBLE else View.GONE; text = if (show) "▾ details" else "▸ details" }
        }
        col.addView(toggle); col.addView(details)
        return reply to details
    }

    private fun actions(episode: Long, cloud: Boolean) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, 0)
        fun chip(t: String, f: () -> Unit) = TextView(this@MainActivity).apply { text = t; textSize = 13f; setTextColor(muted); setPadding(0, px(4), px(14), px(4)); setOnClickListener { f() } }
        addView(chip("👍 Learn") { feedback(episode, true) })
        addView(chip("👎 Correct") { feedback(episode, false) })
        addView(TextView(this@MainActivity).apply { text = if (cloud) "AI · cloud" else "AI · on device"; textSize = 11f; setTextColor(muted) })
    }

    private fun feedback(ep: Long, good: Boolean) {
        val box = EditText(this).apply { hint = if (good) "Optional note" else "What should it have said or done?" }
        AlertDialog.Builder(this).setTitle(if (good) "Learn this as a skill?" else "Correct FlyAgent").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val note = box.text.toString()
                Thread {
                    val msgs = StringBuilder()
                    agent.feedback(ep, good, note) { msgs.appendLine(it) }
                    runOnUiThread { toast(msgs.toString().trim().ifEmpty { "Saved" }) }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Mode")
        menu.add(0, 2, 0, "Cloud APIs")
        menu.add(0, 3, 0, "Local LLM (Qwen3-0.6B)")
        menu.add(0, 4, 0, "Fly brain (connectome)")
        menu.add(0, 5, 0, "Memory & skills")
        menu.add(0, 6, 0, "New chat")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> AlertDialog.Builder(this).setTitle("Mode").setSingleChoiceItems(Mode.values().map { it.label() }.toTypedArray(), settings.mode.ordinal) { d, i ->
                settings.mode = Mode.values()[i]; updateSubtitle(); d.dismiss() }.show()
            2 -> providersDialog()
            3 -> modelDialog()
            4 -> brainDialog()
            5 -> memoryDialog()
            6 -> { list.removeAllViews(); history.clear(); bubble(false, "New chat started. Memory and skills are kept.") }
        }
        return true
    }

    private fun modelDialog() {
        val f = LocalLlm.file(this)
        val status = if (f.exists()) "Installed: ${f.length() / 1_048_576} MB" else "No local model installed."
        AlertDialog.Builder(this).setTitle("Offline brain: Qwen3-0.6B")
            .setMessage("$status\n\nQwen3-0.6B (Apache-2.0, by Alibaba Qwen) runs fully on your phone with llama.cpp. It is downloaded once from Hugging Face (ggml-org/Qwen3-0.6B-GGUF) and checked against its published SHA-256.\n\nUses about 0.6–1 GB RAM while answering. Use Wi-Fi for the download.")
            .setPositiveButton("Download") { _, _ -> chooseModel() }
            .setNeutralButton("Load .gguf file") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_MODEL) }
            .setNegativeButton(if (f.exists()) "Remove model" else "Close") { _, _ -> if (f.exists()) { LocalLlm.reset(); f.delete(); updateSubtitle() } }
            .show()
    }

    private fun chooseModel() {
        val opts = LocalLlm.MODELS
        AlertDialog.Builder(this).setTitle("Choose model").setItems(opts.map { "${it.label} — ${it.sizeMb} MB" }.toTypedArray()) { _, i ->
            background("Starting download…") { say -> Downloads.installModel(this, opts[i], say); "Qwen3 installed. Offline chat is ready." }
        }.show()
    }

    /** Runs a long job off the UI thread, showing progress in a chat bubble. */
    private fun background(start: String, job: ((String) -> Unit) -> String) {
        val col = bubble(false, start); val tv = col.getChildAt(0) as TextView
        Thread {
            val msg = try { job { p -> runOnUiThread { tv.text = p } } } catch (e: Exception) { "Failed: ${e.message}" }
            runOnUiThread { tv.text = msg; updateSubtitle() }
        }.start()
    }

    private fun brainDialog() {
        val full = ConnectomeBrain.fullFile(this).exists()
        AlertDialog.Builder(this).setTitle("Fruit-fly connectome")
            .setMessage("Real wiring data: FlyWire adult Drosophila brain v783 (CC BY 4.0). Activity is simulated with a leaky integrate-and-fire model (Shiu et al. 2024), running in C++ on this phone.\n\n" +
                (if (full) "Full brain installed (139k neurons)." else "Using the bundled subset (sugar-taste → feeding pathway). The full brain is a one-time download of about 100 MB and needs about 200 MB RAM while loaded.") +
                "\n\nThe LLM can call this simulator as a tool; it does not make the LLM biologically intelligent.")
            .setPositiveButton("Run sugar → feeding test") { _, _ -> input.setText("brain sugar 100"); submit() }
            .setNeutralButton(if (full) "Use subset" else "Download full brain") { _, _ ->
                if (full) { ConnectomeBrain.reset(); ConnectomeBrain.fullFile(this).delete(); toast("Using subset") }
                else background("Starting connectome download…") { say -> Downloads.installFullBrain(this, say); "Full fly brain installed. Try: brain sugar 100" }
            }
            .setNegativeButton("Info") { _, _ -> background("Loading connectome…") { BrainLab.info(ConnectomeBrain.get(this)) } }
            .show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // free the big native allocations when the app is in the background
        if (level >= TRIM_MEMORY_BACKGROUND) { LocalLlm.reset(); ConnectomeBrain.reset() }
    }

    @Deprecated("platform API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        if (req == PICK_MODEL) return installModel(uri)
        if (req != PICK_IMAGE) return
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return toast("Not an image")
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height)) // keep cloud requests small
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        image = Img(Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg", jpg)
        attach.setColorFilter(Color.parseColor("#D97757")); input.hint = "Image attached — ask about it…"
    }

    private fun clearImage() { image = null; attach.clearColorFilter(); input.hint = "Message FlyAgent…" }

    private fun installModel(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } ?: ""
        if (!name.endsWith(".gguf")) return toast("Pick a .gguf model file (e.g. Qwen3-0.6B-Q4_0.gguf)")
        toast("Copying model… this can take a minute")
        Thread {
            val tmp = File(LocalLlm.file(this).parentFile, "model.tmp")
            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } } }.isSuccess
            if (ok) { LocalLlm.reset(); LocalLlm.file(this).delete(); tmp.renameTo(LocalLlm.file(this)) } else tmp.delete()
            runOnUiThread { toast(if (ok) "Model installed. Offline chat is ready." else "Copy failed (not enough storage?)"); updateSubtitle() }
        }.start()
    }

    private fun memoryDialog() {
        val eps = memory.recent(200)
        val stats = eps.groupBy { it.controller.ifEmpty { "?" } }.entries.joinToString("\n") { (c, l) -> "$c: ${l.count { it.success }}/${l.size} succeeded" }
        val skills = memory.skills().joinToString("\n") { "• ${it.name} v${it.version} [${it.status}]" }.ifEmpty { "none yet — tap 👍 Learn under a good answer" }
        val text = "${memory.count()} conversations stored on this phone.\n\nSkills:\n$skills\n\nController comparison (last 200):\n$stats\n\n" + eps.take(20).joinToString("\n\n") {
            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.ts))} ${if (it.success) "✓" else "✗"}\n${it.task}"
        }
        AlertDialog.Builder(this).setTitle("Memory & skills").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun providersDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val savers = settings.providers.map { p ->
            val on = Switch(this).apply { text = p.label; isChecked = settings.enabled(p.id); textSize = 16f }
            val key = EditText(this).apply { hint = "API key"; setText(settings.key(p.id)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val url = EditText(this).apply { hint = "Base URL"; setText(settings.url(p)); textSize = 13f }
            val model = EditText(this).apply { hint = "Model"; setText(settings.model(p)); textSize = 13f }
            listOf(on, key, url, model).forEach(box::addView)
            box.addView(View(this), LinearLayout.LayoutParams(1, px(16)))
            val save: () -> Unit = { settings.save(p.id, on.isChecked, key.text.toString().trim(), url.text.toString().trim(), model.text.toString().trim()) }
            save
        }
        AlertDialog.Builder(this).setTitle("Cloud APIs (tried top to bottom)").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> savers.forEach { it() }; updateSubtitle(); toast("On: " + settings.llms().joinToString { it.name }.ifEmpty { "none" }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun round(color: String, r: Float, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = r * dp; stroke?.let { setStroke(px(1), Color.parseColor(it)) }
    }
    private fun px(v: Int) = (v * dp).toInt()
    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { const val PICK_IMAGE = 7; const val PICK_MODEL = 8 }
}
