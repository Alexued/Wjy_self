package com.apk.claw.android.agent.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

interface LlmClient {
    /** Blocking call. Returns the complete AI response. */
    fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse

    /** Streaming call. Invokes listener callbacks as tokens arrive. Blocks until stream completes. */
    fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse
}
