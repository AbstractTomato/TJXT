package com.tianji.agent.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 知识库文档块
 * <p>
 * 每个块对应课程内容（字幕/讲义）的一个片段，约 500~800 字。
 * 文本内容存 MySQL，向量存 Redis，关键词索引存 ES。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_doc_chunk")
public class DocChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文本内容 */
    private String content;

    /** 来源课程 ID */
    private Long courseId;

    /** 来源课程名称 */
    private String courseName;

    /** 章节标题 */
    private String chapterTitle;

    /** 来源类型：SUBTITLE（字幕）、QA（问答对）、EXAM（题目解析） */
    private String sourceType;

    /** 原始数据的业务 ID（如 subtitleId、questionId） */
    private Long sourceId;

    /** Redis 中向量存储的 key */
    private String vectorKey;

    /** 创建时间 */
    private LocalDateTime createTime;
}
