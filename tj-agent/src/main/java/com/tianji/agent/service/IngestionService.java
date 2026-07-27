package com.tianji.agent.service;

/**
 * 知识库入库服务
 * <p>
 * 负责将课程内容（字幕、讲义、问答对）转换成向量并存入 ES dense_vector，
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


    /**
     * 手动录入知识内容（管理员直接提交文本，不走 Feign 拉取）
     *
     * @param content      知识文本内容
     * @param courseId     归属课程 ID
     * @param courseName   课程名称
     * @param chapterTitle 章节标题（可为 null）
     */
    void ingestManual(String content, Long courseId, String courseName, String chapterTitle, String sourceType);
}
