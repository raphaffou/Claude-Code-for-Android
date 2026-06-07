package com.simplemobiletools.smsmessenger.activities

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.simplemobiletools.smsmessenger.R
import org.json.JSONObject

class SecuritySettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "claude_settings"
        const val KEY_SETTINGS = "agent_settings"

        val TOOLS = listOf(
            "Bash"      to "Exécuter des commandes shell",
            "Read"      to "Lire des fichiers",
            "Write"     to "Écrire des fichiers",
            "Edit"      to "Éditer des fichiers",
            "Glob"      to "Chercher des fichiers (pattern)",
            "Grep"      to "Chercher dans les fichiers",
            "WebFetch"  to "Requêtes HTTP",
            "WebSearch" to "Recherche web",
            "Task"      to "Lancer des sous-agents",
        )

        fun loadSettings(ctx: Context): JSONObject {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SETTINGS, null)
            return if (raw != null) JSONObject(raw) else defaultSettings()
        }

        val MODELS = mapOf(
            R.id.radio_sonnet to "claude-sonnet-4-6",
            R.id.radio_opus   to "claude-opus-4-8",
            R.id.radio_haiku  to "claude-haiku-4-5-20251001",
        )

        fun defaultSettings(): JSONObject = JSONObject().apply {
            put("dangerouslySkipPermissions", false)
            put("maxTurns", 10)
            put("model", "claude-sonnet-4-6")
            val tools = org.json.JSONArray()
            listOf("Bash", "Read", "Write", "Edit", "Glob", "Grep").forEach { tools.put(it) }
            put("allowedTools", tools)
        }
    }

    private val toolSwitches = mutableMapOf<String, SwitchMaterial>()
    private lateinit var switchSkipPerms: SwitchMaterial
    private lateinit var editMaxTurns: EditText
    private lateinit var modelGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security_settings)

        val toolbar = findViewById<Toolbar>(R.id.security_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        switchSkipPerms = findViewById(R.id.switch_skip_permissions)
        editMaxTurns    = findViewById(R.id.edit_max_turns)
        modelGroup      = findViewById(R.id.model_group)
        val container   = findViewById<LinearLayout>(R.id.tools_container)

        // Build tool rows
        val inf = LayoutInflater.from(this)
        for ((tool, desc) in TOOLS) {
            val row = inf.inflate(R.layout.item_tool_toggle, container, false)
            row.findViewById<TextView>(R.id.tool_name).text = tool
            row.findViewById<TextView>(R.id.tool_desc).text = desc
            val sw = row.findViewById<SwitchMaterial>(R.id.tool_switch)
            toolSwitches[tool] = sw
            container.addView(row)
        }

        loadIntoUI()
    }

    private fun loadIntoUI() {
        val s = loadSettings(this)
        switchSkipPerms.isChecked = s.optBoolean("dangerouslySkipPermissions", false)
        editMaxTurns.setText(s.optInt("maxTurns", 10).toString())
        val arr = s.optJSONArray("allowedTools")
        val allowed = mutableSetOf<String>()
        if (arr != null) for (i in 0 until arr.length()) allowed.add(arr.getString(i))
        for ((tool, sw) in toolSwitches) sw.isChecked = tool in allowed
        val savedModel = s.optString("model", "claude-sonnet-4-6")
        val radioId = MODELS.entries.firstOrNull { it.value == savedModel }?.key ?: R.id.radio_sonnet
        modelGroup.check(radioId)
    }

    private fun saveFromUI() {
        val tools = org.json.JSONArray()
        for ((tool, sw) in toolSwitches) if (sw.isChecked) tools.put(tool)
        val model = MODELS[modelGroup.checkedRadioButtonId] ?: "claude-sonnet-4-6"

        val s = JSONObject().apply {
            put("dangerouslySkipPermissions", switchSkipPerms.isChecked)
            put("maxTurns", editMaxTurns.text.toString().toIntOrNull() ?: 10)
            put("model", model)
            put("allowedTools", tools)
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SETTINGS, s.toString())
            .apply()
    }

    override fun onPause() { super.onPause(); saveFromUI() }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
