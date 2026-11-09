package com.irkhatary.hoshoof

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.irkhatary.hoshoof.ai.LocalAiEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class ChatMessage(val id: Long, val text: String, val fromUser: Boolean, val attachment: String? = null)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechCallback: ((String) -> Unit)? = null

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) speechCallback?.invoke(spoken)
        }
        speechCallback = null
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSpeechRecognition()
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION); pickedImage = it }
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importLocalModel(it) }
    }

    private var pickedImage by mutableStateOf<Uri?>(null)
    private var modelStatus by mutableStateOf("هنوز مدلی نصب نشده")
    private var modelFile by mutableStateOf<File?>(null)
    private val aiEngine = LocalAiEngine(cacheDir.absolutePath)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val existing = File(filesDir, "models/assistant.litertlm")
        if (existing.exists()) {
            modelFile = existing
            modelStatus = "در حال بارگذاری مدل ذخیره‌شده..."
            lifecycleScope.launch {
                runCatching { aiEngine.load(existing.absolutePath) }
                    .onSuccess { modelStatus = "مدل آفلاین آماده پاسخ‌گویی است" }
                    .onFailure { modelStatus = "بارگذاری مدل ناموفق بود: ${it.localizedMessage ?: "خطا"}" }
            }
        }
        setContent {
            HoshApp(
                modelStatus = modelStatus,
                onSpeak = ::speak,
                onMic = ::requestSpeech,
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                onPickModel = { modelPicker.launch(arrayOf("*/*")) },
                onRemoveImage = { pickedImage = null },
                imageUri = pickedImage,
                initialMessages = loadChatHistory(),
                onHistoryChanged = ::saveChatHistory,
                onSend = { text, image, onReply ->
                    lifecycleScope.launch {
                        val answer = runCatching {
                            val localImage = image?.let { uri ->
                                val imageFile = File(cacheDir, "chat_image_${System.currentTimeMillis()}.jpg")
                                contentResolver.openInputStream(uri)?.use { input ->
                                    imageFile.outputStream().use { output -> input.copyTo(output) }
                                } ?: throw IllegalStateException("تصویر قابل خواندن نیست")
                                imageFile.absolutePath
                            }
                            aiEngine.reply(text, localImage)
                        }.getOrElse {
                            "پاسخ‌گویی انجام نشد: ${it.localizedMessage ?: "مدل بارگذاری نشده یا از این ورودی پشتیبانی نمی‌کند."}"
                        }
                        onReply(answer)
                    }
                }
            )
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val result = tts?.setLanguage(Locale("fa", "IR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun loadChatHistory(): List<ChatMessage> {
        return try {
            val array = org.json.JSONArray(getSharedPreferences("chat", MODE_PRIVATE).getString("messages", "[]"))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ChatMessage(item.optLong("id"), item.optString("text"), item.optBoolean("user"), item.optString("attachment").takeIf { it.isNotBlank() })
            }.ifEmpty { listOf(ChatMessage(1, "سلام! من هوش آفلاین هستم. برای شروع، یک مدل سازگار را از تنظیمات وارد کن.", false)) }
        } catch (_: Exception) {
            listOf(ChatMessage(1, "سلام! من هوش آفلاین هستم. برای شروع، یک مدل سازگار را از تنظیمات وارد کن.", false))
        }
    }

    private fun saveChatHistory(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(150).forEach { message ->
            array.put(JSONObject().put("id", message.id).put("text", message.text)
                .put("user", message.fromUser).put("attachment", message.attachment ?: ""))
        }
        getSharedPreferences("chat", MODE_PRIVATE).edit().putString("messages", array.toString()).apply()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hosh_voice_reply")
    }

    private fun requestSpeech(callback: (String) -> Unit) {
        speechCallback = callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchSpeechRecognition()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            modelStatus = "سرویس تشخیص گفتار روی این گوشی در دسترس نیست"
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "به فارسی صحبت کنید")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            modelStatus = "اجرای تشخیص گفتار ممکن نشد"
        }
    }

    private fun importLocalModel(uri: Uri) {
        lifecycleScope.launch {
            modelStatus = "در حال کپی‌کردن فایل مدل..."
            try {
                val target = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val folder = File(filesDir, "models").apply { mkdirs() }
                    val destination = File(folder, "assistant.litertlm")
                    contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("فایل قابل خواندن نیست")
                    destination
                }
                modelFile = target
                modelStatus = "فایل ذخیره شد؛ در حال بارگذاری مدل..."
                aiEngine.load(target.absolutePath)
                modelStatus = "مدل آفلاین آماده پاسخ‌گویی است"
            } catch (e: Exception) {
                modelStatus = "بارگذاری ناموفق: ${e.localizedMessage ?: "فایل یا قالب ناسازگار"}"
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { aiEngine.close() }
        super.onDestroy()
    }
}

