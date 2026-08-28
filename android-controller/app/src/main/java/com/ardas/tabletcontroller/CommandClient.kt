package com.ardas.tabletcontroller

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Sends line-based commands through the local ADB reverse endpoint. */
class CommandClient {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var running = true
    @Volatile var connected = false
        private set

    init {
        thread(name = "controller-network", isDaemon = true) {
            while (running) {
                try {
                    Socket("127.0.0.1", 27183).use { socket ->
                        BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { writer ->
                            connected = true
                            while (running && !socket.isClosed) {
                                writer.write(queue.take())
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    connected = false
                    Thread.sleep(1000)
                }
            }
        }
    }

    fun send(command: String) { if (running) queue.offer(command) }
    fun close() { running = false; queue.offer("disconnect") }
}

