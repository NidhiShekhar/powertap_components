// kotlin
package com.example.powertap

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer

class LogActivity : AppCompatActivity() {
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val listView = findViewById<ListView>(R.id.logListView)
        val clearButton = findViewById<Button>(R.id.clearButton)

        // custom adapter that makes the item TextView multiline and wraps text so long logs are visible
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.isSingleLine = false
                tv.setHorizontallyScrolling(false)
                tv.maxLines = Integer.MAX_VALUE
                return v
            }
        }

        listView.adapter = adapter

        LogRepository.logs.observe(this, Observer { list ->
            adapter.clear()
            adapter.addAll(list)
            adapter.notifyDataSetChanged()
            listView.post { listView.smoothScrollToPosition(0) } // keep latest on top
        })

        clearButton.setOnClickListener {
            LogRepository.clear()
        }
    }
}