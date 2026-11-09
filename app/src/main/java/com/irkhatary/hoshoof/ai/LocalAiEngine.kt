package com.irkhatary.hoshoof.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device language model runtime.
 * Models are kept in app-private storage and are never uploaded by this class.
 */
class LocalAiEngine {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        closeInternal()
        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU()
            )
        )
        try {
            newEngine.initialize()
            val newConversation = newEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are Hosh Offline, a helpful multilingual assistant. " +
                            "Prioritize fluent, respectful Persian when the user writes Persian. " +
                            "Be honest when you do not know an answer."
                    )
                )
            )
            engine = newEngine
            conversation = newConversation
        } catch (error: Throwable) {
            newEngine.close()
            throw error
        }
    }

    suspend fun reply(prompt: String): String = withContext(Dispatchers.IO) {
        val activeConversation = conversation
            ?: throw IllegalStateException("ابتدا یک مدل سازگار .litertlm وارد و بارگذاری کنید.")
        activeConversation.sendMessage(prompt).text
            .ifBlank { "مدل پاسخ متنی تولید نکرد." }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        try { conversation?.close() } catch (_: Exception) { }
        conversation = null
        try { engine?.close() } catch (_: Exception) { }
        engine = null
    }
}