@Composable
private fun HoshApp(
    modelStatus: String,
    onSpeak: (String) -> Unit,
    onMic: ((String) -> Unit) -> Unit,
    onPickImage: () -> Unit,
    onPickModel: () -> Unit,
    onRemoveImage: () -> Unit,
    imageUri: Uri?,
    initialMessages: List<ChatMessage>,
    onHistoryChanged: (List<ChatMessage>) -> Unit,
    onSend: (String, Uri?, (String) -> Unit) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }
    LaunchedEffect(messages.size) { onHistoryChanged(messages.toList()) }
    val bg = Color(0xFF080D18)
    val surface = Color(0xFF121B2B)
    val panel = Color(0xFF19243A)
    val accent = Color(0xFF8B7CFF)
    val muted = Color(0xFF9BA9C5)

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = surface)) {
        Column(Modifier.fillMaxSize().background(bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(15.dp), color = accent.copy(alpha = .18f), modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("هوش", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("هوش آفلاین", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("دستیار هوشمند شخصی", color = muted, fontSize = 11.sp)
                }
                IconButton(onClick = { showTools = !showTools }) {
                    Icon(Icons.Default.Settings, contentDescription = "تنظیمات", tint = muted)
                }
            }

            if (showTools) {
                Spacer(Modifier.height(12.dp))
                Surface(color = surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("مدل هوش مصنوعی", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Text(modelStatus, color = muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("وارد کردن فایل مدل محلی")
                        }
                        Text("فایل مدل باید با موتور اجرا سازگار باشد. صرفاً واردکردن فایل، به معنی فعال‌شدن هوش مصنوعی نیست.", color = muted, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("گفت‌وگو", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        messages.clear()
                        messages.add(ChatMessage(System.currentTimeMillis(), "گفت‌وگوی تازه شروع شد. پیام خود را بنویس.", false))
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "گفت‌وگوی جدید", tint = muted, modifier = Modifier.size(18.dp))
                    }
                }
                Text("حالت محلی", color = Color(0xFF8EE6B1), fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))

            Surface(color = Color(0xFF0E1625), shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.Start else Arrangement.End) {
                            Column(horizontalAlignment = if (msg.fromUser) Alignment.Start else Alignment.End) {
                                Text(if (msg.fromUser) "شما" else "هوش آفلاین", color = muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                                Surface(
                                    color = if (msg.fromUser) Color(0xFF263653) else Color(0xFF202746),
                                    shape = RoundedCornerShape(17.dp),
                                    modifier = Modifier.widthIn(max = 310.dp)
                                ) {
                                    Text(msg.text, modifier = Modifier.padding(13.dp), color = Color(0xFFF1F3FA), fontSize = 14.sp, textAlign = TextAlign.Start, lineHeight = 23.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (imageUri != null) {
                Spacer(Modifier.height(8.dp))
                Surface(color = panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = accent)
                        Spacer(Modifier.width(8.dp))
                        Text("یک تصویر پیوست شده", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = onRemoveImage) { Icon(Icons.Default.Close, contentDescription = "حذف تصویر", tint = muted) }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(color = surface, shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPickImage) { Icon(Icons.Default.AttachFile, contentDescription = "افزودن تصویر", tint = muted) }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Right),
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) Text("پیامت را بنویس...", color = muted, fontSize = 13.sp)
                                    inner()
                                }
                            }
                        )
                        IconButton(onClick = {
                            onMic { recognized -> input = if (input.isBlank()) recognized else "$input $recognized" }
                        }) { Icon(Icons.Default.Mic, contentDescription = "گفتار به متن", tint = accent) }
                        IconButton(onClick = {
                            if (input.isNotBlank() || imageUri != null) {
                                val text = input.trim().ifBlank { "این تصویر را بررسی کن." }
                                messages.add(ChatMessage(System.currentTimeMillis(), text, true, imageUri?.toString()))
                                onSend(text, imageUri) { answer ->
                                    messages.add(ChatMessage(System.currentTimeMillis(), answer, false))
                                    onSpeak(answer)
                                }
                                input = ""
                                onRemoveImage()
                            }
                        }) {
                            Surface(color = accent, shape = CircleShape, modifier = Modifier.size(38.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Send, contentDescription = "ارسال پیام", tint = Color.White, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = muted, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("مدل روی دستگاه • تشخیص گفتار وابسته به سرویس نصب‌شده", color = muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
