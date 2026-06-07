package com.simplemobiletools.smsmessenger

import android.content.Context
import com.simplemobiletools.smsmessenger.model.MessageItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SessionManager {

    private const val FILE_NAME = "sessions.json"
    private const val MAX_SESSIONS = 50

    data class Session(
        val id: String,
        val title: String,
        val createdAt: Long,
        val messages: List<MessageItem>,
        val resumeId: String? = null,   // hash de session Claude Code pour --resume
    )

    // ── Persistence ────────────────────────────────────────────────────────────

    fun loadAll(context: Context): MutableList<Session> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { parseSession(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveAll(context: Context, sessions: List<Session>) {
        val arr = JSONArray()
        sessions.take(MAX_SESSIONS).forEach { arr.put(serializeSession(it)) }
        try { File(context.filesDir, FILE_NAME).writeText(arr.toString()) } catch (_: Exception) {}
    }

    fun newSession(items: List<MessageItem> = emptyList()): Session {
        val title = items.filterIsInstance<MessageItem.User>().firstOrNull()?.text?.take(40) ?: "Nouvelle conversation"
        return Session(
            id = "s_${System.currentTimeMillis()}",
            title = title,
            createdAt = System.currentTimeMillis(),
            messages = items.toList(),
        )
    }

    fun titleFor(items: List<MessageItem>): String =
        items.filterIsInstance<MessageItem.User>().firstOrNull()?.text?.take(40) ?: "Nouvelle conversation"

    // ── Serialization ──────────────────────────────────────────────────────────

    private fun serializeSession(s: Session): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("createdAt", s.createdAt)
        val msgs = JSONArray()
        s.messages.forEach { msgs.put(serializeMessage(it)) }
        put("messages", msgs)
        if (s.resumeId != null) put("resumeId", s.resumeId)
    }

    private fun serializeMessage(m: MessageItem): JSONObject = when (m) {
        is MessageItem.User      -> JSONObject().apply { put("type","user"); put("text", m.text); put("ts", m.ts) }
        is MessageItem.Assistant -> JSONObject().apply {
            put("type","assistant"); put("text", m.text); put("thinking", m.thinking)
            put("isComplete", m.isComplete); put("ts", m.ts)
        }
        is MessageItem.ToolEvent -> JSONObject().apply {
            put("type","tool_event"); put("id", m.id); put("tool", m.tool)
            put("input", m.input); put("result", m.result ?: "")
        }
        MessageItem.Typing -> JSONObject().apply { put("type","typing") }
    }

    private fun parseSession(obj: JSONObject): Session {
        val msgs = obj.getJSONArray("messages")
        return Session(
            id        = obj.getString("id"),
            title     = obj.getString("title"),
            createdAt = obj.getLong("createdAt"),
            messages  = (0 until msgs.length()).mapNotNull { parseMessage(msgs.getJSONObject(it)) },
            resumeId  = obj.optString("resumeId").takeIf { it.isNotEmpty() },
        )
    }

    private fun parseMessage(obj: JSONObject): MessageItem? = when (obj.getString("type")) {
        "user"       -> MessageItem.User(obj.getString("text"), obj.optLong("ts", System.currentTimeMillis()))
        "assistant"  -> MessageItem.Assistant(
            text = obj.getString("text"), thinking = obj.optString("thinking"),
            isComplete = obj.optBoolean("isComplete", true),
            ts = obj.optLong("ts", System.currentTimeMillis()),
        )
        "tool_event" -> MessageItem.ToolEvent(
            id = obj.optString("id"), tool = obj.getString("tool"),
            input = obj.getString("input"),
            result = obj.optString("result").takeIf { it.isNotEmpty() },
        )
        else -> null
    }
}
