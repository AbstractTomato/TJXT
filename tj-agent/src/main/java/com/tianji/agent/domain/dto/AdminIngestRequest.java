package com.tianji.agent.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminIngestRequest {

    /**
     * 知识内容
     */
    private String content;

    /**
     * 归属课程 ID
     */
    private Long courseId;

    /**
     * 课程名称
     */
    private String courseName;

    /**
     * 章节标题
     */
    private String chapterTitle;


    private String sourceType;
}
