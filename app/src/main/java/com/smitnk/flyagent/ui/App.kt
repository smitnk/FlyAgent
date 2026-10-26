@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.smitnk.flyagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smitnk.flyagent.*
import com.smitnk.flyagent.R

private enum class Tab(val label: String, val icon: Int) { Chat("Chat", R.drawable.ic_chat), Tasks("Tasks", R.drawable.ic_alarm), Workspace("Code", R.drawable.ic_terminal), Spaces("Spaces", R.drawable.ic_doc), Settings("Settings", 0) }

@Composable
fun FlyApp(act: MainActivity) {
    var tab by remember { mutableStateOf(Tab.Chat) }
    val keyboard = WindowInsets.isImeVisible
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!keyboard) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                        icon = { if (t.icon != 0) Icon(painterResource(t.icon), null, Modifier.size(22.dp)) else Icon(Icons.Default.Settings, null) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = PocketOrange, selectedTextColor = PocketOrange, indicatorColor = PocketOrange.copy(alpha = 0.14f)))
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            when (tab) {
                Tab.Chat -> ChatScreen(act)
                Tab.Tasks -> TasksScreen(act)
                Tab.Workspace -> WorkspaceScreen(act)
                Tab.Spaces -> SpacesScreen(act)
                Tab.Settings -> SettingsScreen(act)
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
fun FlyMark(size: Int = 28) = Box(
    Modifier.size(size.dp).background(PocketOrange.copy(alpha = 0.14f), RoundedCornerShape((size / 3).dp))
        .border(1.dp, PocketOrange.copy(alpha = 0.3f), RoundedCornerShape((size / 3).dp)), contentAlignment = Alignment.Center,
) { Icon(painterResource(R.drawable.ic_stat_fly), null, tint = PocketOrange, modifier = Modifier.size((size * 0.7).dp)) }

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) = Surface(
    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
fun Tag(text: String, color: Color = PocketBlue) = Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color,
    modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))

@Composable
fun StatusBanner(act: MainActivity) = act.status?.let { s ->
    Surface(color = PocketBlue.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp).clickable { act.status = null })
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, lines: Int = 1, mono: Boolean = false) = OutlinedTextField(
    value, onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines, shape = RoundedCornerShape(12.dp),
    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    textStyle = LocalTextStyle.current.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontSize = if (mono) 13.sp else 15.sp),
    modifier = Modifier.fillMaxWidth(),
)

private fun modeLabel(m: Mode) = when (m) { Mode.OFFLINE -> "Offline"; Mode.CLOUD -> "Cloud"; Mode.HYBRID -> "Hybrid" }

// ---------------------------------------------------------------- chat

@Composable
fun ChatScreen(act: MainActivity) {
    val hub = AgentHub; val s = hub.settings
    var showDots by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    var editPage by remember { mutableStateOf<Page?>(null) }
    var feedbackFor by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    var dotName by remember { mutableStateOf(s.dot.name) }
    val brain = s.llms().firstOrNull()?.name ?: if (LocalLlm.file(act).exists()) "Qwen3 on device" else "basic offline"
    val list = rememberLazyListState()
    val last = hub.messages.lastOrNull()
    LaunchedEffect(hub.messages.size, last?.text?.length) { if (hub.messages.isNotEmpty()) list.animateScrollToItem(hub.messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showDots = true }) {
                    FlyMark(32); Spacer(Modifier.width(10.dp))
                    Column {
                        Text("$dotName ▾", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${modeLabel(s.mode)} · $brain", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            actions = {
                IconButton({ showChats = true }) { Icon(Icons.Default.List, "Chats") }
                IconButton({ hub.newChat() }) { Icon(Icons.Default.Add, "New chat") }
            },
        )
        StatusBanner(act)
        if (hub.messages.isEmpty()) Welcome(act, Modifier.weight(1f))
        else LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(hub.messages) { m ->
                when (m.role) {
                    "user" -> UserMessage(m)
                    "approval" -> ApprovalCard(m)
                    else -> AssistantMessage(m, onSave = { editPage = Page(0, 0, it.lineSequence().first().take(60).trim('#', ' ', '*'), it) },
                        onFeedback = { ep, good -> feedbackFor = ep to good })
                }
            }
        }
        Composer(act)
    }

    if (showDots) DotSheet(onDismiss = { showDots = false; dotName = s.dot.name })
    if (showChats) ChatsSheet { showChats = false; dotName = s.dot.name }
    editPage?.let { p -> PageEditor(p) { editPage = null } }
    feedbackFor?.let { (ep, good) -> FeedbackDialog(act, ep, good) { feedbackFor = null } }
}

