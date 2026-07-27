package com.tianji.agent.service;

import com.tianji.agent.domain.dto.RetrievalResult;

import java.util.List;

/**
 * 知识库检索服务。
 * <p>
 * 混合检索管线：术语标准化 → embedding 缓存 → 向量检索 + 关键词检索 → RRF 融合排序。
 * 为 Phase 4 的对话生成提供 Top-K 相关文档块及其 RRF 分数（用于自信度判定）。
 */
public interface RetrievalService {

    /**
     * 根据学生问题检索最相关的知识库文档块。
     *
     * @param question 学生问题（原始输入）
     * @param courseId 课程 ID（可选，为 null 则跨课程检索）
     * @param topK     返回的最大文档块数
     * @return 按 RRF 分数降序排列的检索结果（含文档块 + 分数）
     */
    List<RetrievalResult> retrieve(String question, Long courseId, int topK);
}
