package com.smitnk.flyagent

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smitnk.flyagent.mh.model.AgentKind
import com.smitnk.flyagent.mh.model.ChatMessage
import com.smitnk.flyagent.mh.model.DevStack
import com.smitnk.flyagent.mh.model.ProjectKind
import com.smitnk.flyagent.mh.model.ProviderKind
import com.smitnk.flyagent.mh.model.ProviderProfile
import com.smitnk.flyagent.mh.model.RuntimeEvent
import com.smitnk.flyagent.mh.model.ToolRequest
import com.smitnk.flyagent.mh.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** A folder in the Linux workspace: /workspace/<slug> inside Ubuntu, files/workspaces/<id> on the phone. */
data class WsProject(val id: String, val name: String) {
    val slug get() = id
    fun json() = JSONObject().put("id", id).put("name", name)
}

/** One line in a workspace agent's activity feed. */
class WsEvent(val kind: String, text: String, val request: ToolRequest? = null) {
    var text by mutableStateOf(text)
    var decided by mutableStateOf<Boolean?>(null)
}

/**
 * The Linux workspace (Ubuntu in PRoot, from Mobile Harness): setup, a command runner, coding agents
 * (Claude Code, Antigravity CLI, DeepSeek Harness), and on-phone Android builds.
 */
object Workspace {
    private lateinit var app: Context
    lateinit var installer: RuntimeInstaller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var agentJob: Job? = null
    private var bridge: RuntimeBridge? = null
    private var bridgeKind: AgentKind? = null
    private var session: String? = null

    var project by mutableStateOf<WsProject?>(null)
    val output = mutableStateListOf<String>()        // terminal / build output (tail)
    val events = mutableStateListOf<WsEvent>()         // coding-agent activity
    var running by mutableStateOf(false)
    var agent by mutableStateOf(AgentKind.CLAUDE_CODE)

    fun init(ctx: Context) {
        if (::installer.isInitialized) return
        app = ctx.applicationContext; installer = RuntimeInstaller(app)
        RuntimeSetupController.restore(app)
        project = projects().firstOrNull()
    }

    // ---------- setup ----------

