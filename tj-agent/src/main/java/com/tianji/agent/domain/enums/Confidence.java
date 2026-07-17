package com.tianji.agent.domain.enums;

/**
 * Agent 回答自信度
 */
public enum Confidence {

    /**
     * 高自信度：LLM 有充分依据回答，直接返回给学生
     */
    HIGH,

    /**
     * 中自信度：LLM 有一定依据但不够充分，带免责提示返回
     */
    MEDIUM,

    /**
     * 低自信度：LLM 无法确认答案，自动创建 Question 等讲师回复
     */
    LOW
}
