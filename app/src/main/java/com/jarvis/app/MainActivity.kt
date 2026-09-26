package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.core.app.*
import androidx.core.content.*
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.navigation.compose.*
import java.text.SimpleDateFormat
import java.util.*



private val Bg = Color(0xFF0A0F1A)
private val Surface = Color(0xFF1A2332)
private val Cyan = Color(0xFF00E5FF)
private val TextP = Color.White
private val TextS = Color(0xFF94A3B8)
private val TextT = Color(0xFF64748B)

data class Msg(val id: String = UUID.randomUUID().toString(), val role: String, val text: String)
data class TaskItem(val id: String = UUID.randomUUID().toString(), val title: String, val whenText: String, val done: Boolean = false)
data class Mem(val id: String = UUID.randomUUID().toString(), val text: String)
data class AppItem(val label: String, val pkg: String, val on: Boolean)

class JarvisVm(app: Application) : AndroidViewModel(app) {
    private val model = LocalModel(app)
    private val prefs = app.getSharedPreferences("jarvis", 0)
    private var tts: TextToSpeech? = null
    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs = _msgs.asStateFlow()
    private val _tasks = MutableStateFlow(
        listOf(
            TaskItem(title = "Call mom", whenText = "6:00 PM — Today"),
            TaskItem(title = "Finish project report", whenText = "8:00 PM — Today"),
            TaskItem(title = "Workout", whenText = "6:30 AM — Tomorrow")
        )
    )
    val tasks = _tasks.asStateFlow()
    private val _mems = MutableStateFlow(
        listOf(Mem(text = "Prefers dark mode"), Mem(text = "Likes tech & AI"), Mem(text = "Lives in Delhi"))
    )
    val mems = _mems.asStateFlow()
    val modelReady get() = model.installed

    init {
        tts = TextToSpeech(app) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
    }

    override fun onCleared() {
        tts?.shutdown(); model.release(); super.onCleared()
    }

    fun importModel(uri: Uri, done: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) { model.importUri(uri) }
            done(msg)
        }
    }

    fun send(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        viewModelScope.launch {
            _msgs.value = _msgs.value + Msg(role = "user", text = t)
            val reply = withContext(Dispatchers.Default) { answer(t) }
            _msgs.value = _msgs.value + Msg(role = "assistant", text = reply)
            tts?.speak(reply.take(220), TextToSpeech.QUEUE_FLUSH, null, "j")
        }
    }

    fun toggle(id: String) {
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(done = !it.done) else it }
    }

    fun addTask(title: String) {
        if (title.isBlank()) return
        _tasks.value = _tasks.value + TaskItem(title = title, whenText = "Today")
    }

    private fun apps(): List<AppItem> {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = if (Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        val on = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        return found.map {
            AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.packageName in on)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun listedApps() = apps()

    fun setApp(pkg: String, on: Boolean) {
        val next = (prefs.getStringSet("apps", emptySet()) ?: emptySet()).toMutableSet()
        if (on) next.add(pkg) else next.remove(pkg)
        prefs.edit().putStringSet("apps", next).apply()
        if (on) launch(pkg)
    }

    fun launch(pkg: String): Boolean {
        val i = getApplication<Application>().packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { getApplication<Application>().startActivity(i); true }.getOrDefault(false)
    }

    private fun answer(input: String): String {
        val low = input.lowercase()
        when {
            "hello" in low || low == "hi" -> return "Hello. I'm JARVIS. Made by Veer. Import your GGUF in Settings to chat with the local model."
            "time" in low || "date" in low ->
                return "It's " + SimpleDateFormat("h:mm a, EEEE d MMM", Locale.getDefault()).format(Date()) + "."
            "remind" in low || low.startsWith("add ") || "task" in low -> {
                val title = input.replace(Regex("(?i)remind me to|remind me|add a task|add task|add |todo"), "").trim()
                addTask(title.ifBlank { "New task" })
                return "Added: ${title.ifBlank { "New task" }}"
            }
            "remember" in low -> {
                val v = input.replace(Regex("(?i)remember that|remember"), "").trim()
                _mems.value = listOf(Mem(text = v)) + _mems.value
                return "Saved to memory: $v"
            }
            low.startsWith("open ") || low.startsWith("launch ") -> {
                val name = input.replace(Regex("(?i)open|launch|start|the|app|please"), " ").trim()
                val hit = apps().firstOrNull { it.label.lowercase().contains(name) }
                    ?: return "No app named $name. Connect it in Connect."
                setApp(hit.pkg, true)
                return if (launch(hit.pkg)) "Opening ${hit.label}." else "Couldn't open ${hit.label}."
            }
            "model" in low || "gguf" in low ->
                return if (model.installed) "A GGUF is installed." else "Settings → AI Model → Import .gguf"
        }
        return if (model.installed) model.generate(input)
        else "Skills are working. For real AI answers, open Settings → AI Model and import your .gguf, then ask again."
    }
}

class MainActivity : ComponentActivity() {
    private val vm: JarvisVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        setContent { JarvisRoot(vm) }
    }
}

