package com.tianji.api.dto.learning;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 互动问题 DTO（Feign 通信用，字段对齐 tj-learning 的 QuestionVO）
 */
@Data
public class QuestionDTO {

    /** 所属课程id */
    private Long courseId;

    /** 所属课程章id */
    private Long chapterId;

    /** 所属课程节id */
    private Long sectionId;

    /** 主键id */
    private Long id;

    /** 互动问题名称 */
    private String title;

    /** 互动问题描述 */
    private String description;

    /** 回答数量，0表示没有回答 */
    private Integer answerTimes;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 提问者id */
    private Long userId;

    /** 提问者昵称 */
    private String userName;

    /** 提问者头像 */
    private String userIcon;

    /** 最新的回答信息 */
    private String latestReplyContent;

    /** 最新的回答者昵称 */
    private String latestReplyUser;
}
