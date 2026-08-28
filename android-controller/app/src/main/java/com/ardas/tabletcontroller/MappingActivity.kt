package com.ardas.tabletcontroller

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class MappingActivity : Activity() {
    private val choices = arrayOf("W", "A", "S", "D", "SPACE", "SHIFT", "CTRL", "E", "F", "Q", "R", "1", "2", "3")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val profile = ControlProfile(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        Control.entries.forEach { control ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text = control.label; textSize = 20f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Spinner(this).apply {
                adapter = ArrayAdapter(this@MappingActivity, android.R.layout.simple_spinner_dropdown_item, choices)
                setSelection(choices.indexOf(profile.key(control)).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { profile.set(control, choices[position]) }
                }
            })
            layout.addView(row)
        }
        layout.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        setContentView(layout)
    }
}

