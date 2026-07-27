package com.tianji.agent.domain.dto;

import com.tianji.agent.domain.po.DocChunk;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 检索结果——文档块 + RRF 融合分数。
 * <p>
 * 与 {@link DocChunk} 分离：DocChunk 是 MyBatis-Plus 实体，
 * 加分数字段会污染 MySQL 映射和 ES 文档。RetrievalResult 仅存在于检索层内存中。
 */
@Data
@AllArgsConstructor
public class RetrievalResult {

    /** 文档块 */
    private DocChunk chunk;

    /** RRF 融合分数（范围约 [0, 0.1]，用于自信度判定） */
    private double rrfScore;
}
