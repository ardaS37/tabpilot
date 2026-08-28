package com.ardas.tabletcontroller

import android.content.Context

enum class Control(val preference: String, val defaultKey: String, val label: String) {
    UP("up", "W", "Up"), LEFT("left", "A", "Left"), DOWN("down", "S", "Down"),
    RIGHT("right", "D", "Right"), ACTION("action", "SPACE", "Action / jump")
}

class ControlProfile(context: Context) {
    private val prefs = context.getSharedPreferences("mapping", Context.MODE_PRIVATE)
    fun key(control: Control): String = prefs.getString(control.preference, control.defaultKey) ?: control.defaultKey
    fun set(control: Control, key: String) { prefs.edit().putString(control.preference, key.uppercase()).apply() }
}

