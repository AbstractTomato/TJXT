package com.tianji.agent.domain.dto;

import com.tianji.agent.domain.enums.Confidence;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 流式响应的每个片段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunk {

    /** 本次对话的消息 ID */
    private String messageId;

    /** LLM 生成的内容片段（一个 token） */
    private String content;

    /** 是否已完成 */
    private boolean finished;

    /** 回答自信度（仅在 finished=true 时有意义） */
    private Confidence confidence;

    /** 引用的知识来源（仅在 finished=true 时填充） */
    private String sources;

    private String sessionId;
}
