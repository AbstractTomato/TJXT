-- ============================================
-- Agent 知识库文档块表
-- 数据库: tj_agent
-- 说明: 存储课程内容和问答对的文本块（向量存 ES dense_vector，文本存此表）
-- ============================================

CREATE TABLE IF NOT EXISTS agent_doc_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    content       TEXT         NOT NULL COMMENT '文本内容',
    course_id     BIGINT       NOT NULL COMMENT '来源课程ID',
    course_name   VARCHAR(255) COMMENT '来源课程名称',
    chapter_title VARCHAR(255) COMMENT '章节标题',
    source_type   VARCHAR(32)  COMMENT '来源类型: CHAPTER(章节名), QA(问答对)',
    source_id     BIGINT       COMMENT '原始数据业务ID（questionId 或 catalogue sectionId）',
    vector_key    VARCHAR(255) COMMENT '向量存储key（已废弃，Phase 3迁移至ES dense_vector，不再写入新值）',
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_course_id (course_id),
    INDEX idx_source_type (source_type),
    UNIQUE INDEX idx_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent知识库文档块';
