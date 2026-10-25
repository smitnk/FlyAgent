package com.smitnk.flyagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.widget.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var memory: MemoryDb
    private lateinit var settings: Settings
    private lateinit var agent: Agent
    private lateinit var out: TextView
    private lateinit var imgBtn: Button
    private var image: Img? = null
    private var lastEpisode = -1L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        memory = MemoryDb(this); settings = Settings(this)
        val tools = Tools(File(filesDir, "workspace"), memory)
        agent = Agent(memory, tools::run, settings::llms, Vision::describe)

        val pad = (12 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply { hint = "Give FlyAgent a task…"; minLines = 2 }
        val modeSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Mode.values().map { it.name })
            setSelection(settings.mode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { settings.mode = Mode.values()[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val fly = Switch(this).apply { text = "Bio-AGI fly router"; isChecked = settings.useFlyRouter; setOnCheckedChangeListener { _, c -> settings.useFlyRouter = c } }
        val run = Button(this).apply { text = "Run" }
        imgBtn = Button(this).apply { text = "Image"; setOnClickListener {
            if (image != null) { image = null; text = "Image" }
            else startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK) // user picks one file; no storage permission
        } }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        val row1 = LinearLayout(this).apply { addView(modeSpin, LinearLayout.LayoutParams(0, -2, 1f)); addView(fly) }
        val row2 = LinearLayout(this).apply {
            listOf(run, imgBtn, btn("Memory") { history() }, btn("APIs") { providersDialog() }).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        val row3 = LinearLayout(this).apply {
            addView(btn("👍 Learn") { feedback(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("👎 Correct") { feedback(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        out = TextView(this).apply { setTextIsSelectable(true); textSize = 14f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            addView(input); addView(row1); addView(row2); addView(row3)
            addView(ScrollView(this@MainActivity).apply { addView(out) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        print("Memory: ${memory.count()} episodes, ${memory.skills().size} skills on this device.")
        print("Cloud APIs on: ${settings.llms().joinToString { it.name }.ifEmpty { "none" }}\n\n${LocalCommands.HELP}")

        run.setOnClickListener {
            val task = input.text.toString().trim().ifEmpty { if (image != null) "Describe this image and read any text in it." else return@setOnClickListener }
            run.isEnabled = false; input.setText(""); out.text = ""
            print("TASK: $task  [${settings.mode}]")
            val img = image
            Thread {
                val r = agent.run(task, img, settings.mode, settings.controller) { line -> runOnUiThread { print(line) } }
                runOnUiThread {
                    lastEpisode = r.episodeId
                    print("\nRESULT:\n${r.answer}\n\n(AI-generated${if (r.usedCloud) " via cloud" else " on device"}. Tap 👍 to teach it as a skill, 👎 to correct.)")
                    run.isEnabled = true; image = null; imgBtn.text = "Image"
                }
            }.start()
        }
    }

    @Deprecated("platform API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != PICK || res != RESULT_OK) return
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return toast("Not an image")
        // downscale so cloud requests stay small
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        image = Img(Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg", jpg)
        imgBtn.text = "✕ Image"; toast("Image attached (${jpg.size / 1024} KB)")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun print(s: String) = out.append(s + "\n")

    private fun feedback(good: Boolean) {
        if (lastEpisode < 0) return toast("Run a task first")
        val box = EditText(this).apply { hint = if (good) "Optional note" else "What should it have done?" }
        AlertDialog.Builder(this).setTitle(if (good) "Learn this as a skill?" else "Correct the agent").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val note = box.text.toString(); val ep = lastEpisode
                Thread { agent.feedback(ep, good, note) { l -> runOnUiThread { print(l) } } }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun history() {
        val eps = memory.recent(200)
        val stats = eps.groupBy { it.controller.ifEmpty { "?" } }.entries.joinToString("\n") { (c, l) ->
            "$c: ${l.count { it.success }}/${l.size} succeeded"
        }
        val skills = memory.skills().joinToString("\n") { "• ${it.name} v${it.version} [${it.status}]" }.ifEmpty { "none yet" }
        val text = "Controller comparison (last 200):\n$stats\n\nSkills:\n$skills\n\n" + eps.take(30).joinToString("\n\n") {
            "#${it.id} ${DateFormat.getDateTimeInstance().format(Date(it.ts))} ${if (it.success) "✓" else "✗"} ${it.controller}\n${it.task}\n→ ${it.outcome.take(200)}"
        }
        AlertDialog.Builder(this).setTitle("Memory").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun providersDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val savers = settings.providers.map { p ->
            val on = Switch(this).apply { text = p.label; isChecked = settings.enabled(p.id) }
            val key = EditText(this).apply { hint = "API key"; setText(settings.key(p.id)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val url = EditText(this).apply { hint = "Base URL"; setText(settings.url(p)) }
            val model = EditText(this).apply { hint = "Model"; setText(settings.model(p)) }
            listOf(on, key, url, model).forEach(box::addView)
            box.addView(TextView(this).apply { text = " " })
            { settings.save(p.id, on.isChecked, key.text.toString().trim(), url.text.toString().trim(), model.text.toString().trim()) }
        }
        AlertDialog.Builder(this).setTitle("Cloud APIs (tried top to bottom)").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> savers.forEach { it() }; toast("Saved. On: " + settings.llms().joinToString { it.name }.ifEmpty { "none" }) }
            .setNegativeButton("Cancel", null).show()
    }

    companion object { const val PICK = 7 }
}
