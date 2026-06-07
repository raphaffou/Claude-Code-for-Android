package com.simplemobiletools.smsmessenger.activities

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.SessionManager
import com.simplemobiletools.smsmessenger.adapters.ClaudeAdapter
import com.simplemobiletools.smsmessenger.adapters.SessionAdapter
import com.simplemobiletools.smsmessenger.model.MessageItem
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ClaudeActivity : AppCompatActivity() {

    // ── UI refs ───────────────────────────────────────────────────────────────
    private lateinit var drawer: DrawerLayout
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var status: TextView
    private lateinit var contextBar: android.widget.ProgressBar
    private lateinit var sessionsList: RecyclerView
    private var serverMenuItem: MenuItem? = null
    private var lastInputTokens = 0

    // ── Data ──────────────────────────────────────────────────────────────────
    private val items = mutableListOf<MessageItem>()
    private lateinit var adapter: ClaudeAdapter

    private val sessions = mutableListOf<SessionManager.Session>()
    private var currentSessionId: String? = null

    // ── Network ───────────────────────────────────────────────────────────────
    private val HTTP_BASE = "http://127.0.0.1:8765"
    private val HTTP_PING = "$HTTP_BASE/ping"
    private val HTTP_STOP = "$HTTP_BASE/stop"
    private val WS_URL    = "ws://127.0.0.1:8766"

    private val handler = Handler(Looper.getMainLooper())
    private var serverReady = false
    private var isProcessing = false
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var currentAssistant: MessageItem.Assistant? = null

    // ── Text streaming (direct, vrais deltas du CLI via --include-partial-messages) ──
    private var streamTarget: MessageItem.Assistant? = null

    private fun enqueueText(text: String) {
        val asst = getOrCreateAssistant()
        streamTarget = asst
        asst.text += text
        notifyAssistant(asst)
    }

    private fun flushStream() {
        streamTarget = null
    }

    // ── Thinking streaming ────────────────────────────────────────────────────
    private var thinkTarget: MessageItem.Assistant? = null

    private fun enqueueThinking(text: String) {
        val asst = getOrCreateAssistant()
        asst.isThinkingExpanded = true
        thinkTarget = asst
        asst.thinking += text
        notifyAssistant(asst)
    }

    private fun flushThinking() {
        thinkTarget = null
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private val PERM_TERMUX = "com.termux.permission.RUN_COMMAND"
    private val REQ_PERM = 1001

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_claude)
        setSupportActionBar(findViewById<Toolbar>(R.id.claude_toolbar))

        drawer       = findViewById(R.id.drawer_layout)
        list         = findViewById(R.id.claude_messages_list)
        input        = findViewById(R.id.claude_message_input)
        sendBtn      = findViewById(R.id.claude_send_button)
        stopBtn      = findViewById(R.id.claude_stop_button)
        status       = findViewById(R.id.claude_status_text)
        contextBar   = findViewById(R.id.context_bar)
        sessionsList = findViewById(R.id.sessions_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)

        adapter = ClaudeAdapter(items)
        list.adapter = adapter
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        sendBtn.setOnClickListener { sendMessage() }
        stopBtn.setOnClickListener { cancelProcessing() }
        input.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND ||
                (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN))
            { sendMessage(); true } else false
        }

        findViewById<ImageButton>(R.id.btn_new_session).setOnClickListener {
            saveCurrentSession()
            startNewSession()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<ImageButton>(R.id.btn_import_session).setOnClickListener {
            showImportSessionDialog()
        }

        loadSessions()
        updateInputState()
        checkOrStartServer()
    }

    override fun onPause()   { super.onPause();  saveCurrentSession() }
    override fun onDestroy() {
        super.onDestroy()
        flushThinking()
        flushStream()
        ws?.close(1000, null)
        handler.removeCallbacksAndMessages(null)
        okHttp.dispatcher.executorService.shutdown()
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_claude, menu)
        serverMenuItem = menu.findItem(R.id.action_server_toggle)
        updateServerIcon()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home         -> { drawer.openDrawer(GravityCompat.START); true }
        R.id.action_server_toggle -> { if (serverReady) stopServer() else checkOrStartServer(); true }
        R.id.action_settings      -> { startActivity(Intent(this, SecuritySettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updateServerIcon() {
        serverMenuItem?.setIcon(if (serverReady) R.drawable.ic_server_on else R.drawable.ic_server_off)
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    private fun loadSessions() {
        sessions.clear()
        sessions.addAll(SessionManager.loadAll(this).reversed())
        if (sessions.isEmpty()) startNewSession()
        else switchToSession(sessions.first())
        refreshSessionsList()
    }

    private fun startNewSession() {
        val s = SessionManager.newSession()
        sessions.add(0, s)
        currentSessionId = s.id
        items.clear()
        adapter.notifyDataSetChanged()
        refreshSessionsList()
        updateTitle()
    }

    private fun switchToSession(session: SessionManager.Session) {
        currentSessionId = session.id
        lastInputTokens = 0
        contextBar.visibility = android.view.View.GONE
        items.clear()
        items.addAll(session.messages.filter { it !is MessageItem.Typing })
        adapter.notifyDataSetChanged()
        scrollToBottom()
        updateTitle()
        refreshSessionsList()
    }

    private fun saveCurrentSession() {
        val id = currentSessionId ?: return
        val cleanItems = items.filter { it !is MessageItem.Typing }
        if (cleanItems.isEmpty()) return
        val idx = sessions.indexOfFirst { it.id == id }
        val existing = sessions.getOrNull(idx)
        val title = SessionManager.titleFor(cleanItems)
        val updated = SessionManager.Session(
            id, title,
            existing?.createdAt ?: System.currentTimeMillis(),
            cleanItems,
            existing?.resumeId,          // préserver le resumeId
        )
        if (idx >= 0) sessions[idx] = updated else sessions.add(0, updated)
        SessionManager.saveAll(this, sessions)
    }

    private fun currentResumeId(): String? =
        sessions.find { it.id == currentSessionId }?.resumeId

    private fun showImportSessionDialog() {
        val input = EditText(this).apply {
            hint = "Hash de session  (ex: c8a92f12-3a6b-…)"
            textSize = 13f
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Importer une session Claude Code")
            .setMessage("L'historique sera restauré et les prochains messages continueront cette session.")
            .setView(input)
            .setPositiveButton("Importer") { _, _ ->
                val hash = input.text.toString().trim()
                if (hash.isNotEmpty()) loadAndImportSession(hash)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun loadAndImportSession(hash: String) {
        status.visibility = View.VISIBLE
        status.text = "Chargement de la session…"
        Thread {
            try {
                val conn = URL("$HTTP_BASE/load_session/$hash").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout   = 60_000
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val arr  = json.getJSONArray("messages")

                val loaded = mutableListOf<MessageItem>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    when (m.getString("role")) {
                        "user"      -> loaded.add(MessageItem.User(m.getString("text")))
                        "assistant" -> loaded.add(MessageItem.Assistant(
                            text      = m.getString("text"),
                            thinking  = m.optString("thinking"),
                            isComplete = true,
                        ))
                    }
                }

                runOnUiThread {
                    status.visibility = View.GONE
                    saveCurrentSession()
                    val title = "↩ ${SessionManager.titleFor(loaded)}"
                    val s = SessionManager.Session(
                        id        = "s_${System.currentTimeMillis()}",
                        title     = title,
                        createdAt = System.currentTimeMillis(),
                        messages  = loaded,
                        resumeId  = hash,
                    )
                    sessions.add(0, s)
                    SessionManager.saveAll(this, sessions)
                    currentSessionId = s.id
                    lastInputTokens  = 0
                    contextBar.visibility = View.GONE
                    items.clear()
                    items.addAll(loaded)
                    adapter.notifyDataSetChanged()
                    scrollToBottom()
                    updateTitle()
                    refreshSessionsList()
                    drawer.closeDrawer(GravityCompat.START)
                    Toast.makeText(this, "${loaded.size} messages restaurés", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.visibility = View.GONE
                    Toast.makeText(this, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun attachResumeId(hash: String, auto: Boolean = false) {
        val id = currentSessionId ?: return
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        val s = sessions[idx]
        val newTitle = if (auto) s.title else "↩ ${s.title.removePrefix("↩ ")}"
        sessions[idx] = s.copy(resumeId = hash, title = newTitle)
        SessionManager.saveAll(this, sessions)
        if (!auto) {
            refreshSessionsList()
            updateTitle()
            Toast.makeText(this, "Session liée : ${hash.take(8)}…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSessionsList() {
        sessionsList.adapter = SessionAdapter(sessions, currentSessionId) { session ->
            saveCurrentSession()
            switchToSession(session)
            drawer.closeDrawer(GravityCompat.START)
        }
        if (sessionsList.layoutManager == null)
            sessionsList.layoutManager = LinearLayoutManager(this)
    }

    private fun updateTitle() {
        val s = sessions.find { it.id == currentSessionId }
        supportActionBar?.title = s?.title?.take(25) ?: "Claude"
    }

    // ── Server ────────────────────────────────────────────────────────────────

    private fun checkOrStartServer() {
        status.visibility = View.VISIBLE
        status.text = "Vérification…"
        Thread {
            if (pingHttp()) runOnUiThread { connectWs() }
            else runOnUiThread { status.text = "Démarrage…"; launchWithPermCheck() }
        }.start()
    }

    private fun stopServer() {
        ws?.close(1000, null); ws = null
        Thread {
            try { val c = URL(HTTP_STOP).openConnection() as HttpURLConnection; c.requestMethod = "GET"; c.connectTimeout = 2000; c.readTimeout = 2000; c.responseCode } catch (_: Exception) {}
            runOnUiThread { onServerStopped() }
        }.start()
    }

    private fun onServerStopped() {
        serverReady = false
        isProcessing = false
        flushThinking()
        flushStream()
        removeTyping()
        currentAssistant = null
        status.visibility = View.VISIBLE
        status.text = "Serveur arrêté"
        updateInputState()
        updateServerIcon()
    }

    private fun connectWs() {
        ws = okHttp.newWebSocket(Request.Builder().url(WS_URL).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response)           = runOnUiThread { onServerReady() }
            override fun onMessage(ws: WebSocket, text: String)       = runOnUiThread { handleWsEvent(text) }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = runOnUiThread {
                if (serverReady) onServerStopped() else { status.text = "WS inaccessible"; pollUntilWs() }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = runOnUiThread {
                if (serverReady) onServerStopped()
            }
        })
    }

    private fun pollUntilWs(attempt: Int = 0) {
        if (attempt > 30) { status.text = "Inaccessible après 60s"; return }
        handler.postDelayed({ Thread { val up = pingHttp(); runOnUiThread { if (up) connectWs() else pollUntilWs(attempt + 1) } }.start() }, 2000)
    }

    private fun onServerReady() {
        serverReady = true
        status.visibility = View.GONE
        updateInputState()
        updateServerIcon()
    }

    private fun updateInputState() {
        val ready = serverReady && !isProcessing
        input.isEnabled = ready
        sendBtn.visibility = if (serverReady && isProcessing) View.GONE else View.VISIBLE
        stopBtn.visibility = if (serverReady && isProcessing) View.VISIBLE else View.GONE
        sendBtn.isEnabled = ready
    }

    // ── Termux ────────────────────────────────────────────────────────────────

    private fun launchWithPermCheck() {
        if (ContextCompat.checkSelfPermission(this, PERM_TERMUX) == PackageManager.PERMISSION_GRANTED)
        { startServerViaTermux(); pollUntilWs() }
        else ActivityCompat.requestPermissions(this, arrayOf(PERM_TERMUX), REQ_PERM)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, perms, gr)
        if (rc == REQ_PERM) {
            if (gr.firstOrNull() == PackageManager.PERMISSION_GRANTED) { startServerViaTermux(); pollUntilWs() }
            else status.text = "Permission Termux refusée"
        }
    }

    private fun startServerViaTermux() {
        try {
            startService(Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("/storage/emulated/0/claude_code/claude-android-server/start_server.sh"))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_TERMINAL", false)
            })
            handler.postDelayed({ startActivity(Intent(this, ClaudeActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }) }, 400)
        } catch (e: Exception) { status.text = "Échec Termux : ${e.message}" }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        if (!serverReady || ws == null || isProcessing) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()

        addItem(MessageItem.User(text))
        showTyping()
        isProcessing = true
        updateInputState()

        val resumeId = currentResumeId()
        ws?.send(JSONObject().apply {
            put("type", "chat")
            put("text", text)
            if (resumeId != null) put("resume_id", resumeId)
            else put("history", buildHistory())
            put("settings", SecuritySettingsActivity.loadSettings(this@ClaudeActivity))
        }.toString())
    }

    private fun cancelProcessing() {
        ws?.send(JSONObject().apply { put("type", "cancel") }.toString())
    }

    private fun buildHistory(): JSONArray {
        val arr = JSONArray()
        // Garder seulement les 30 derniers messages pour éviter des prompts trop longs
        val relevant = items.filter { it is MessageItem.User || (it is MessageItem.Assistant && it.isComplete) }
            .takeLast(30)
        for (item in relevant) when (item) {
            is MessageItem.User ->
                arr.put(JSONObject().apply { put("role","user"); put("content", item.text) })
            is MessageItem.Assistant ->
                if (item.text.isNotEmpty())
                    arr.put(JSONObject().apply { put("role","assistant"); put("content", item.text) })
            else -> {}
        }
        return arr
    }

    // ── WS events ─────────────────────────────────────────────────────────────

    private fun handleWsEvent(raw: String) {
        val e = JSONObject(raw)
        when (e.getString("type")) {
            "thinking"    -> enqueueThinking(e.getString("text"))
            "tool_use"    -> addToolEvent(e.getString("id"), e.getString("tool"), e.getString("input"))
            "tool_result" -> updateToolResult(e.getString("tool_use_id"), e.getString("content"))
            "text_delta"  -> enqueueText(e.getString("text"))
            "done"        -> onDone(e.getString("text"), e.optString("session_id"), e.optInt("input_tokens"))
            "error"       -> onError(e.getString("message"))
            "cancelled"   -> onCancelled()
        }
    }

    private fun getOrCreateAssistant(): MessageItem.Assistant {
        currentAssistant?.let { return it }
        removeTyping()
        return MessageItem.Assistant().also { currentAssistant = it; addItem(it) }
    }

    private fun addToolEvent(id: String, tool: String, input: String) {
        currentAssistant = null
        removeTyping()
        addItem(MessageItem.ToolEvent(id, tool, input))
        showTyping()
    }

    private fun updateToolResult(toolUseId: String, content: String) {
        val idx = items.indexOfFirst { it is MessageItem.ToolEvent && it.id == toolUseId }
        if (idx >= 0) {
            (items[idx] as MessageItem.ToolEvent).result = content
            adapter.notifyItemChanged(idx)
        }
    }

    private fun notifyAssistant(asst: MessageItem.Assistant) {
        val idx = items.indexOf(asst as MessageItem?)
        if (idx >= 0) { adapter.notifyItemChanged(idx); scrollToBottom() }
    }

    private fun onDone(fullText: String, sessionId: String = "", inputTokens: Int = 0) {
        flushThinking()
        flushStream()
        removeTyping()
        currentAssistant?.let {
            if (it.text.isEmpty()) it.text = fullText
            it.isComplete = true
            notifyAssistant(it)
        }
        currentAssistant = null
        isProcessing = false
        updateInputState()
        if (sessionId.isNotEmpty() && currentResumeId() != sessionId)
            attachResumeId(sessionId, auto = true)
        updateContextBar(inputTokens)
        saveCurrentSession()
        refreshSessionsList()
        updateTitle()
        scrollToBottom()
    }

    private fun updateContextBar(tokens: Int) {
        val compacted = lastInputTokens > 0 && tokens in 1 until lastInputTokens / 2
        if (tokens > 0) lastInputTokens = tokens

        contextBar.visibility = View.VISIBLE

        if (tokens == 0) {
            // Données absentes : barre subtile pour confirmer que l'UI marche
            contextBar.progress = 1
            contextBar.progressTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
            return
        }

        contextBar.max = 200_000
        contextBar.progress = tokens

        val color = when {
            tokens < 100_000 -> 0xFF4CAF50.toInt()   // vert  < 50 %
            tokens < 160_000 -> 0xFFFF9800.toInt()   // orange 50–80 %
            else             -> 0xFFF44336.toInt()   // rouge  > 80 %
        }
        contextBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

        if (compacted) {
            status.visibility = View.VISIBLE
            status.text = "Contexte compacté (${tokens / 1000}K tokens)"
            handler.postDelayed({ if (!isProcessing) status.visibility = View.GONE }, 4000)
        }
    }

    private fun onError(msg: String) {
        flushThinking()
        flushStream()
        removeTyping()
        currentAssistant = null
        isProcessing = false
        updateInputState()
        Toast.makeText(this, "Erreur : $msg", Toast.LENGTH_LONG).show()
    }

    private fun onCancelled() {
        flushThinking()
        flushStream()
        removeTyping()
        currentAssistant?.let {
            it.isComplete = true
            if (it.text.isEmpty()) it.text = "[annulé]"
            notifyAssistant(it)
        }
        currentAssistant = null
        isProcessing = false
        updateInputState()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showTyping() { if (items.none { it is MessageItem.Typing }) addItem(MessageItem.Typing) }
    private fun removeTyping() {
        val i = items.indexOfFirst { it is MessageItem.Typing }
        if (i >= 0) { items.removeAt(i); adapter.notifyItemRemoved(i) }
    }
    private fun addItem(item: MessageItem) { items.add(item); adapter.notifyItemInserted(items.size - 1); scrollToBottom() }
    private fun scrollToBottom() { if (items.isNotEmpty()) list.scrollToPosition(items.size - 1) }

    private fun pingHttp(): Boolean = try {
        val c = URL(HTTP_PING).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 2000; c.readTimeout = 2000; c.responseCode == 200
    } catch (_: Exception) { false }
}
