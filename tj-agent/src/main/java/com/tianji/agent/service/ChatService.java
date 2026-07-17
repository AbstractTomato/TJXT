package com.tianji.agent.service;

import com.tianji.agent.domain.dto.ChatChunk;
import com.tianji.agent.domain.dto.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * 智能对话服务 —— 🦉 夜猫子助教核心逻辑
 * <p>
 * 流程：理解问题 → 检索知识 → LLM 生成回答 → 自信度判断 → 兜底
 */
public interface ChatService {

    /**
     * 处理学生的提问，返回 SSE 流式回答
     *
     * @param request 问题 + 上下文（课程 ID、会话 ID）
     * @return 流式回答片段
     */
    Flux<ChatChunk> chat(ChatRequest request);
}
