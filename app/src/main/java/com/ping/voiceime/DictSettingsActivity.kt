package com.ping.voiceime

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class DictSettingsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: DictAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dict_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        emptyState = findViewById(R.id.ll_empty_state)
        recycler = findViewById(R.id.rv_dict)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = DictAdapter(loadEntries()) { from ->
            UserDictionary.remove(this, from)
            refreshList()
        }
        recycler.adapter = adapter
        refreshList()

        findViewById<View>(R.id.btn_add_entry).setOnClickListener { showAddDialog() }
    }

    private fun refreshList() {
        val entries = loadEntries()
        adapter.update(entries)
        emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadEntries() = UserDictionary.load(this).entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_entry, null)
        val etTo = view.findViewById<EditText>(R.id.et_to)

        AlertDialog.Builder(this)
            .setTitle("新增專屬詞彙")
            .setView(view)
            .setPositiveButton("新增") { _, _ ->
                val to = etTo.text.toString().trim()
                if (to.isBlank()) {
                    Toast.makeText(this, "請輸入詞彙內容", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                UserDictionary.add(this, to, to)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class DictAdapter(
        private var items: List<Pair<String, String>>,
        private val onDelete: (String) -> Unit,
    ) : RecyclerView.Adapter<DictAdapter.VH>() {

        fun update(newItems: List<Pair<String, String>>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTo: TextView      = view.findViewById(R.id.tv_to)
            val btnDel: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_dict_entry, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (from, to) = items[position]
            holder.tvTo.text = to
            holder.btnDel.setOnClickListener { onDelete(from) }
        }
    }
}
