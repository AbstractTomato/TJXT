package com.tianji.agent.service;

/**
 * 知识库入库服务
 * <p>
 * 负责将课程内容（字幕、讲义、问答对）转换成向量并存入 Redis，
 * 同时维护 ES 关键词索引和 MySQL 元数据。
 */
public interface IngestionService {

    /**
     * 将一门课程的全部内容入库
     *
     * @param courseId 课程 ID
     */
    void ingestCourse(Long courseId);

    /**
     * 将单个问答对入库（讲师回复后自动同步）
     *
     * @param questionId 问题 ID
     */
    void ingestQA(Long questionId);
}