    fun isReady() = runCatching { installer.isInstalled() }.getOrDefault(false)
    fun startSetup(agent: AgentKind, stacks: Set<DevStack>) = app.startForegroundService(Intent(app, RuntimeSetupService::class.java)
        .setAction(RuntimeSetupService.ACTION_START).putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
        .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks.joinToString(",") { it.name }))
    fun stopSetup() = app.startService(Intent(app, RuntimeSetupService::class.java).setAction(RuntimeSetupService.ACTION_STOP))

    fun installAgent(kind: AgentKind, progress: (String) -> Unit) = launchJob {
        installer.ensureAgentInstalled(kind) { progress(it.message) }; progress("${kind.title} installed")
    }
    fun installStack(stack: DevStack, progress: (String) -> Unit) = launchJob {
        installer.ensureStackInstalled(stack) { progress(it.message) }; progress("${stack.label} installed")
    }

    // ---------- projects ----------

    private fun prefs() = app.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    fun projects(): List<WsProject> = JSONArray(prefs().getString("projects", "[]")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> WsProject(o.getString("id"), o.getString("name")) } } }
    fun createProject(name: String): WsProject {
        val slug = slugFor(name, projects().map { it.id }.toSet())
        val p = WsProject(slug, name.trim().ifEmpty { slug })
        prefs().edit().putString("projects", JSONArray((projects() + p).map { it.json() }).toString()).apply()
        dir(p).mkdirs(); project = p; return p
    }
    fun deleteProject(p: WsProject) {
        prefs().edit().putString("projects", JSONArray(projects().filter { it.id != p.id }.map { it.json() }).toString()).apply()
        dir(p).deleteRecursively(); if (project?.id == p.id) project = projects().firstOrNull()
    }
    fun dir(p: WsProject) = File(app.filesDir, "workspaces/${p.id}")

    // ---------- commands ----------

    /** Runs a shell command inside Ubuntu in the project folder, streaming output. Returns exit code and output tail. */
    fun runBlocking(command: String, p: WsProject, onChunk: (String) -> Unit = {}, workspace: File = dir(p)): Pair<Int, String> {
        check(isReady()) { "The Linux workspace isn't installed yet — open the Workspace tab and tap Set up." }
        val rt = installer.installedRuntime()
        val proc = installer.process(rt.proot, rt.rootfs, workspace, emptyMap(), listOf("/usr/bin/bash", "-lc", command), "/workspace/${p.slug}")
        val native = proc as NativeSpawnProcess
        var offset = 0L; val all = StringBuilder()
        while (proc.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) { Thread.sleep(75); continue }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val n = RandomAccessFile(native.outputFile, "r").use { it.seek(offset); it.read(bytes) }
            if (n > 0) { offset += n; val chunk = bytes.decodeToString(0, n); all.append(chunk); if (all.length > 200_000) all.delete(0, all.length - 100_000); onChunk(chunk) }
        }
        return proc.waitFor() to all.toString()
    }

    fun run(command: String) {
        val p = project ?: return
        output.add("$ $command")
        launchJob { val (code, _) = runBlocking(command, p, ::appendOutput); appendOutput("\n[exit $code]") }
    }

    private fun appendOutput(chunk: String) {
        chunk.split('\n').forEachIndexed { i, line -> if (i == 0 && output.isNotEmpty()) output[output.lastIndex] = output.last() + line else output.add(line) }
        while (output.size > 2000) output.removeAt(0)
    }

    // ---------- Android builds on the phone ----------

    fun buildAndInstall() {
        val p = project ?: return
        if (!installer.isStackInstalled(DevStack.ANDROID)) { output.add("Android toolchain not installed — install it in Workspace → Toolchains (~570 MB download)."); return }
        launchJob {
            val root = findAndroidProjectRoot(dir(p)) ?: error("No Android project (settings.gradle + AndroidManifest.xml) in ${p.name}")
            val cmd = androidGradleCommand(root, "assembleDebug")
            appendOutput("$ $cmd\n")
            val (code, out) = runBlocking(cmd, p, ::appendOutput, root)
            check(code == 0) { diagnoseAndroidBuildFailure(out, code) }
            val apk = findDebugApk(root) ?: error("Build finished but no debug APK was found")
            appendOutput("\nBuilt ${apk.name} (${apk.length() / 1_000_000} MB) — opening installer…")
            AndroidAppInstaller.install(app, apk)
        }
    }

    // ---------- coding agents ----------

    /** FlyAgent's saved keys mapped onto the providers the coding agents understand. */
    fun provider(): ProviderProfile? {
        val s = AgentHub.settings
        val map = listOf("claude" to ProviderKind.ANTHROPIC, "custom" to ProviderKind.LLM_ROUTER, "deepseek" to ProviderKind.DEEPSEEK,
            "kimi" to ProviderKind.KIMI, "nvidia" to ProviderKind.NVIDIA_NIM)
        val (id, kind) = map.firstOrNull { (id, _) -> s.enabled(id) && s.key(id).isNotBlank() } ?: return null
        return ProviderProfile(kind, hasSecret = true).also { providerKeyId = id }
    }
    private var providerKeyId = ""
    private fun secretFor(@Suppress("UNUSED_PARAMETER") p: ProviderProfile) = AgentHub.settings.key(providerKeyId).ifBlank { null }

    private fun bridgeFor(kind: AgentKind): RuntimeBridge {
        if (bridgeKind == kind) bridge?.let { return it }
        val prefs = prefs()
        val b = when (kind) {
            AgentKind.CLAUDE_CODE -> ClaudeRuntimeBridge(app, ::secretFor)
            AgentKind.DEEPSEEK_HARNESS -> DshRuntimeBridge(app, ::secretFor)
            AgentKind.ANTIGRAVITY -> AntigravityRuntimeBridge(app, { prefs.getString("agy.model", "")!! }, { prefs.getString("agy.effort", "")!! },
                { prefs.getString("agy.conv.$it", null) }, { pid, conv -> prefs.edit().putString("agy.conv.$pid", conv).apply() })
        }
        bridge = b; bridgeKind = kind
        scope.launch { b.events.collect(::onEvent) }
        return b
    }

    fun ask(prompt: String) {
        val p = project ?: return
        val kind = agent
        if (!installer.isAgentInstalled(kind)) { events.add(WsEvent("error", "${kind.title} isn't installed yet — install it under Agents.")); return }
        val prov = if (kind == AgentKind.ANTIGRAVITY) ProviderProfile(ProviderKind.CUSTOM) else provider()
            ?: run { events.add(WsEvent("error", "Turn on Claude, OpenRouter, DeepSeek, Kimi or NVIDIA with a key in Settings → AI providers.")); return }
        events.add(WsEvent("user", prompt)); events.add(WsEvent("assistant", ""))
        running = true
        val history = events.filter { it.kind == "user" || it.kind == "assistant" }.dropLast(2).map { ChatMessage(fromUser = it.kind == "user", text = it.text) }
        agentJob = scope.launch {
            runCatching {
                session = bridgeFor(kind).startSession(p.id, p.slug, ProjectKind.PROJECT, prompt, history, prov)
            }.onFailure { events.add(WsEvent("error", it.message ?: "failed to start")); running = false }
        }
    }

    private fun onEvent(e: RuntimeEvent) {
        fun reply() = events.lastOrNull { it.kind == "assistant" } ?: WsEvent("assistant", "").also(events::add)
        when (e) {
            is RuntimeEvent.AssistantDelta -> reply().text += e.text
            is RuntimeEvent.ToolStarted -> events.add(WsEvent("tool", "▶ ${e.toolName} ${e.detail.take(200)}"))
            is RuntimeEvent.ToolCompleted -> events.add(WsEvent("tool", "✓ ${e.toolName} ${e.summary.take(200)}"))
            is RuntimeEvent.ToolRequested -> events.add(WsEvent("approval", "${e.request.toolName}: ${e.request.explanation}\n${e.request.commandPreview.orEmpty()}", e.request))
            is RuntimeEvent.FilesChanged -> events.add(WsEvent("tool", "✎ changed ${e.paths.take(8).joinToString()}"))
            is RuntimeEvent.PreviewStarted -> events.add(WsEvent("tool", "🌐 preview running at ${e.url}"))
            is RuntimeEvent.SessionCompleted -> running = false
            is RuntimeEvent.SessionFailed -> { running = false; events.add(WsEvent("error", e.reason)) }
            else -> Unit
        }
    }

    fun decide(ev: WsEvent, ok: Boolean) {
        val req = ev.request ?: return
        if (ev.decided != null) return
        ev.decided = ok
        scope.launch { bridge?.respondToApproval(req, ok) }
    }

    // ---------- Antigravity Google sign-in (official agy CLI flow; tokens stay inside the CLI's Linux home) ----------
    val agyAuth: AntigravityAuthController by lazy {
        val p = prefs()
        AntigravityAuthController(app, p.getBoolean("agy.signedIn", false), p.getString("agy.email", "")!!) { signedIn, email ->
            p.edit().putBoolean("agy.signedIn", signedIn).putString("agy.email", email.orEmpty()).apply()
        }
    }
    fun agySignIn() { scope.launch { runCatching { agyAuth.beginLogin() } } }

    /** Used by FlyAgent's own Dots (tool ws_run): runs a command in a workspace project and returns the output tail. */
    fun toolRun(args: JSONObject): String {
        val name = args.optString("project").ifBlank { project?.id ?: "" }
        val p = projects().firstOrNull { it.id == name || it.name == name } ?: return "ERROR: no workspace project '$name'. Projects: ${projects().joinToString { it.id }}"
        val (code, out) = runBlocking(args.getString("command").take(4000), p)
        return "exit $code\n" + out.takeLast(6000)
    }

    fun stopAgent() { scope.launch { runCatching { bridge?.stopActiveSession() } }; agentJob?.cancel(); running = false }

    private fun launchJob(block: suspend () -> Unit) {
        running = true
        scope.launch { runCatching { block() }.onFailure { appendOutput("\nError: ${it.message}") }; running = false }
    }

}

/** Lowercase, hyphenated, unique — the runtime only accepts /workspace/<slug> names like this. */
fun slugFor(name: String, taken: Set<String>): String {
    val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "project" }
    var s = base; var n = 2
    while (s in taken) s = "$base-${n++}"
    return s
}
