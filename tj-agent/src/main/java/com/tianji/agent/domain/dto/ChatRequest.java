package com.tianji.agent.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学生提问请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 学生的问题 */
    private String question;

    /** 当前课程 ID（可选，限定知识检索范围） */
    private Long courseId;

    /** 会话 ID（多轮对话时用于上下文记忆） */
    private String sessionId;
}
