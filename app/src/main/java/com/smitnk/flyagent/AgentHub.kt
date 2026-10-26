package com.smitnk.flyagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** One chat message as the UI shows it. Mutable fields are Compose state so streaming text updates live. */
class UiMsg(val role: String, text: String = "", val episode: Long = -1, cloud: Boolean = false) {
    var text by mutableStateOf(text)
    var details by mutableStateOf("")
    var working by mutableStateOf(false)
    var cloud by mutableStateOf(cloud)
    var episodeId by mutableStateOf(episode)
    // approval cards
    var detail = ""
    var decision by mutableStateOf<Boolean?>(null)
    internal var latch: CountDownLatch? = null
}

/**
 * Owns the agent and the current chat. Work runs on a background thread inside [AgentService] (a foreground
 * service), so replies keep generating when the app is closed; the UI just observes this state.
 */
object AgentHub {
    lateinit var memory: MemoryDb; lateinit var settings: Settings; lateinit var agent: Agent; lateinit var connectors: ConnectorRegistry
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pending = 0
    /** Set by the activity: opens the email app with a draft. */
    @Volatile var emailLauncher: ((String, String, String) -> Unit)? = null
    @Volatile var foreground = false
    /** Set by the activity: opens Android's package installer for a downloaded APK. */
    @Volatile var installer: ((File) -> Unit)? = null

    val messages = mutableStateListOf<UiMsg>()
    var chatId by mutableStateOf(-1L)
    var busy by mutableStateOf(false)

    @Synchronized fun init(ctx: Context) {
        if (::agent.isInitialized) return
        app = ctx.applicationContext
        memory = MemoryDb(app); settings = Settings(app); connectors = ConnectorRegistry(settings::connectors)
        val github = GitHub({ settings.githubToken }, apkDir = File(app.cacheDir, "apks"), install = { apk -> main.post { installer?.invoke(apk) }; "Android's installer is opening — confirm to install." })
        Workspace.init(app)
        val tools = Tools(File(app.filesDir, "workspace"), memory, { ConnectomeBrain.get(app) }, connectors::call, github, Workspace::toolRun) { to, subject, body ->
            val l = emailLauncher ?: return@Tools "ERROR: open FlyAgent to send email"
            main.post { l(to, subject, body) }; "Opened the draft in your email app — press Send there."
        }
        agent = Agent(memory, tools::run, settings::llms, Vision::describe, { LocalLlm.get(app) }, connectors::tools, ::approve)
        Notify.channels(app)
    }

    fun newChat() { chatId = -1; messages.clear() }

    fun openChat(id: Long) {
        chatId = id; messages.clear()
        memory.chats().firstOrNull { it.id == id }?.let { settings.dotId = it.dot }
        memory.messages(id).forEach { messages += UiMsg(it.role, it.text, it.episode, it.cloud) }
    }

    /** Queues a message; the foreground service keeps it running even if the app is closed. */
    fun send(question: String, task: String, image: Img?, shown: String) {
        if (chatId < 0) chatId = memory.newChat(question, settings.dot.id)
        val chat = chatId
        // photos are shown from the app's own storage; the model gets "[image]" instead of a file path
        val history = messages.filter { it.role == "user" || it.role == "assistant" }.map { it.role to it.text.replace(Regex("""!\[[^\]]*]\(file://[^)]*\)"""), "[image]") }
        val photo = image?.let { img ->
            File(app.filesDir, "chat_images").apply { mkdirs() }.resolve("${System.currentTimeMillis()}.jpg").also { it.writeBytes(img.bytes) }
        }
        val display = photo?.let { "![photo](file://${it.absolutePath})\n" + shown.removePrefix("🖼 Image\n") } ?: shown
        messages += UiMsg("user", display); memory.addMessage(chat, "user", display)
        val reply = UiMsg("assistant", "").apply { working = true }; messages += reply
        busy = true; pending++
        val dot = settings.dot; val mode = settings.mode; val rounds = settings.deepThink
        app.startForegroundService(Intent(app, AgentService::class.java))
        worker.execute {
            val log = StringBuilder()
            val r = runCatching {
                agent.run(task, image, mode, settings.controller, { line -> log.appendLine(line); main.post { reply.details = log.toString().trim() } }, history, dot, rounds) { partial ->
                    main.post { reply.text = partial }; true
                }
            }.getOrElse { Result(-1, "Error: ${it.message}", false, false) }
            memory.addMessage(chat, "assistant", r.answer, r.episodeId, r.usedCloud)
            main.post {
                reply.text = r.answer; reply.cloud = r.usedCloud; reply.episodeId = r.episodeId; reply.working = false
                if (--pending == 0) busy = false
                if (!foreground) Notify.done(app, "${dot.name} finished", r.answer)
                onReply?.invoke(r.answer)
            }
        }
    }

    /** Called by the activity to speak replies when voice output is on. */
    @Volatile var onReply: ((String) -> Unit)? = null

    fun stop() { agent.stop(); runCatching { NativeBrain.cancel() } }

    /** Approval card: blocks the agent thread until the user taps Approve/Decline (notifies if the app is closed). */
    @Volatile private var unattended = false
    private fun approve(title: String, detail: String): Boolean {
        if (unattended) return false // scheduled runs never act without a person approving
        val card = UiMsg("approval", title).apply { this.detail = detail; latch = CountDownLatch(1) }
        main.post { messages += card; if (!foreground) Notify.approval(app, title) }
        card.latch!!.await()
        return card.decision == true
    }
    fun decide(card: UiMsg, ok: Boolean) { if (card.decision == null) { card.decision = ok; card.latch?.countDown() } }

    /** Scheduled tasks run here synchronously (from WorkManager); actions needing approval are declined. */
    fun runScheduled(prompt: String, dotId: String): String {
        val dot = settings.dots().firstOrNull { it.id == dotId } ?: Dot.DEFAULT
        // same single worker thread as chats, so the agent never runs two tasks at once
        return worker.submit<String> {
            val chat = memory.newChat("⏰ $prompt", dot.id)
            memory.addMessage(chat, "user", prompt)
            unattended = true
            val r = try { agent.run(prompt, null, Mode.HYBRID, settings.controller, {}, emptyList(), dot) } finally { unattended = false }
            memory.addMessage(chat, "assistant", r.answer, r.episodeId, r.usedCloud)
            r.answer
        }.get()
    }

    internal fun idle() = pending == 0
}

/** Foreground service: keeps the process alive while the agent works (Android stops background apps otherwise). */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 1, Notify.working(this),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        Thread { while (!AgentHub.idle()) Thread.sleep(500); stopSelf() }.start()
        return START_NOT_STICKY
    }
}

object Notify {
    private const val WORK = "agent_work"; private const val DONE = "agent_done"
    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WORK, "Agent working", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE, "Agent results", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun working(ctx: Context) = NotificationCompat.Builder(ctx, WORK).setSmallIcon(R.drawable.ic_stat_fly)
        .setContentTitle("FlyAgent is working…").setContentIntent(open(ctx)).setOngoing(true).build()
    private fun post(ctx: Context, id: Int, title: String, text: String) = runCatching {
        ctx.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(ctx, DONE).setSmallIcon(R.drawable.ic_stat_fly)
            .setContentTitle(title).setContentText(text.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(open(ctx)).setAutoCancel(true).build())
    }
    fun done(ctx: Context, title: String, text: String) = post(ctx, (System.currentTimeMillis() % 100000).toInt() + 10, title, text)
    fun approval(ctx: Context, title: String) = post(ctx, 2, "Approval needed", title)
}
