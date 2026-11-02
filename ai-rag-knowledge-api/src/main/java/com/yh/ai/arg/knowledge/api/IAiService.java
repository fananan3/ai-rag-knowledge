package com.yh.ai.arg.knowledge.api;

import org.springframework.ai.chat.ChatResponse;
import reactor.core.publisher.Flux;

public interface IAiService {
    ChatResponse generate(String model, String message);

    Flux<ChatResponse> generateStream(String model, String message);

    /**
     * 基于知识库检索（RAG）的对话生成：先按 ragTag 检索向量库，把命中的文档拼进 Prompt 再调用模型
     *
     * @param model   模型名称
     * @param message 用户问题
     * @param ragTag  知识库标签，为空时退化为普通对话
     */
    ChatResponse generateWithRag(String model, String message, String ragTag);

    /**
     * 基于知识库检索（RAG）的流式对话生成
     */
    Flux<ChatResponse> generateWithRagStream(String model, String message, String ragTag);
}
