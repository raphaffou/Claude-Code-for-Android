package com.simplemobiletools.smsmessenger.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.SessionManager
import java.text.SimpleDateFormat
import java.util.*

class SessionAdapter(
    private val sessions: List<SessionManager.Session>,
    private val activeId: String?,
    private val onSelect: (SessionManager.Session) -> Unit,
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

    override fun getItemCount() = sessions.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = sessions[pos]
        h.title.text = s.title
        h.date.text  = fmt.format(Date(s.createdAt))
        h.itemView.alpha = if (s.id == activeId) 1f else 0.7f
        h.itemView.setOnClickListener { onSelect(s) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.session_title)
        val date:  TextView = view.findViewById(R.id.session_date)
    }
}
