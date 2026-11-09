package com.irkhatary.hoshoof.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A single serialized owner for the on-device LiteRT-LM engine.
 * The model remains in app-private storage and is never uploaded.
 */
class LocalAiEngine(private val cachePath: String) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
            val candidate = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cachePath
                )
            )
            try {
                candidate.initialize()
                val chat = candidate.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "You are Hosh Offline, a helpful multilingual assistant. " +
                                "Prioritize clear Persian when the user writes Persian. " +
                                "Answer honestly and do not invent facts."
                        )
                    )
                )
                engine = candidate
                conversation = chat
            } catch (error: Throwable) {
                // Do not call close on a failed initialization; native lifecycle may not be ready.
                throw error
            }
        }
    }

    suspend fun reply(prompt: String, imagePath: String? = null): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = conversation
                    ?: throw IllegalStateException("ابتدا یک مدل سازگار .litertlm را وارد و بارگذاری کنید.")
                val response = if (imagePath == null) {
                    active.sendMessage(prompt)
                } else {
                    active.sendMessage(
                        Contents.of(
                            Content.ImageFile(imagePath),
                            Content.Text(prompt)
                        )
                    )
                }
                response.text.ifBlank { "مدل پاسخ متنی تولید نکرد." }
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock { closeInternal() }
    }

    private fun closeInternal() {
        try { conversation?.close() } catch (_: Exception) { }
        conversation = null
        try { engine?.close() } catch (_: Exception) { }
        engine = null
    }
}
