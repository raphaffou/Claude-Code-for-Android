package com.simplemobiletools.smsmessenger.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.model.MessageItem
import org.json.JSONObject

class ClaudeAdapter(private val items: List<MessageItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_USER       = 1
        const val TYPE_ASSISTANT  = 2
        const val TYPE_TOOL_EVENT = 3
        const val TYPE_TYPING     = 4

        private val TOOL_ICONS = mapOf(
            "Bash"      to "⚡",
            "Read"      to "📖",
            "Write"     to "📝",
            "Edit"      to "✏️",
            "Glob"      to "🔍",
            "Grep"      to "🔍",
            "WebFetch"  to "🌐",
            "WebSearch" to "🔍",
            "Task"      to "🤖",
        )

        fun iconFor(tool: String) = TOOL_ICONS[tool] ?: "🔧"
    }

    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is MessageItem.User       -> TYPE_USER
        is MessageItem.Assistant  -> TYPE_ASSISTANT
        is MessageItem.ToolEvent  -> TYPE_TOOL_EVENT
        MessageItem.Typing        -> TYPE_TYPING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER       -> UserVH(inf.inflate(R.layout.item_user_message, parent, false))
            TYPE_ASSISTANT  -> AssistantVH(inf.inflate(R.layout.item_assistant_message, parent, false))
            TYPE_TOOL_EVENT -> ToolEventVH(inf.inflate(R.layout.item_tool_event, parent, false))
            else            -> TypingVH(inf.inflate(R.layout.item_typing, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val item = items[pos]) {
            is MessageItem.User      -> (holder as UserVH).bind(item)
            is MessageItem.Assistant -> (holder as AssistantVH).bind(item)
            is MessageItem.ToolEvent -> (holder as ToolEventVH).bind(item)
            MessageItem.Typing       -> { /* animation managed by attach/detach */ }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        if (holder is TypingVH) holder.startAnimation()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is TypingVH) holder.stopAnimation()
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TypingVH) holder.stopAnimation()
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.message_text)
        fun bind(item: MessageItem.User) { text.text = item.text }
    }

    class AssistantVH(view: View) : RecyclerView.ViewHolder(view) {
        private val msgText:        TextView = view.findViewById(R.id.message_text)
        private val thinkingSection: View    = view.findViewById(R.id.thinking_section)
        private val thinkingHeader: TextView = view.findViewById(R.id.thinking_header)
        private val thinkingContent:TextView = view.findViewById(R.id.thinking_content)

        fun bind(item: MessageItem.Assistant) {
            msgText.text = item.text

            if (item.thinking.isNotEmpty()) {
                thinkingSection.visibility = View.VISIBLE
                thinkingContent.text = item.thinking
                thinkingContent.visibility = if (item.isThinkingExpanded) View.VISIBLE else View.GONE
                thinkingHeader.text = if (item.isThinkingExpanded) "▼ Réflexion" else "▶ Réflexion"
                thinkingHeader.setOnClickListener {
                    item.isThinkingExpanded = !item.isThinkingExpanded
                    thinkingContent.visibility = if (item.isThinkingExpanded) View.VISIBLE else View.GONE
                    thinkingHeader.text = if (item.isThinkingExpanded) "▼ Réflexion" else "▶ Réflexion"
                }
            } else {
                thinkingSection.visibility = View.GONE
            }
        }
    }

    class ToolEventVH(view: View) : RecyclerView.ViewHolder(view) {
        private val pill:   TextView = view.findViewById(R.id.tool_pill_text)
        private val output: TextView = view.findViewById(R.id.tool_output_text)

        fun bind(item: MessageItem.ToolEvent) {
            val icon  = iconFor(item.tool)
            val label = formatInput(item.tool, item.input)

            if (item.result != null) {
                val raw = item.result!!
                output.text = if (raw.length > 3000) raw.take(3000) + "\n[…tronqué]" else raw
                output.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
                pillText(item, icon, label)
                pill.setOnClickListener {
                    item.isExpanded = !item.isExpanded
                    output.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
                    pillText(item, icon, label)
                }
            } else {
                output.visibility = View.GONE
                pillText(item, icon, label)
                pill.setOnClickListener(null)
            }
        }

        private fun pillText(item: MessageItem.ToolEvent, icon: String, label: String) {
            val chevron = when {
                item.result == null -> ""
                item.isExpanded     -> "  ▼"
                else                -> "  ▶"
            }
            pill.text = "$icon ${item.tool}  $label$chevron"
        }

        private fun formatInput(tool: String, raw: String): String = try {
            val obj = JSONObject(raw)
            when (tool) {
                "Bash"      -> obj.optString("command").trim()
                                  .replace('\n', ';').take(70)
                "Read"      -> shortenPath(obj.optString("file_path"))
                "Write"     -> shortenPath(obj.optString("file_path"))
                "Edit"      -> shortenPath(obj.optString("file_path"))
                "Glob"      -> obj.optString("pattern").take(50)
                "Grep"      -> {
                    val pat  = obj.optString("pattern")
                    val path = obj.optString("path").takeIf { it.isNotEmpty() }
                    if (path != null) "\"$pat\"  ${shortenPath(path)}" else "\"$pat\""
                }
                "WebFetch"  -> obj.optString("url")
                                  .removePrefix("https://").removePrefix("http://").take(60)
                "WebSearch" -> "\"${obj.optString("query").take(60)}\""
                "Task"      -> (obj.optString("description").takeIf { it.isNotEmpty() }
                               ?: obj.optString("prompt")).take(60)
                else        -> raw.take(60).replace('\n', ' ')
            }.trimEnd()
        } catch (_: Exception) { raw.take(60).replace('\n', ' ') }

        private fun shortenPath(path: String): String {
            val parts = path.split('/').filter { it.isNotEmpty() }
            return if (parts.size <= 2) path else "…/${parts.takeLast(2).joinToString("/")}"
        }
    }

    class TypingVH(view: View) : RecyclerView.ViewHolder(view) {
        private val dots: TextView = view.findViewById(R.id.typing_dots)
        private val frames = listOf("●", "● ●", "● ● ●")
        private var frame = 0
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val tick = object : Runnable {
            override fun run() { dots.text = frames[frame++ % frames.size]; handler.postDelayed(this, 400) }
        }
        fun startAnimation() { handler.post(tick) }
        fun stopAnimation()  { handler.removeCallbacks(tick) }
    }
}
