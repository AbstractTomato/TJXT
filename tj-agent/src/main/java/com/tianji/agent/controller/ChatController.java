package com.tianji.agent.controller;

import com.tianji.agent.domain.dto.ChatChunk;
import com.tianji.agent.domain.dto.ChatRequest;
import com.tianji.agent.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * Agent 对话接口
 * <p>
 * 使用 SSE（Server-Sent Events）流式输出，学生看到的是"一个字一个字蹦出来"的效果。
 */
@RestController
@RequestMapping("/agent/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 流式对话接口（SSE）
     *
     * @param request 学生的问题和上下文
     * @return SSE 流，每个事件是一个 ChatChunk
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatChunk> chatStream(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }
}