@Composable
private fun Welcome(act: MainActivity, modifier: Modifier) = Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
    FlyMark(56); Spacer(Modifier.height(18.dp))
    Text("How can I help you today?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text("Chatting with ${AgentHub.settings.dot.name}. Replies keep running if you close the app.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
    listOf(
        "📰" to "Today's top 10 news",
        "🧠" to "Run the sugar brain experiment and explain MN9",
        "✉️" to "Write an email asking a client for feedback",
        "🐞" to "Find bugs in my GitHub repo",
    ).forEach { (e, t) ->
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { act.input = t; act.send() }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(e); Spacer(Modifier.width(12.dp)); Text(t, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun UserMessage(m: UiMsg) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = PocketOrange.copy(alpha = 0.16f), modifier = Modifier.padding(start = 48.dp)) {
        MarkdownText(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
private fun AssistantMessage(m: UiMsg, onSave: (String) -> Unit, onFeedback: (Long, Boolean) -> Unit) {
    val clip = LocalClipboardManager.current
    var showDetails by remember { mutableStateOf(false) }
    Row {
        FlyMark(26); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (m.text.isBlank() && m.working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PocketOrange)
                    Spacer(Modifier.width(8.dp)); Text(m.details.lines().lastOrNull()?.take(60)?.ifBlank { null } ?: "Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else MarkdownText(m.text)
            if (!m.working) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                @Composable fun act(label: String, f: () -> Unit) = Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = f).padding(horizontal = 6.dp, vertical = 4.dp))
                act("Copy") { clip.setText(AnnotatedString(m.text)) }
                if (m.episodeId > 0) { act("👍") { onFeedback(m.episodeId, true) }; act("👎") { onFeedback(m.episodeId, false) } }
                act("Save page") { onSave(m.text) }
                if (m.details.isNotBlank()) act(if (showDetails) "Hide details" else "Details") { showDetails = !showDetails }
                Spacer(Modifier.weight(1f)); Tag(if (m.cloud) "cloud" else "on device", if (m.cloud) PocketBlue else PocketGreen)
            }
            AnimatedVisibility(showDetails || (m.working && m.text.isNotBlank() && m.details.isNotBlank())) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Text(m.details, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(m: UiMsg) = Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, PocketOrange), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Tag("APPROVAL NEEDED", PocketOrange); Spacer(Modifier.height(8.dp))
        Text(m.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(m.detail, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 14, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
        when (m.decision) {
            null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ AgentHub.decide(m, false) }) { Text("Decline") }; Spacer(Modifier.width(8.dp))
                Button({ AgentHub.decide(m, true) }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text("Approve") }
            }
            true -> Text("✓ Approved", color = PocketGreen, fontWeight = FontWeight.SemiBold)
            false -> Text("✗ Declined", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(act: MainActivity) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column {
            val chip = act.image?.let { "🖼 Image" } ?: act.file?.let { "📎 ${it.first}" }
            var deep by remember { mutableStateOf(AgentHub.settings.deepThink) }
            Row(Modifier.padding(start = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // Deep Think: the model re-thinks its answer in loops with the question re-injected each time
                Text(if (deep > 0) "🔁 Deep Think ×$deep" else "🔁 Deep Think", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (deep > 0) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (deep > 0) PocketOrange.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (deep > 0) PocketOrange.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { deep = if (deep > 0) 0 else 2; AgentHub.settings.deepThink = deep }.padding(horizontal = 8.dp, vertical = 3.dp))
                if (chip != null) {
                    Spacer(Modifier.width(8.dp)); Tag(chip, PocketOrange); Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { act.image = null; act.file = null })
                }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(4.dp)) {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.Add, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Photo") }, { menu = false; act.attachPhoto() })
                        DropdownMenuItem({ Text("File or ZIP") }, { menu = false; act.attachFile() })
                    }
                }
                TextField(act.input, { act.input = it }, placeholder = { Text("Message FlyAgent…") }, maxLines = 6, modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                if (act.input.isBlank() && !AgentHub.busy) IconButton({ act.voice() }) { Icon(painterResource(R.drawable.ic_mic), "Voice", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledIconButton({ if (AgentHub.busy) { AgentHub.stop(); act.stopSpeaking() } else act.send() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PocketOrange, contentColor = Color(0xFF241107))) {
                    Icon(painterResource(if (AgentHub.busy) R.drawable.ic_stop else R.drawable.ic_send), if (AgentHub.busy) "Stop" else "Send")
                }
            }
        }
    }
}

@Composable
private fun FeedbackDialog(act: MainActivity, episode: Long, good: Boolean, onDone: () -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDone, title = { Text(if (good) "Learn this as a skill?" else "Correct FlyAgent") },
        text = { Field(if (good) "Optional note" else "What should it have said or done?", note, { note = it }, lines = 3) },
        confirmButton = { TextButton({
            Thread { val out = StringBuilder(); AgentHub.agent.feedback(episode, good, note) { out.appendLine(it) }; act.runOnUiThread { act.toast(out.toString().trim().ifEmpty { "Saved" }) } }.start()
            onDone() }) { Text("Save") } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
}

@Composable
private fun ChatsSheet(onDismiss: () -> Unit) {
    var chats by remember { mutableStateOf(AgentHub.memory.chats()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Text("Chats", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            if (chats.isEmpty()) item { Text("No chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(chats) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { AgentHub.openChat(c.id); onDismiss() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = if (c.id == AgentHub.chatId) PocketOrange else MaterialTheme.colorScheme.onSurface)
                    IconButton({ AgentHub.memory.deleteChat(c.id); if (c.id == AgentHub.chatId) AgentHub.newChat(); chats = AgentHub.memory.chats() }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun DotSheet(onDismiss: () -> Unit) {
    val s = AgentHub.settings
    var editing by remember { mutableStateOf<Dot?>(null) }
    var dots by remember { mutableStateOf(s.dots()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dots", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ editing = Dot("", "", "", Dot.GROUPS.keys) }) { Text("+ New Dot") }
        }
        Text("Specialists with their own role and tools.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dots) { d ->
                val selected = d.id == s.dotId
                Surface(shape = RoundedCornerShape(14.dp), color = if (selected) PocketOrange.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().clickable { s.dotId = d.id; AgentHub.newChat(); onDismiss() }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (d !in Dot.PRESETS) Text("Edit", color = PocketOrange, fontSize = 13.sp, modifier = Modifier.clickable { editing = d })
                        }
                        if (d.instructions.isNotBlank()) Text(d.instructions, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) { d.toolGroups.forEach { Tag(it) } }
                    }
                }
            }
        }
    }
    editing?.let { d -> DotEditor(d, onClose = { editing = null; dots = s.dots() }) }
}

@Composable
private fun DotEditor(d: Dot, onClose: () -> Unit) {
    val s = AgentHub.settings
    var name by remember { mutableStateOf(d.name) }; var instr by remember { mutableStateOf(d.instructions) }
    var groups by remember { mutableStateOf(d.toolGroups) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (d.id.isEmpty()) "New Dot" else "Edit ${d.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name", name, { name = it }); Field("Role and instructions", instr, { instr = it }, lines = 4)
                Text("Allowed tools", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot.GROUPS.keys.forEach { g -> FilterChip(g in groups, { groups = if (g in groups) groups - g else groups + g }, { Text(g) }) }
                }
            }
        },
        confirmButton = { TextButton({
            if (name.isNotBlank()) {
                val nd = Dot(d.id.ifEmpty { "dot${System.currentTimeMillis()}" }, name.trim(), instr.trim(), groups, d.maxSteps)
                s.saveCustomDots(s.dots().filter { it !in Dot.PRESETS && it.id != nd.id } + nd); s.dotId = nd.id
            }
            onClose() }) { Text("Save") } },
        dismissButton = { Row {
            if (d.id.isNotEmpty()) TextButton({ s.saveCustomDots(s.dots().filter { it !in Dot.PRESETS && it.id != d.id }); s.dotId = Dot.DEFAULT.id; onClose() }) { Text("Delete") }
            TextButton(onClose) { Text("Cancel") }
        } })
}

// ---------------------------------------------------------------- tasks

@Composable
fun TasksScreen(act: MainActivity) {
    var list by remember { mutableStateOf(Schedules.list(act)) }
    var adding by remember { mutableStateOf(false) }
    fun save(l: List<Schedule>) { Schedules.save(act, l); list = l }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Tasks", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = { TextButton({ adding = true }) { Text("+ New task", color = PocketOrange) } })
        LazyColumn(Modifier.weight(1f)) {
            item {
                SectionCard("Background agent", "Chats keep running in a background service when you leave the app; you get a notification when they finish or need approval.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (AgentHub.busy) PocketOrange else PocketGreen, CircleShape)); Spacer(Modifier.width(8.dp))
                        Text(if (AgentHub.busy) "Working on a task…" else "Idle — ready", fontSize = 14.sp)
                    }
                }
            }
            item { Text("SCHEDULED", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 12.dp)) }
            if (list.isEmpty()) item {
                Text("No scheduled tasks. Example: every day at 8:00 — “Give me today's top news about animation and AI”.", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            items(list) { t ->
                SectionCard("%02d:%02d daily".format(t.hour, t.minute), AgentHub.settings.dots().firstOrNull { it.id == t.dotId }?.name ?: "FlyAgent") {
                    Text(t.prompt, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(t.enabled, { on -> save(list.map { if (it.id == t.id) it.copy(enabled = on) else it }) },
                            colors = SwitchDefaults.colors(checkedTrackColor = PocketOrange))
                        Spacer(Modifier.weight(1f))
                        TextButton({ Schedules.runNow(act, t); act.toast("Running now — you'll get a notification") }) { Text("Run now") }
                        IconButton({ save(list.filter { it.id != t.id }) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            item { Text("Scheduled tasks can't send, save or post anything on their own — actions that need approval are skipped.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        }
    }
    if (adding) {
        var prompt by remember { mutableStateOf("") }; var time by remember { mutableStateOf("08:00") }
        var dot by remember { mutableStateOf(AgentHub.settings.dotId) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("New daily task") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("What should the agent do?", prompt, { prompt = it }, lines = 3)
                Field("Time (HH:MM, 24h)", time, { time = it })
                Text("Dot", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AgentHub.settings.dots().forEach { d -> FilterChip(dot == d.id, { dot = d.id }, { Text(d.name) }) } }
            } },
            confirmButton = { TextButton({
                val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim())
                val h = m?.groupValues?.get(1)?.toInt(); val min = m?.groupValues?.get(2)?.toInt()
                if (prompt.isBlank() || h == null || min == null || h > 23 || min > 59) act.toast("Enter a task and a time like 08:00")
                else { save(list + Schedule("s${System.currentTimeMillis()}", prompt.trim(), h, min, dot)); adding = false }
            }) { Text("Save") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- spaces

@Composable
fun SpacesScreen(act: MainActivity) {
    var pages by remember { mutableStateOf(AgentHub.memory.pages()) }
    var open by remember { mutableStateOf<Page?>(null) }
    var edit by remember { mutableStateOf<Page?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Spaces", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = { TextButton({ edit = Page(0, 0, "", "") }) { Text("+ New page", color = PocketOrange) } })
        if (pages.isEmpty()) Text("No pages yet. Tap “Save page” under an answer, or ask a Dot to draft one.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        LazyColumn {
            items(pages) { p ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable { open = p }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.title, fontWeight = FontWeight.SemiBold)
                        Text(p.body.replace(Regex("[#*`>]"), "").take(140), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    open?.let { p ->
        AlertDialog(onDismissRequest = { open = null }, title = { Text(p.title) },
            text = { LazyColumn(Modifier.heightIn(max = 480.dp)) { item { MarkdownText(p.body) } } },
            confirmButton = { TextButton({ edit = p; open = null }) { Text("Edit") } },
            dismissButton = { Row {
                TextButton({ act.file = "page ${p.title}" to "Page \"${p.title}\":\n${p.body}"; act.toast("Page attached — ask about it in Chat"); open = null }) { Text("Ask about it") }
                TextButton({ AgentHub.memory.deletePage(p.id); pages = AgentHub.memory.pages(); open = null }) { Text("Delete") }
            } })
    }
    edit?.let { p -> PageEditor(p) { edit = null; pages = AgentHub.memory.pages() } }
}

@Composable
fun PageEditor(p: Page, onClose: () -> Unit) {
    var title by remember { mutableStateOf(p.title) }; var body by remember { mutableStateOf(p.body) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (p.id > 0) "Edit page" else "Save page") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Title", title, { title = it }); Field("Content (Markdown)", body, { body = it }, lines = 8) } },
        confirmButton = { TextButton({ AgentHub.memory.savePage(title.ifBlank { "Untitled" }, body, p.id); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(act: MainActivity) {
    val s = AgentHub.settings
    var mode by remember { mutableStateOf(s.mode) }
    var speak by remember { mutableStateOf(s.speakReplies) }
    var showDots by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        StatusBanner(act)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionCard("Mode", "Where the thinking happens.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mode.entries.forEach { m -> FilterChip(mode == m, { mode = m; s.mode = m }, { Text(modeLabel(m)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PocketOrange.copy(alpha = 0.18f), selectedLabelColor = PocketOrange)) }
                    }
                    Text(when (mode) { Mode.OFFLINE -> "Only the on-device model and tools. Nothing leaves the phone except web searches you ask for."
                        Mode.CLOUD -> "Only your API models; fails if none is reachable."; Mode.HYBRID -> "Cloud models when available, on-device model otherwise." },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard("AI providers", "Turn on any you have a key for — they're tried top to bottom. Keys are encrypted with the Android Keystore.") {
                    s.providers.forEach { p -> ProviderRow(p) }
                }
            }
            item { LocalModelCard(act) }
            item {
                SectionCard("Dots", "Specialists: ${s.dots().joinToString { it.name }}") {
                    OutlinedButton({ showDots = true }, shape = RoundedCornerShape(12.dp)) { Text("Manage Dots") }
                }
            }
            item { ConnectorsCard(act) }
            item { GitHubCard(act) }
            item {
                SectionCard("Voice", "Tap the mic in the chat box to talk. Uses your phone's speech recognition.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Read replies aloud", modifier = Modifier.weight(1f))
                        Switch(speak, { speak = it; s.speakReplies = it }, colors = SwitchDefaults.colors(checkedTrackColor = PocketOrange))
                    }
                }
            }
            item {
                var rounds by remember { mutableStateOf(s.deepThink) }
                SectionCard("Deep Think (looped reasoning)", "The model re-thinks its answer in loops, re-reading your original question at the start of every loop so it doesn't drift — " +
                    "inspired by looped language models with input injection. Stops early when the answer stops changing. Costs extra model calls and time.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 2, 3, 4).forEach { n -> FilterChip(rounds == n, { rounds = n; s.deepThink = n }, { Text(if (n == 0) "Off" else "×$n") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PocketOrange.copy(alpha = 0.18f), selectedLabelColor = PocketOrange)) }
                    }
                    Text("This is a prompt-level technique for any model. A trained looped model (e.g. ByteDance Ouro) can't run on the phone yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { BrainCard(act) }
            item {
                val m = AgentHub.memory
                SectionCard("Memory & skills", "${m.count()} tasks remembered on this phone.") {
                    val skills = m.skills()
                    if (skills.isEmpty()) Text("No skills yet — tap 👍 under a good answer.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    skills.forEach { Text("• ${it.name} v${it.version} [${it.status}]", fontSize = 13.sp) }
                }
            }
            item {
                SectionCard("About", "FlyAgent ${act.packageManager.getPackageInfo(act.packageName, 0).versionName}") {
                    Text("UI style and Markdown renderer adapted from Mobile Harness (MIT). Fly connectome: FlyWire v783 (CC BY 4.0). " +
                        "Model runtime: llama.cpp (MIT); Qwen3 (Apache-2.0). Ideas from OpenDots (MIT). See NOTICE.md.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showDots) DotSheet { showDots = false }
}

@Composable
private fun ProviderRow(p: Provider) {
    val s = AgentHub.settings
    var open by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(s.enabled(p.id)) }
    var key by remember { mutableStateOf(s.key(p.id)) }; var url by remember { mutableStateOf(s.url(p)) }; var model by remember { mutableStateOf(s.model(p)) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), border = BorderStroke(1.dp, if (on) PocketOrange.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (key.isNotBlank()) "Key saved · $model" else model, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Switch(on, { on = it; s.save(p.id, it, key.trim(), url.trim(), model.trim()) }, colors = SwitchDefaults.colors(checkedTrackColor = PocketOrange))
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Field("API key", key, { key = it }, secret = true); Field("Base URL", url, { url = it }, mono = true); Field("Model", model, { model = it }, mono = true)
                    Button({ s.save(p.id, on, key.trim(), url.trim(), model.trim()); open = false }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text("Save") }
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun LocalModelCard(act: MainActivity) {
    val f = LocalLlm.file(act)
    var picking by remember { mutableStateOf(false) }
    SectionCard("On-device AI · Qwen3-0.6B", if (f.exists()) "Installed (${f.length() / 1_048_576} MB). Runs offline with llama.cpp." else "Not installed. One-time download from Hugging Face, checked with SHA-256.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ picking = true }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text(if (f.exists()) "Re-download" else "Download") }
            OutlinedButton({ act.pickModelFile() }, shape = RoundedCornerShape(12.dp)) { Text("Load .gguf") }
            if (f.exists()) TextButton({ LocalLlm.reset(); f.delete(); act.status = "Model removed" }) { Text("Remove") }
        }
    }
    if (picking) AlertDialog(onDismissRequest = { picking = false }, title = { Text("Choose model") },
        text = { Column { LocalLlm.MODELS.forEach { m -> Text("${m.label} — ${m.sizeMb} MB", Modifier.fillMaxWidth().clickable {
            picking = false; act.job("Starting download…") { say -> Downloads.installModel(act, m, say); "Qwen3 installed. Offline chat is ready." } }.padding(vertical = 12.dp)) } } },
        confirmButton = { TextButton({ picking = false }) { Text("Cancel") } })
}

@Composable
private fun BrainCard(act: MainActivity) {
    val full = ConnectomeBrain.fullFile(act).exists()
    SectionCard("Fly brain · connectome", "Real FlyWire v783 wiring; activity is a leaky integrate-and-fire simulation. " +
        if (full) "Full brain installed (139k neurons)." else "Using the bundled sugar→feeding subset.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ act.input = "brain sugar 100"; act.send(); act.toast("Running — see Chat") }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text("Run test") }
            OutlinedButton({
                if (full) { ConnectomeBrain.reset(); ConnectomeBrain.fullFile(act).delete(); act.status = "Using subset" }
                else act.job("Starting connectome download…") { say -> Downloads.installFullBrain(act, say); "Full fly brain installed." }
            }, shape = RoundedCornerShape(12.dp)) { Text(if (full) "Use subset" else "Get full brain") }
        }
    }
}

@Composable
private fun ConnectorsCard(act: MainActivity) {
    val s = AgentHub.settings
    var list by remember { mutableStateOf(s.connectors()) }
    var adding by remember { mutableStateOf(false) }
    SectionCard("Connectors (MCP)", "Give Dots skills from remote MCP servers. Read-only tools run on their own; anything else asks you first.") {
        list.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.SemiBold); Text(c.url, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                TextButton({ act.job("Connecting to ${c.name}…") { _ -> AgentHub.connectors.test(c).let { t -> "${c.name}: ${t.size} tools — " + t.joinToString { it.name } } } }) { Text("Test") }
                Switch(c.enabled, { on -> list = list.map { if (it.name == c.name) it.copy(enabled = on) else it }; s.saveConnectors(list); AgentHub.connectors.reset() },
                    colors = SwitchDefaults.colors(checkedTrackColor = PocketOrange))
                IconButton({ list = list.filter { it.name != c.name }; s.saveConnectors(list); AgentHub.connectors.reset() }) { Icon(Icons.Default.Delete, "Remove") }
            }
        }
        OutlinedButton({ adding = true }, shape = RoundedCornerShape(12.dp)) { Text("+ Add connector") }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add connector") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Name (e.g. github)", name, { name = it }); Field("Server URL (https://…/mcp)", url, { url = it }, mono = true); Field("Bearer token (optional)", token, { token = it }, secret = true) } },
            confirmButton = { TextButton({
                val c = Connector(name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_"), url.trim(), token.trim())
                if (c.name.isBlank() || !c.url.startsWith("https://")) act.toast("Name and an https:// URL are required")
                else { list = list.filter { it.name != c.name } + c; s.saveConnectors(list); AgentHub.connectors.reset(); adding = false }
            }) { Text("Save") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}

@Composable
private fun GitHubCard(act: MainActivity) {
    val s = AgentHub.settings
    var token by remember { mutableStateOf(s.githubToken) }
    SectionCard("GitHub (Coder Dot)", "A fine-grained token lets the Coder Dot read your repos, find bugs, and — only after you approve — open issues/PRs, run cloud builds with GitHub Actions and install the built APK. " +
        "Token permissions: Contents, Issues, Pull requests (read & write), Actions (read & write).") {
        Field("Personal access token", token, { token = it }, secret = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ s.githubToken = token.trim(); act.status = "GitHub token saved (encrypted)" }, colors = ButtonDefaults.buttonColors(containerColor = PocketOrange), shape = RoundedCornerShape(12.dp)) { Text("Save") }
            OutlinedButton({ act.job("Checking GitHub…") { _ -> GitHub({ s.githubToken }).run("gh_repos", org.json.JSONObject()).lines().let { "Connected — ${it.size} repos, e.g. ${it.first()}" } } },
                shape = RoundedCornerShape(12.dp)) { Text("Test") }
        }
    }
}
