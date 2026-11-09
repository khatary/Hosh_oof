package com.irkhatary.hoshoof

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
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
import java.util.Locale

private data class ChatMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val askAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startSpeechInput() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { HoshApp(onSpeak = ::speak, onMic = ::requestSpeechInput) }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("fa", "IR")
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hosh_reply")
    }

    private fun requestSpeechInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startSpeechInput()
        else askAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startSpeechInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "صحبت کنید")
        }
        try { startActivityForResult(intent, 701) } catch (_: Exception) { }
    }

    @Deprecated("Deprecated by Android; retained for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) sendRecognizedText(spoken)
        }
    }

    private fun sendRecognizedText(text: String) {
        // Voice input is routed through the same chat composer in the UI in a later iteration.
        speak("صدای شما دریافت شد. موتور هوش مصنوعی آفلاین هنوز به برنامه متصل نشده است.")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun HoshApp(onSpeak: (String) -> Unit, onMic: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            ChatMessage("سلام! من هوش آفلاین هستم. می‌توانی به فارسی با من گفتگو کنی.", false),
            ChatMessage("نسخهٔ اولیهٔ محیط گفتگو آماده است. موتور مدل زبانی آفلاین در مرحلهٔ بعد اضافه می‌شود.", false)
        )
    }
    val bg = Color(0xFF090D18)
    val panel = Color(0xFF151D2E)
    val accent = Color(0xFF6D7CFF)

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, background = bg, surface = panel,
        onBackground = Color(0xFFF2F4FF), onSurface = Color(0xFFF2F4FF)
    )) {
        Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(shape = CircleShape, color = accent.copy(alpha = .18f), modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("هوش", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("هوش آفلاین", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("دستیار فارسی چندزبانه", fontSize = 12.sp, color = Color(0xFF9BA8C5))
                }
                Text("نسخه ۰.۱", color = Color(0xFF9BA8C5), fontSize = 11.sp)
            }
            Spacer(Modifier.height(20.dp))
            Surface(color = Color(0xFF11192A), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    reverseLayout = false
                ) {
                    items(messages) { msg ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.Start else Arrangement.End) {
                            Surface(
                                color = if (msg.fromUser) Color(0xFF25324A) else Color(0xFF20284A),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Text(msg.text, modifier = Modifier.padding(14.dp), color = Color.White, textAlign = TextAlign.Start, lineHeight = 22.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().background(panel, RoundedCornerShape(24.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMic) { Icon(Icons.Default.Mic, contentDescription = "مکالمه صوتی", tint = accent) }
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Right),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("پیامت را بنویس...", color = Color(0xFF8490A9))
                            inner()
                        }
                    }
                )
                IconButton(onClick = {
                    if (input.isNotBlank()) {
                        messages.add(ChatMessage(input.trim(), true))
                        input = ""
                        messages.add(ChatMessage("موتور هوش مصنوعی محلی هنوز نصب نشده است؛ این بخش در مرحلهٔ بعد به مدل آفلاین متصل می‌شود.", false))
                    }
                }) { Icon(Icons.Default.Send, contentDescription = "ارسال", tint = accent) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF8D9ABC), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("مکالمه صوتی آزمایشی • پردازش کاملاً آفلاین هنوز فعال نشده", color = Color(0xFF8D9ABC), fontSize = 10.sp)
            }
        }
    }
}
