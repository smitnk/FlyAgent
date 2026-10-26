@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.smitnk.flyagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smitnk.flyagent.MainActivity
import com.smitnk.flyagent.R
import com.smitnk.flyagent.Workspace
import com.smitnk.flyagent.WsEvent
import com.smitnk.flyagent.mh.model.AgentKind
import com.smitnk.flyagent.mh.model.DevStack
import com.smitnk.flyagent.mh.runtime.AntigravityAuthStatus
import com.smitnk.flyagent.mh.runtime.RuntimeSetupController
import com.smitnk.flyagent.mh.runtime.RuntimeSetupStatus

private enum class WsTab(val label: String) { Agent("Agent"), Terminal("Terminal"), Build("Build & tools") }

/** The phone's Linux workspace (Ubuntu in PRoot, adapted from Mobile Harness). */
@Composable
fun WorkspaceScreen(act: MainActivity) {
    val ws = Workspace
    val setup by RuntimeSetupController.snapshot.collectAsState()
    var ready by remember { mutableStateOf(ws.isReady()) }
    LaunchedEffect(setup.status) { if (setup.status == RuntimeSetupStatus.COMPLETE) ready = ws.isReady() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column {
            Text("Workspace", fontWeight = FontWeight.Bold)
            Text(if (ready) "Ubuntu 20.04 on this phone · ${ws.project?.name ?: "no project"}" else "Linux, coding agents and app builds on your phone",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        if (!ready || setup.status == RuntimeSetupStatus.RUNNING) SetupView(setup.status, setup.message, setup.progress, setup.logs, setup.errorMessage)
        else WorkspaceMain(act)
    }
}

@Composable
private fun SetupView(status: RuntimeSetupStatus, message: String, progress: Float, logs: List<String>, error: String?) {
    var agent by remember { mutableStateOf(AgentKind.CLAUDE_CODE) }
    var stacks by remember { mutableStateOf(setOf<DevStack>()) }
    LazyColumn {
        item {
            SectionCard("Set up your Linux workspace", "A private Ubuntu 20.04 system inside FlyAgent (no root needed), with Node.js and Git. " +
                "Everything downloads from official sources: Ubuntu, nodejs.org, Anthropic, Google. Use Wi-Fi; the core is about 150 MB.") {
                if (status == RuntimeSetupStatus.RUNNING) {
                    Text(message, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator({ progress }, Modifier.fillMaxWidth(), color = PocketOrange)
                    Surface(color = Color(0xFF07090D), shape = RoundedCornerShape(12.dp)) {
                        Text(logs.takeLast(12).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF9AA0A6), modifier = Modifier.fillMaxWidth().padding(10.dp))
                    }
                    OutlinedButton({ Workspace.stopSetup() }, shape = RoundedCornerShape(12.dp)) { Text("Stop") }
                } else {
                    error?.let { Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Text("Coding agent", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentKind.entries.forEach { a -> FilterChip(agent == a, { agent = a }, { Text("${a.title} · ${a.downloadNote}") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PocketOrange.copy(alpha = 0.18f), selectedLabelColor = PocketOrange)) }
                    }
                    Text("Optional toolchains (add more later)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DevStack.entries.filter { it != DevStack.WEB }.forEach { s -> FilterChip(s in stacks, { stacks = if (s in stacks) stacks - s else stacks + s }, { Text(s.label) }) }
                    }
                    if (DevStack.ANDROID in stacks) Text("Android toolchain is a ~570 MB download (JDK 17, Android SDK, Gradle) and needs several GB free.", fontSize = 12.sp, color = PocketOrange)
                    Button({ Workspace.startSetup(agent, stacks) }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text("Set up") }
                }
            }
        }
        item {
            Text("Runs with PRoot (GPL-2.0) — it isolates files but is not a hardened sandbox. Only run code you trust. Adapted from Mobile Harness (MIT).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun WorkspaceMain(act: MainActivity) {
    val ws = Workspace
    var tab by remember { mutableStateOf(WsTab.Agent) }
    var newProject by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(ws.projects()) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        projects.forEach { p -> FilterChip(ws.project?.id == p.id, { ws.project = p }, { Text(p.name) }, modifier = Modifier.padding(end = 6.dp)) }
        TextButton({ newProject = true }) { Text("+ Project", color = PocketOrange) }
    }
    if (ws.project == null) { Text("Create a project to start — or clone one of your GitHub repos into it.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    else {
        TabRow(tab.ordinal, containerColor = MaterialTheme.colorScheme.background, contentColor = PocketOrange) {
            WsTab.entries.forEach { t -> Tab(tab == t, { tab = t }, text = { Text(t.label) }) }
        }
        when (tab) {
            WsTab.Agent -> AgentPane()
            WsTab.Terminal -> TerminalPane()
            WsTab.Build -> BuildPane(act)
        }
    }
    if (newProject) {
        var name by remember { mutableStateOf("") }; var repo by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newProject = false }, title = { Text("New project") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name", name, { name = it })
                Field("Clone from GitHub (optional, https://github.com/…)", repo, { repo = it }, mono = true)
            } },
            confirmButton = { TextButton({
                if (repo.isNotBlank() && !Regex("^https://github\\.com/[\\w.-]+/[\\w.-]+(\\.git)?$").matches(repo.trim())) act.toast("Use a https://github.com/owner/repo link")
                else {
                    ws.createProject(name.ifBlank { repo.substringAfterLast('/').removeSuffix(".git") }); projects = ws.projects()
                    if (repo.isNotBlank()) ws.run("git clone --depth 1 '${repo.trim()}' . && ls")
                    newProject = false; tab = if (repo.isNotBlank()) WsTab.Terminal else tab
                }
            }) { Text("Create") } },
            dismissButton = { TextButton({ newProject = false }) { Text("Cancel") } })
    }
}

@Composable
private fun AgentPane() {
    val ws = Workspace
    var prompt by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ws.events.size, ws.events.lastOrNull()?.text?.length) { if (ws.events.isNotEmpty()) list.animateScrollToItem(ws.events.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            AgentKind.entries.forEach { a ->
                val installed = runCatching { ws.installer.isAgentInstalled(a) }.getOrDefault(false)
                FilterChip(ws.agent == a, { ws.agent = a }, { Text(a.title + if (installed) "" else " (not installed)") }, modifier = Modifier.padding(end = 6.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PocketOrange.copy(alpha = 0.18f), selectedLabelColor = PocketOrange))
            }
        }
        if (ws.agent == AgentKind.ANTIGRAVITY) AntigravityLogin()
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ws.events.isEmpty()) item {
                Text("Ask ${ws.agent.title} to build or fix something in “${ws.project?.name}”. It reads and edits files and runs commands inside Ubuntu; risky actions ask you first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            items(ws.events) { e -> WsEventRow(e) }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(4.dp)) {
                TextField(prompt, { prompt = it }, placeholder = { Text("Tell ${ws.agent.title} what to do…") }, maxLines = 6, modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                FilledIconButton({ if (ws.running) ws.stopAgent() else if (prompt.isNotBlank()) { ws.ask(prompt.trim()); prompt = "" } },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PocketOrange, contentColor = Color(0xFF241107))) {
                    Icon(painterResource(if (ws.running) R.drawable.ic_stop else R.drawable.ic_send), if (ws.running) "Stop" else "Send")
                }
            }
        }
    }
}

@Composable
private fun WsEventRow(e: WsEvent) = when (e.kind) {
    "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = PocketOrange.copy(alpha = 0.16f)) { Text(e.text, Modifier.padding(12.dp), fontSize = 15.sp) }
    }
    "assistant" -> if (e.text.isBlank() && Workspace.running) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PocketOrange); Spacer(Modifier.width(8.dp)); Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else MarkdownText(e.text)
    "tool" -> Text(e.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = PocketBlue)
    "approval" -> Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, PocketOrange), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp)) {
            Tag("APPROVAL NEEDED", PocketOrange)
            Text(e.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            when (e.decided) {
                null -> Row { TextButton({ Workspace.decide(e, false) }) { Text("Deny") }; Button({ Workspace.decide(e, true) }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)) { Text("Allow") } }
                true -> Text("✓ Allowed", color = PocketGreen)
                false -> Text("✗ Denied", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    else -> Text(e.text, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
}

@Composable
private fun AntigravityLogin() {
    val auth = Workspace.agyAuth
    val st by auth.state.collectAsState()
    val uri = LocalUriHandler.current
    if (st.status == AntigravityAuthStatus.SIGNED_IN) return
    var code by remember { mutableStateOf("") }
    LaunchedEffect(st.authorizationUrl) { st.authorizationUrl?.let { runCatching { uri.openUri(it) } } }
    SectionCard("Sign in to Antigravity", st.message ?: "Antigravity CLI uses your Google account. Sign-in happens in your browser through Google's official flow; FlyAgent never sees your password or tokens.") {
        when (st.status) {
            AntigravityAuthStatus.AWAITING_CODE -> {
                Field("Paste the code from Google", code, { code = it })
                Button({ auth.submitCode(code.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)) { Text("Finish sign-in") }
            }
            AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = PocketOrange)
            else -> Button({ Workspace.agySignIn() }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)) { Text("Sign in with Google") }
        }
    }
}

@Composable
private fun TerminalPane() {
    val ws = Workspace
    var cmd by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ws.output.size) { if (ws.output.isNotEmpty()) list.scrollToItem(ws.output.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = Color(0xFF07090D), modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(14.dp)) {
            LazyColumn(state = list, contentPadding = PaddingValues(10.dp)) {
                items(ws.output) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (line.startsWith("$ ")) PocketGreen else Color(0xFFC9D1D9)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            listOf("ls -la", "git status", "git pull", "npm install", "python3 --version").forEach { q ->
                AssistChip({ ws.run(q) }, { Text(q, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Text("$", color = PocketGreen, fontFamily = FontFamily.Monospace); Spacer(Modifier.width(8.dp))
            OutlinedTextField(cmd, { cmd = it }, singleLine = true, modifier = Modifier.weight(1f), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.width(8.dp))
            Button({ if (cmd.isNotBlank()) { ws.run(cmd.trim()); cmd = "" } }, enabled = !ws.running, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange)) { Text("Run") }
        }
    }
}

@Composable
private fun BuildPane(act: MainActivity) {
    val ws = Workspace
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionCard("Build Android app on this phone", "Runs Gradle assembleDebug inside Ubuntu and opens the installer — no PC, no cloud. Needs the Android toolchain.") {
                Button({ ws.buildAndInstall() }, enabled = !ws.running, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text("Build & install") }
                Text("Prefer cloud builds? Ask the Coder Dot in Chat — it uses GitHub Actions instead.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionCard("Coding agents", "Each installs separately over the core runtime.") {
                AgentKind.entries.forEach { a ->
                    val installed = runCatching { ws.installer.isAgentInstalled(a) }.getOrDefault(false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(a.title, fontWeight = FontWeight.SemiBold); Text(a.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (installed) Tag("installed", PocketGreen) else TextButton({ ws.installAgent(a) { msg -> act.runOnUiThread { act.status = msg } } }, enabled = !ws.running) { Text("Install ${a.downloadNote}") }
                    }
                }
            }
        }
        item {
            SectionCard("Toolchains", "Node.js and Git are always included.") {
                DevStack.entries.filter { it != DevStack.WEB }.forEach { s ->
                    val installed = runCatching { ws.installer.isStackInstalled(s) }.getOrDefault(false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(s.label, fontWeight = FontWeight.SemiBold); Text(s.installsSummary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (installed) Tag("installed", PocketGreen) else TextButton({ ws.installStack(s) { msg -> act.runOnUiThread { act.status = msg } } }, enabled = !ws.running) { Text("Install") }
                    }
                }
            }
        }
        item { StatusBanner(act) }
        item {
            Surface(color = Color(0xFF07090D), modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp)) {
                Text(ws.output.takeLast(40).joinToString("\n").ifBlank { "Build output appears here." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = Color(0xFFC9D1D9), modifier = Modifier.padding(10.dp))
            }
        }
    }
}