@Composable
fun JarvisRoot(vm: JarvisVm) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route ?: "chat"
    var voice by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            NavHost(nav, startDestination = "chat") {
                composable("chat") { ChatPage(vm) { nav.navigate("model") } }
                composable("model") { ModelPage(vm) { nav.popBackStack() } }
            }
        }
    }
}

            NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") { HomePage(vm, { nav.navigate(it) }, { voice = true }) }
                composable("chat") { ChatPage(vm, { nav.popBackStack() }, { voice = true }) }
                composable("memory") { MemoryPage(vm) { nav.popBackStack() } }
                composable("skills") { SkillsPage { nav.popBackStack() } }
                composable("tasks") { TasksPage(vm) { nav.popBackStack() } }
                composable("files") { FilesPage { nav.popBackStack() } }
                composable("settings") { SettingsPage(vm) { nav.navigate(it) } }
                composable("connect") { ConnectPage(vm) { nav.popBackStack() } }
                composable("model") { ModelPage(vm) { nav.popBackStack() } }
                composable("more") { MorePage { nav.navigate(it) } }
            }
            if (route in setOf("home", "memory", "skills", "more")) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF0D1228)).padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("home" to "Home", "memory" to "Memory", "skills" to "Skills", "more" to "More").forEach { (r, l) ->
                        Text(l, color = if (route == r) Cyan else TextS, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { nav.navigate(r) { launchSingleTop = true } })
                    }
                }
            }
        }
        if (voice) VoicePage(vm, { voice = false })
    }
}

@Composable fun HomePage(vm: JarvisVm, go: (String) -> Unit, talk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("JARVIS", color = TextP, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Your Personal AI Assistant", color = TextS, fontSize = 12.sp)
            }
            Text(if (vm.modelReady) "Model •" else "Local •", color = Cyan, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(160.dp).clip(CircleShape).border(4.dp, Cyan, CircleShape), contentAlignment = Alignment.Center) {
                Text("JARVIS", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        Text("Good to see you", color = TextP, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text("How can I help you today?", color = TextS)
        Text("Made by Veer", color = TextT, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardBtn("Memory", "Your data", Modifier.weight(1f)) { go("memory") }
            CardBtn("Skills", "On-device", Modifier.weight(1f)) { go("skills") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Mini("Notes") { go("chat") }; Mini("Tasks") { go("tasks") }
            Mini("Files") { go("files") }; Mini("Apps") { go("connect") }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Cyan).clickable { talk() }, contentAlignment = Alignment.Center) {
                Text("mic", color = Bg, fontWeight = FontWeight.Bold)
            }
        }
        Text("Tap to talk", color = TextS, modifier = Modifier.fillMaxWidth().padding(8.dp))
    }
}

@Composable fun CardBtn(t: String, s: String, m: Modifier, on: () -> Unit) {
    Column(m.clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Cyan.copy(0.25f), RoundedCornerShape(16.dp)).clickable(onClick = on).padding(14.dp)) {
        Text(t, color = TextP, fontWeight = FontWeight.SemiBold); Text(s, color = TextS, fontSize = 12.sp)
    }
}
@Composable fun Mini(t: String, on: () -> Unit) {
    Text(t, color = TextS, modifier = Modifier.clickable(onClick = on).padding(8.dp))
}

@Composable fun ChatPage(vm: JarvisVm, back: () -> Unit, talk: () -> Unit) {
    val msgs by vm.msgs.collectAsState()
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("‹  JARVIS", color = TextP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() })
        LazyColumn(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty()) item { Text("Ask anything. Import a GGUF in Settings for real model answers.", color = TextS) }
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.role == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text, color = TextP, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (m.role == "user") Cyan.copy(0.2f) else Surface).padding(12.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Surface).padding(12.dp)) {
                if (text.isEmpty()) Text("Type a message…", color = TextT)
                BasicTextField(text, { text = it }, textStyle = TextStyle(color = TextP), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(Cyan).clickable { vm.send(text); text = "" }, contentAlignment = Alignment.Center) {
                Text(">", color = Bg, fontWeight = FontWeight.Bold)
            }
        }
        Text("Voice", color = Cyan, modifier = Modifier.clickable { talk() }.padding(top = 8.dp))
    }
}

@Composable fun MemoryPage(vm: JarvisVm, back: () -> Unit) {
    val mems by vm.mems.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("‹  Memory", color = TextP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(14.dp)) {
            Column { Text("User Profile", color = Cyan, fontWeight = FontWeight.SemiBold); Text("Name: You", color = TextP); Text("Timezone: Asia/Kolkata", color = TextS) }
        }
        Spacer(Modifier.height(10.dp))
        mems.forEach { Text("• ${it.text}", color = TextP, modifier = Modifier.padding(vertical = 6.dp)) }
    }
}

