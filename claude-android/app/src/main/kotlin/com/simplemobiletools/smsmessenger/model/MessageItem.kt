package com.simplemobiletools.smsmessenger.model

sealed class MessageItem {

    data class User(
        val text: String,
        val ts: Long = System.currentTimeMillis(),
    ) : MessageItem()

    data class Assistant(
        var text: String = "",
        var thinking: String = "",
        var isComplete: Boolean = false,
        var isThinkingExpanded: Boolean = false,
        val ts: Long = System.currentTimeMillis(),
    ) : MessageItem()

    data class ToolEvent(
        val id: String,
        val tool: String,
        val input: String,
        var result: String? = null,
        var isExpanded: Boolean = false,
    ) : MessageItem()

    object Typing : MessageItem()
}
