package com.apk.claw.android.agent.llm

interface StreamingListener {
    fun onPartialText(token: String)
    fun onComplete(response: LlmResponse)
    fun onError(error: Throwable)
}