@Composable fun SkillsPage(back: () -> Unit) {
    val items = listOf("Calculator","Notes","Tasks","Reminders","File Search","Document Reader","App Launcher","Weather")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("‹  Skills", color = TextP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() })
        items.forEach { Text(it, color = TextP, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable fun TasksPage(vm: JarvisVm, back: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("‹  Tasks", color = TextP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() })
        tasks.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface).clickable { vm.toggle(t.id) }.padding(12.dp)) {
                Text(if (t.done) "✓" else "○", color = Cyan)
                Spacer(Modifier.width(10.dp))
                Column { Text(t.title, color = TextP); Text(t.whenText, color = TextS, fontSize = 12.sp) }
            }
        }
    }
}

@Composable fun FilesPage(back: () -> Unit) {
    val files = listOf("Project Plan.pdf" to "2.4 MB", "Notes.txt" to "120 KB", "Study Material.pdf" to "4.8 MB")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("‹  Files & Knowledge", color = TextP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() })
        files.forEach { (n, s) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface).padding(12.dp)) {
                Text(n, color = TextP); Text(s, color = TextS, fontSize = 12.sp)
            }
        }
    }
}

@Composable fun SettingsPage(vm: JarvisVm, go: (String) -> Unit) {
    val rows = listOf(
        "AI Model" to "Import GGUF",
        "Voice" to "Android TTS",
        "Speech Recognition" to "On-device",
        "Memory" to "Manage data",
        "Connect" to "Link apps"
    )
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", color = TextP, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text("Made by Veer", color = TextT, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        rows.forEach { (t, s) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Surface).clickable {
                when (t) {
                    "AI Model" -> go("model")
                    "Memory" -> go("memory")
                    "Connect" -> go("connect")
                }
            }.padding(14.dp)) {
                Text(t, color = TextP, fontWeight = FontWeight.SemiBold); Text(s, color = TextS, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ModelPage(
    vm: JarvisVm,
    back: () -> Unit,
    context: Context = LocalContext.current
) {
    var status by remember {
        mutableStateOf(
            if (vm.modelReady) "A GGUF is already installed."
            else "No model yet. Import the .gguf from Downloads."
        )
    }

    val pick = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            vm.importModel(uri) { status = it }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Model Configuration", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(status)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { pick.launch(arrayOf("*/*")) }) {
            Text("Select .gguf File")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = back) {
            Text("Back")
        }
    }
}





@Composable
fun ConnectPage(vm: JarvisVm, back: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(vm.listedApps()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "< Connect",
            color = TextP,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { back() }
        )
        Text(
            "System integrations and connected services.",
            color = TextS,
            fontSize = 12.sp
        )
        BasicTextField(
            value = q,
            onValueChange = { q = it },
            textStyle = TextStyle(color = TextP),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(Surface, RoundedCornerShape(20.dp))
                .padding(12.dp)
        )
        LazyColumn {
            items(
                items = apps.filter { q.isBlank() || it.label.contains(q, true) },
                key = { it.pkg }
            ) { a ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = TextP)
                        Text(
                            if (a.on) "Connected" else "Off",
                            color = TextS,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = a.on,
                        onCheckedChange = {
                            vm.setApp(a.pkg, it)
                            apps = vm.listedApps()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan)
                    )
                }
            }
        }
    }
}

@Composable
fun MorePage(go: (String) -> Unit, back: () -> Unit) {

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("More", color = TextP, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        listOf("tasks" to "Tasks", "files" to "Files", "settings" to "Settings", "connect" to "Connect", "model" to "AI Model").forEach { (r, n) ->
            Text(n, color = TextP, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(12.dp)).background(Surface).clickable { go(r) }.padding(14.dp))
        }
    }
}

@Composable fun VoicePage(vm: JarvisVm, close: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("JARVIS", color = TextP, fontWeight = FontWeight.Bold)
            Text("X", color = TextS, modifier = Modifier.clickable { close() })
        }
        Spacer(Modifier.height(20.dp))
        Text("Listening…", color = Cyan)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(160.dp).clip(CircleShape).border(4.dp, Cyan, CircleShape), contentAlignment = Alignment.Center) {
            Text("JARVIS", color = Cyan, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(72.dp).clip(CircleShape).background(Cyan).clickable {
            listen(ctx) { vm.send(it); close() }
        }, contentAlignment = Alignment.Center) { Text("mic", color = Bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
        Text("Tap to stop", color = TextS, modifier = Modifier.clickable { close() })
    }
}

fun listen(ctx: android.content.Context, on: (String) -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { on("Speech not available"); return }
    val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
    }
    sr.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(e: Int) { on("I didn't catch that.") }
        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) on(t)
        }
        override fun onPartialResults(r: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    })
    sr.startListening(i)
}
data class ChatMessage(val text: String, val isUser: Boolean)

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier
) {
val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages) { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.isUser) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth(0.8f)
                ) {
                    Text(
                        text = msg.text,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask JARVIS...") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank() && !isLoading) {
                        val prompt = inputText
AutomationManager(context).executeCommand(prompt)

                        messages.add(ChatMessage(prompt, isUser = true))
                        inputText = ""
                        isLoading = true

                        scope.launch {
                            val reply = "JARVIS: Processing your prompt offline..."
                            messages.add(ChatMessage(reply, isUser = false))
                            isLoading = false
                        }
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}
}

