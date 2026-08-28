package com.ardas.tabletcontroller

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var client: CommandClient
    private lateinit var surface: ControlSurface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = CommandClient()
        val root = FrameLayout(this)
        surface = ControlSurface(this, client)
        root.addView(surface)
        setContentView(root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { surface.nextMode(); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { surface.previousMode(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { client.close(); super.onDestroy() }
}
