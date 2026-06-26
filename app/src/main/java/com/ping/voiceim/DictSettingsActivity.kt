package com.ping.voiceim

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
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DictSettingsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DictAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dict_settings)

        supportActionBar?.title = "自定義詞彙"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recycler = findViewById(R.id.rv_dict)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        adapter = DictAdapter(loadEntries()) { from ->
            UserDictionary.remove(this, from)
            adapter.update(loadEntries())
        }
        recycler.adapter = adapter

        findViewById<View>(R.id.btn_add_entry).setOnClickListener { showAddDialog() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun loadEntries() = UserDictionary.load(this).entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_entry, null)
        val etFrom = view.findViewById<EditText>(R.id.et_from)
        val etTo   = view.findViewById<EditText>(R.id.et_to)

        AlertDialog.Builder(this)
            .setTitle("新增詞彙替換")
            .setView(view)
            .setPositiveButton("新增") { _, _ ->
                val from = etFrom.text.toString().trim()
                val to   = etTo.text.toString().trim()
                if (from.isBlank() || to.isBlank()) {
                    Toast.makeText(this, "請填寫兩個欄位", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                UserDictionary.add(this, from, to)
                adapter.update(loadEntries())
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
            val tvFrom: TextView    = view.findViewById(R.id.tv_from)
            val tvTo: TextView      = view.findViewById(R.id.tv_to)
            val btnDel: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_dict_entry, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (from, to) = items[position]
            holder.tvFrom.text = from
            holder.tvTo.text   = to
            holder.btnDel.setOnClickListener { onDelete(from) }
        }
    }
}
