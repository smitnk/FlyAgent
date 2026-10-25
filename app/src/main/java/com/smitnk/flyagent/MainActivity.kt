package com.smitnk.flyagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch

/**
 * Main screen: chat with a navigation drawer (new chat, Dots, Spaces, Connectors, settings, recent chats).
 * All views are built in code to keep the project small.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var memory: MemoryDb
    private lateinit var settings: Settings
    private lateinit var agent: Agent
    private lateinit var connectors: ConnectorRegistry
    private lateinit var md: Markwon
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: TextInputEditText
    private lateinit var send: MaterialButton
    private lateinit var chips: ChipGroup
    private var image: Img? = null
    private var file: Pair<String, String>? = null      // attached file name -> extracted text
    private var chatId = -1L
    private val history = mutableListOf<Pair<String, String>>()
    @Volatile private var busy = false

    private val dp get() = resources.displayMetrics.density
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private val fg get() = color(R.color.text)
    private val muted get() = color(R.color.muted)
    private val accent get() = color(R.color.accent)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        memory = MemoryDb(this); settings = Settings(this)
        connectors = ConnectorRegistry(settings::connectors)
        md = Markwon.builder(this).usePlugin(TablePlugin.create(this)).usePlugin(LinkifyPlugin.create()).build()
        val tools = Tools(File(filesDir, "workspace"), memory, { ConnectomeBrain.get(this) }, connectors::call)
        agent = Agent(memory, tools::run, settings::llms, Vision::describe, { LocalLlm.get(this) }, connectors::tools, ::askApproval)

        toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_menu); setNavigationOnClickListener { refreshDrawer(); drawer.open() }
            setOnClickListener { dotPicker() }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(4), px(16), px(16)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        chips = ChipGroup(this).apply { setPadding(px(16), 0, px(16), 0) }

        val attach = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_add); contentDescription = "Attach"; iconTint = ColorStateList.valueOf(muted)
            setOnClickListener { attachMenu() }
        }
        input = TextInputEditText(this).apply {
            hint = "Message FlyAgent…"; maxLines = 6; background = null; textSize = 16f; setTextColor(fg); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        send = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonFilledStyle).apply {
            setIconResource(R.drawable.ic_send); contentDescription = "Send"
            backgroundTintList = ColorStateList.valueOf(accent); iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnClickListener { if (busy) stopRun() else submit() }
        }
        val composer = MaterialCardView(this).apply {
            radius = 26 * dp; cardElevation = 0f; strokeWidth = px(1); strokeColor = color(R.color.bubble); setCardBackgroundColor(color(R.color.card))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), px(2), px(6), px(2))
                addView(attach, LinearLayout.LayoutParams(px(44), px(44)))
                addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(send, LinearLayout.LayoutParams(px(44), px(44)))
            })
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.surface)); fitsSystemWindows = true
            addView(toolbar); addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)); addView(chips)
            addView(composer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(px(12), px(4), px(12), px(12)) })
        }
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(16), px(12), px(16)) }
        val side = ScrollView(this).apply { addView(drawerList); setBackgroundColor(color(R.color.surface)); fitsSystemWindows = true }
        drawer = DrawerLayout(this).apply {
            addView(main, DrawerLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(side, DrawerLayout.LayoutParams(px(300), MATCH_PARENT, Gravity.START))
        }
        setContentView(drawer)
        updateHeader()
        welcome()
    }

    // ---------- header / welcome ----------

    private fun Mode.label() = when (this) { Mode.OFFLINE -> "Offline"; Mode.CLOUD -> "Cloud"; Mode.HYBRID -> "Hybrid" }
    private fun updateHeader() {
        val brain = settings.llms().firstOrNull()?.name ?: if (LocalLlm.file(this).exists()) "Qwen3 on device" else "basic offline"
        toolbar.title = settings.dot.name + " ▾"
        toolbar.subtitle = "${settings.mode.label()} · $brain"
    }

    private fun welcome() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "How can I help you today?"; textSize = 26f; setTextColor(fg); typeface = Typeface.create("serif", Typeface.NORMAL)
            setPadding(0, px(48), 0, px(8))
        })
        list.addView(TextView(this).apply {
            text = "Chatting with ${settings.dot.name}. Ask anything, attach photos, files or ZIPs, or try one of these:"
            setTextColor(muted); textSize = 15f; setPadding(0, 0, 0, px(16))
        })
        val ideas = listOf("Today's top 10 news", "Run the sugar brain experiment and explain MN9", "Summarise a ZIP file I attach", "Draft a page about fruit-fly brains")
        list.addView(ChipGroup(this).apply {
            ideas.forEach { idea -> addView(Chip(this@MainActivity).apply { text = idea; setOnClickListener {
                if (idea.startsWith("Summarise")) attachMenu() else { input.setText(idea); submit() } } }) }
        })
    }

    // ---------- sending ----------

    private fun submit() {
        val text = input.text.toString().trim()
        val img = image; val f = file
        if (text.isEmpty() && img == null && f == null) return
        val question = text.ifEmpty { if (img != null) "Describe this image and read any text in it." else "Summarise this file." }
        // the question goes last so it survives if a long attachment has to be cut to fit the model's context
        val task = if (f != null) "${f.second}\n\n$question" else question
        if (chatId < 0) { list.removeAllViews(); chatId = memory.newChat(question, settings.dot.id) }
        input.setText(""); clearAttachments(); setBusy(true)
        val shown = listOfNotNull(img?.let { "🖼 Image" }, f?.let { "📎 ${it.first}" }, question).joinToString("\n")
        userBubble(shown); memory.addMessage(chatId, "user", shown)
        val (reply, details, col) = pending()
        val hist = history.toList(); val log = StringBuilder(); val dot = settings.dot
        Thread {
            var lastPost = 0L
            val r = agent.run(task, img, settings.mode, settings.controller, { line -> log.appendLine(line); runOnUiThread { details.text = log.toString().trim() } },
                hist, dot) { partial ->
                val now = System.currentTimeMillis()
                if (now - lastPost > 100 && partial.isNotBlank()) { lastPost = now; runOnUiThread { md.setMarkdown(reply, partial); reply.setTextColor(fg); scrollDown() } }
                true
            }
            memory.addMessage(chatId, "assistant", r.answer, r.episodeId, r.usedCloud)
            runOnUiThread {
                history += "user" to question; history += "assistant" to r.answer
                md.setMarkdown(reply, r.answer); reply.setTextColor(fg)
                col.addView(actions(r.episodeId, r.usedCloud, r.answer), 1)
                setBusy(false); scrollDown()
            }
        }.start()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        send.setIconResource(if (b) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = if (b) "Stop" else "Send"
    }

    private fun stopRun() { agent.stop(); runCatching { NativeBrain.cancel() }; toast("Stopping…") }

    // ---------- messages ----------

    private fun userBubble(text: String) {
        val tv = TextView(this).apply {
            this.text = text; setTextColor(fg); textSize = 16f; setTextIsSelectable(true); setLineSpacing(0f, 1.15f)
            background = GradientDrawable().apply { setColor(color(R.color.bubble)); cornerRadius = 20 * dp }
            setPadding(px(16), px(10), px(16), px(10))
        }
        list.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = px(14); marginStart = px(56) })
        scrollDown()
    }

    /** Assistant message: markdown text, then actions, then a collapsible details log. */
    private fun assistantBlock(markdown: String): Triple<TextView, TextView, LinearLayout> {
        val reply = TextView(this).apply { textSize = 16f; setTextColor(fg); setTextIsSelectable(true); setLineSpacing(0f, 1.2f) }
        md.setMarkdown(reply, markdown)
        val details = TextView(this).apply { textSize = 12f; setTextColor(muted); typeface = Typeface.MONOSPACE; visibility = View.GONE }
        val toggle = TextView(this).apply {
            text = "▸ details"; textSize = 12f; setTextColor(muted); setPadding(0, px(2), 0, 0)
            setOnClickListener { val show = details.visibility != View.VISIBLE; details.visibility = if (show) View.VISIBLE else View.GONE; text = if (show) "▾ details" else "▸ details" }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(reply); addView(toggle); addView(details) }
        list.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(14) })
        scrollDown()
        return Triple(reply, details, col)
    }

    private fun pending(): Triple<TextView, TextView, LinearLayout> = assistantBlock("Thinking…").also { it.first.setTextColor(muted) }

    private fun actions(episode: Long, cloud: Boolean, answer: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, 0)
        fun act(label: String, f: () -> Unit) = TextView(this@MainActivity).apply {
            text = label; textSize = 13f; setTextColor(muted); setPadding(0, px(6), px(16), px(6)); setOnClickListener { f() }
        }
        addView(act("Copy") { copy(answer) })
        if (episode > 0) { addView(act("👍") { feedback(episode, true) }); addView(act("👎") { feedback(episode, false) }) }
        addView(act("Save page") { editPage(Page(0, 0, answer.lineSequence().first().take(60).trim('#', ' ', '*'), answer)) })
        addView(TextView(this@MainActivity).apply { text = if (cloud) "cloud" else "on device"; textSize = 11f; setTextColor(muted) })
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FlyAgent", s)); toast("Copied")
    }

    /** Human-in-the-loop card (OpenDots style). Called on the agent thread; blocks until the user decides. */
    private fun askApproval(title: String, detail: String): Boolean {
        val latch = CountDownLatch(1); var ok = false
        runOnUiThread {
            val card = MaterialCardView(this).apply { radius = 16 * dp; strokeWidth = px(1); strokeColor = accent; setCardBackgroundColor(color(R.color.card)) }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), px(8)) }
            body.addView(TextView(this).apply { text = "Approval needed"; setTextColor(accent); textSize = 12f })
            body.addView(TextView(this).apply { text = title; setTextColor(fg); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(4), 0, px(6)) })
            body.addView(TextView(this).apply { text = detail; setTextColor(muted); textSize = 13f; maxLines = 12; typeface = Typeface.MONOSPACE })
            val row = LinearLayout(this).apply { gravity = Gravity.END }
            fun decide(v: Boolean, b: MaterialButton) { ok = v; row.removeAllViews(); row.addView(TextView(this).apply { text = if (v) "✓ Approved" else "✗ Declined"; setTextColor(muted) }); latch.countDown() }
            val decline = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Decline" }
            val approve = MaterialButton(this).apply { text = "Approve"; backgroundTintList = ColorStateList.valueOf(accent) }
            decline.setOnClickListener { decide(false, decline) }; approve.setOnClickListener { decide(true, approve) }
            row.addView(decline); row.addView(approve); body.addView(row); card.addView(body)
            list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(10) })
            scrollDown()
        }
        latch.await()
        return ok
    }

    private fun feedback(ep: Long, good: Boolean) {
        val (box, layout) = field(if (good) "Optional note" else "What should it have said or done?")
        MaterialAlertDialogBuilder(this).setTitle(if (good) "Learn this as a skill?" else "Correct FlyAgent").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val note = box.text.toString()
                Thread { val msgs = StringBuilder(); agent.feedback(ep, good, note) { msgs.appendLine(it) }; runOnUiThread { toast(msgs.toString().trim().ifEmpty { "Saved" }) } }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- attachments ----------

    private fun attachMenu() = MaterialAlertDialogBuilder(this).setTitle("Attach")
        .setItems(arrayOf("Photo", "File or ZIP")) { _, i ->
            if (i == 0) startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK_IMAGE)
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_FILE)
        }.show()

    private fun showChip(label: String) {
        chips.removeAllViews()
        chips.addView(Chip(this).apply { text = label; isCloseIconVisible = true; setOnCloseIconClickListener { clearAttachments() } })
    }
    private fun clearAttachments() { image = null; file = null; chips.removeAllViews() }

    private fun displayName(uri: Uri) = contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } ?: "file"

    @Deprecated("platform API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        when (req) {
            PICK_MODEL -> installModel(uri)
            PICK_FILE -> {
                val name = displayName(uri)
                Thread {
                    val text = runCatching {
                        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        if (bytes.size > 50_000_000) error("file is larger than 50 MB")
                        Attachments.read(name, bytes)
                    }
                    runOnUiThread { text.onSuccess { file = name to it; showChip("📎 $name") }.onFailure { toast("Can't attach: ${it.message}") } }
                }.start()
            }
            PICK_IMAGE -> {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return toast("Not an image")
                val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height)) // keep cloud requests small
                val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                image = Img(Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg", jpg); showChip("🖼 Image")
            }
        }
    }

    // ---------- drawer ----------

    private fun refreshDrawer() {
        drawerList.removeAllViews()
        drawerList.addView(TextView(this).apply { text = "FlyAgent"; textSize = 22f; setTextColor(fg); typeface = Typeface.create("serif", Typeface.NORMAL); setPadding(px(8), 0, 0, px(12)) })
        fun item(label: String, f: () -> Unit) = drawerList.addView(TextView(this).apply {
            text = label; textSize = 16f; setTextColor(fg); setPadding(px(8), px(12), px(8), px(12))
            setOnClickListener { drawer.close(); f() }
        })
        fun header(label: String) = drawerList.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(muted); setPadding(px(8), px(18), 0, px(4)) })
        item("＋  New chat") { newChat() }
        item("◎  Dots — ${settings.dot.name}") { dotPicker() }
        item("▤  Spaces (${memory.pages().size} pages)") { spaces() }
        item("⇄  Connectors (${settings.connectors().count { it.enabled }})") { connectorsDialog() }
        header("SETTINGS")
        item("Mode — ${settings.mode.label()}") { modeDialog() }
        item("Cloud APIs") { providersDialog() }
        item("Local LLM (Qwen3-0.6B)") { modelDialog() }
        item("Fly brain (connectome)") { brainDialog() }
        item("Memory & skills") { memoryDialog() }
        val chats = memory.chats()
        if (chats.isNotEmpty()) header("RECENTS")
        chats.take(30).forEach { c ->
            drawerList.addView(TextView(this).apply {
                text = c.title; textSize = 15f; maxLines = 1; setTextColor(if (c.id == chatId) accent else fg); setPadding(px(8), px(10), px(8), px(10))
                setOnClickListener { drawer.close(); openChat(c.id) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle("Delete chat?").setMessage(c.title)
                        .setPositiveButton("Delete") { _, _ -> memory.deleteChat(c.id); if (c.id == chatId) newChat(); refreshDrawer() }
                        .setNegativeButton("Cancel", null).show(); true
                }
            })
        }
    }

    private fun newChat() { chatId = -1; history.clear(); welcome() }

    private fun openChat(id: Long) {
        chatId = id; history.clear(); list.removeAllViews()
        memory.chats().firstOrNull { it.id == id }?.let { c -> settings.dotId = c.dot; updateHeader() }
        memory.messages(id).forEach { m ->
            if (m.role == "user") userBubble(m.text) else assistantBlock(m.text).third.addView(actions(m.episode, m.cloud, m.text), 1)
            history += m.role to m.text
        }
    }

    // ---------- Dots ----------

    private fun dotPicker() {
        val dots = settings.dots()
        MaterialAlertDialogBuilder(this).setTitle("Choose a Dot")
            .setSingleChoiceItems(dots.map { "${it.name}  ·  ${it.toolGroups.joinToString()}" }.toTypedArray(), dots.indexOfFirst { it.id == settings.dotId }) { d, i ->
                settings.dotId = dots[i].id; updateHeader(); newChat(); d.dismiss()
            }
            .setPositiveButton("New Dot") { _, _ -> editDot(null) }
            .setNeutralButton("Edit current") { _, _ -> if (Dot.PRESETS.any { it.id == settings.dotId }) toast("Built-in Dots can't be edited — create a new one") else editDot(settings.dot) }
            .show()
    }

    private fun editDot(existing: Dot?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val (name, nameL) = field("Name", existing?.name ?: ""); val (instr, instrL) = field("Role and instructions", existing?.instructions ?: "", lines = 4)
        box.addView(nameL); box.addView(instrL)
        box.addView(TextView(this).apply { text = "Allowed tools"; setTextColor(muted); setPadding(0, px(12), 0, 0) })
        val checks = Dot.GROUPS.keys.map { g -> CheckBox(this).apply { text = g; isChecked = existing?.toolGroups?.contains(g) ?: true }.also(box::addView) }
        MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "New Dot" else "Edit ${existing.name}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { return@setPositiveButton toast("Name required") }
                val d = Dot(existing?.id ?: "dot${System.currentTimeMillis()}", n, instr.text.toString().trim(), checks.filter { it.isChecked }.map { it.text.toString() }.toSet())
                val custom = settings.dots().filter { it !in Dot.PRESETS && it.id != d.id } + d
                settings.saveCustomDots(custom); settings.dotId = d.id; updateHeader(); newChat()
            }
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> settings.saveCustomDots(settings.dots().filter { it !in Dot.PRESETS && it.id != existing.id }); settings.dotId = Dot.DEFAULT.id; updateHeader(); newChat() } }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Spaces (pages) ----------

    private fun spaces() {
        val pages = memory.pages()
        MaterialAlertDialogBuilder(this).setTitle("Spaces")
            .setItems(if (pages.isEmpty()) arrayOf("No pages yet — tap “Save page” under an answer, or ask a Dot to draft one.") else
                pages.map { "${it.title}\n${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.ts))}" }.toTypedArray()) { _, i -> pages.getOrNull(i)?.let(::openPage) }
            .setPositiveButton("New page") { _, _ -> editPage(Page(0, 0, "", "")) }
            .show()
    }

    private fun openPage(p: Page) {
        val tv = TextView(this).apply { setTextColor(fg); textSize = 15f; setPadding(px(24), px(8), px(24), px(8)); setTextIsSelectable(true) }
        md.setMarkdown(tv, p.body)
        MaterialAlertDialogBuilder(this).setTitle(p.title).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Edit") { _, _ -> editPage(p) }
            .setNeutralButton("Ask about it") { _, _ -> file = "page ${p.title}" to "Page \"${p.title}\":\n${p.body}"; showChip("▤ ${p.title}"); input.requestFocus() }
            .setNegativeButton("Delete") { _, _ -> memory.deletePage(p.id); toast("Deleted") }
            .show()
    }

    private fun editPage(p: Page) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val (title, tl) = field("Title", p.title); val (body, bl) = field("Content (Markdown)", p.body, lines = 10)
        box.addView(tl); box.addView(bl)
        MaterialAlertDialogBuilder(this).setTitle(if (p.id > 0) "Edit page" else "Save page").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> memory.savePage(title.text.toString().ifBlank { "Untitled" }, body.text.toString(), p.id); toast("Saved to Spaces") }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Connectors (MCP) ----------

    private fun connectorsDialog() {
        val cs = settings.connectors()
        MaterialAlertDialogBuilder(this).setTitle("Connectors (MCP)")
            .setItems(if (cs.isEmpty()) arrayOf("No connectors yet. Add a remote MCP server (HTTPS URL) to give Dots new skills: email, calendar, GitHub, your own tools…")
                      else cs.map { "${if (it.enabled) "●" else "○"} ${it.name}\n${it.url}" }.toTypedArray()) { _, i -> cs.getOrNull(i)?.let(::connectorActions) }
            .setPositiveButton("Add") { _, _ -> addConnector() }
            .show()
    }

    private fun addConnector() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val (name, nl) = field("Name (e.g. github)", ""); val (url, ul) = field("Server URL (https://…/mcp)", "")
        val (token, tl) = field("Bearer token (optional)", "", password = true)
        listOf(nl, ul, tl).forEach(box::addView)
        box.addView(TextView(this).apply { text = "Read-only tools run on their own. Anything else asks you to Approve & run first."; setTextColor(muted); textSize = 13f; setPadding(0, px(8), 0, 0) })
        MaterialAlertDialogBuilder(this).setTitle("Add connector").setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val c = Connector(name.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_"), url.text.toString().trim(), token.text.toString().trim())
                if (c.name.isBlank() || !c.url.startsWith("https://")) return@setPositiveButton toast("Name and an https:// URL are required")
                settings.saveConnectors(settings.connectors().filter { it.name != c.name } + c); testConnector(c)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun connectorActions(c: Connector) = MaterialAlertDialogBuilder(this).setTitle(c.name)
        .setItems(arrayOf("Test & list tools", if (c.enabled) "Disable" else "Enable", "Remove")) { _, i ->
            when (i) {
                0 -> testConnector(c)
                1 -> { settings.saveConnectors(settings.connectors().map { if (it.name == c.name) it.copy(enabled = !it.enabled) else it }); connectors.reset() }
                2 -> { settings.saveConnectors(settings.connectors().filter { it.name != c.name }); connectors.reset() }
            }
        }.show()

    private fun testConnector(c: Connector) {
        toast("Connecting to ${c.name}…")
        Thread {
            val r = runCatching { connectors.test(c) }
            runOnUiThread {
                r.onSuccess { t -> MaterialAlertDialogBuilder(this).setTitle("${c.name}: ${t.size} tools")
                    .setMessage(t.joinToString("\n") { "• ${it.name}${if (it.readOnly) " (read-only)" else ""}" }.ifEmpty { "No tools." }).setPositiveButton("OK", null).show() }
                    .onFailure { toast("${c.name}: ${it.message}") }
            }
        }.start()
    }

    // ---------- settings ----------

    private fun modeDialog() = MaterialAlertDialogBuilder(this).setTitle("Mode")
        .setSingleChoiceItems(arrayOf("Offline — on-device only", "Cloud — API models only", "Hybrid — cloud when available, else on device"), settings.mode.ordinal) { d, i ->
            settings.mode = Mode.values()[i]; updateHeader(); d.dismiss() }.show()

    private fun modelDialog() {
        val f = LocalLlm.file(this)
        val status = if (f.exists()) "Installed: ${f.length() / 1_048_576} MB" else "No local model installed."
        MaterialAlertDialogBuilder(this).setTitle("Offline brain: Qwen3-0.6B")
            .setMessage("$status\n\nQwen3-0.6B (Apache-2.0, by Alibaba Qwen) runs fully on your phone with llama.cpp. It is downloaded once from Hugging Face (ggml-org/Qwen3-0.6B-GGUF) and checked against its published SHA-256.\n\nGood for quick offline answers; cloud models are much better for open conversation. Use Wi-Fi for the download.")
            .setPositiveButton("Download") { _, _ -> chooseModel() }
            .setNeutralButton("Load .gguf file") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_MODEL) }
            .setNegativeButton(if (f.exists()) "Remove model" else "Close") { _, _ -> if (f.exists()) { LocalLlm.reset(); f.delete(); updateHeader() } }
            .show()
    }

    private fun chooseModel() {
        val opts = LocalLlm.MODELS
        MaterialAlertDialogBuilder(this).setTitle("Choose model").setItems(opts.map { "${it.label} — ${it.sizeMb} MB" }.toTypedArray()) { _, i ->
            background("Starting download…") { say -> Downloads.installModel(this, opts[i], say); "Qwen3 installed. Offline chat is ready." }
        }.show()
    }

    /** Runs a long job off the UI thread, showing progress in the chat. */
    private fun background(start: String, job: ((String) -> Unit) -> String) {
        if (chatId < 0 && list.childCount > 0) list.removeAllViews()
        val tv = assistantBlock(start).first
        Thread {
            val msg = try { job { p -> runOnUiThread { tv.text = p } } } catch (e: Exception) { "Failed: ${e.message}" }
            runOnUiThread { md.setMarkdown(tv, msg); updateHeader() }
        }.start()
    }

    private fun brainDialog() {
        val full = ConnectomeBrain.fullFile(this).exists()
        MaterialAlertDialogBuilder(this).setTitle("Fruit-fly connectome")
            .setMessage("Real wiring data: FlyWire adult Drosophila brain v783 (CC BY 4.0). Activity is simulated with a leaky integrate-and-fire model (Shiu et al. 2024), running in C++ on this phone.\n\n" +
                (if (full) "Full brain installed (139k neurons)." else "Using the bundled subset (sugar-taste → feeding pathway). The full brain is a one-time ~50 MB download and needs ~200 MB RAM while loaded.") +
                "\n\nAny LLM (cloud or on device) can call this simulator as a tool; it does not make the LLM biologically intelligent.")
            .setPositiveButton("Run sugar → feeding test") { _, _ -> input.setText("brain sugar 100"); submit() }
            .setNeutralButton(if (full) "Use subset" else "Download full brain") { _, _ ->
                if (full) { ConnectomeBrain.reset(); ConnectomeBrain.fullFile(this).delete(); toast("Using subset") }
                else background("Starting connectome download…") { say -> Downloads.installFullBrain(this, say); "Full fly brain installed. Try: brain sugar 100" }
            }
            .setNegativeButton("Info") { _, _ -> background("Loading connectome…") { BrainLab.info(ConnectomeBrain.get(this)) } }
            .show()
    }

    private fun installModel(uri: Uri) {
        val name = displayName(uri)
        if (!name.endsWith(".gguf")) return toast("Pick a .gguf model file (e.g. Qwen3-0.6B-Q4_0.gguf)")
        toast("Copying model… this can take a minute")
        Thread {
            val tmp = File(filesDir, "model.tmp")
            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } } }.isSuccess
            if (ok) { LocalLlm.reset(); LocalLlm.file(this).delete(); tmp.renameTo(LocalLlm.file(this)) } else tmp.delete()
            runOnUiThread { toast(if (ok) "Model installed. Offline chat is ready." else "Copy failed (not enough storage?)"); updateHeader() }
        }.start()
    }

    private fun memoryDialog() {
        val eps = memory.recent(200)
        val skills = memory.skills().joinToString("\n") { "• ${it.name} v${it.version} [${it.status}]" }.ifEmpty { "none yet — tap 👍 under a good answer" }
        val text = "${memory.count()} tasks remembered on this phone.\n\nSkills:\n$skills\n\nRecent:\n" + eps.take(20).joinToString("\n") {
            "${if (it.success) "✓" else "✗"} ${it.task.lineSequence().last().take(70)}"
        }
        MaterialAlertDialogBuilder(this).setTitle("Memory & skills").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun providersDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val savers = settings.providers.map { p ->
            val on = MaterialSwitch(this).apply { text = p.label; isChecked = settings.enabled(p.id); textSize = 16f }
            val (key, kl) = field("API key", settings.key(p.id), password = true)
            val (url, ul) = field("Base URL", settings.url(p)); val (model, ml) = field("Model", settings.model(p))
            listOf(on, kl, ul, ml).forEach(box::addView)
            box.addView(View(this), LinearLayout.LayoutParams(1, px(16)))
            val save: () -> Unit = { settings.save(p.id, on.isChecked, key.text.toString().trim(), url.text.toString().trim(), model.text.toString().trim()) }
            save
        }
        MaterialAlertDialogBuilder(this).setTitle("Cloud APIs (tried top to bottom)").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> savers.forEach { it() }; updateHeader(); toast("On: " + settings.llms().joinToString { it.name }.ifEmpty { "none" }) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- helpers ----------

    private fun field(hint: String, value: String, lines: Int = 1, password: Boolean = false): Pair<TextInputEditText, TextInputLayout> {
        val layout = TextInputLayout(this).apply { this.hint = hint; setPadding(0, px(4), 0, 0) }
        val et = TextInputEditText(layout.context).apply {
            setText(value); if (lines > 1) { minLines = lines; gravity = Gravity.TOP }
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(et)
        return et to layout
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // free the big native allocations in the background — but never while a reply is still being generated
        // (freeing the model mid-answer used to break the reply when the app was minimised)
        if (level >= TRIM_MEMORY_BACKGROUND && !busy) { LocalLlm.reset(); ConnectomeBrain.reset() }
    }

    private fun px(v: Int) = (v * dp).toInt()
    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { const val PICK_IMAGE = 7; const val PICK_MODEL = 8; const val PICK_FILE = 9 }
}
