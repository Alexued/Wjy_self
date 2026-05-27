package com.apk.claw.android.channel.local

import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelHandler
import com.apk.claw.android.utils.KVUtils

class LocalChannelHandler : ChannelHandler {

    override val channel: Channel = Channel.LOCAL

    override fun isConnected(): Boolean = true

    override fun init() = Unit

    override fun disconnect() = Unit

    override fun reinitFromStorage() = Unit

    override fun sendMessage(content: String, messageID: String) {
        KVUtils.appendLocalTaskResult(messageID, content)
        notifyHistoryChanged()
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        KVUtils.appendLocalTaskResult(messageID, "[Image: ${imageBytes.size} bytes]")
        notifyHistoryChanged()
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        KVUtils.appendLocalTaskResult(messageID, "[File: ${file.name}]")
        notifyHistoryChanged()
    }

    override fun flushMessages() {
        notifyHistoryChanged()
    }

    override fun getLastSenderId(): String = LOCAL_USER_ID

    override fun sendMessageToUser(userId: String, content: String) {
        sendMessage(content, userId)
    }

    private fun notifyHistoryChanged() {
        onHistoryChanged?.invoke()
    }

    companion object {
        const val LOCAL_USER_ID = "local"
        var onHistoryChanged: (() -> Unit)? = null
    }
}
