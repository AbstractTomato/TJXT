package com.tianji.api.dto.learning;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 互动回答 DTO（Feign 通信用，字段对齐 tj-learning 的 ReplyVO）
 */
@Data
public class ReplyDTO {

    /** id */
    private Long id;

    /** 回答内容 */
    private String content;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 当前回复者id */
    private Long userId;

    /** 当前回复者昵称 */
    private String userName;

    /** 当前回复者头像 */
    private String userIcon;

    /** 当前回复者类型，2-学员，其它-老师 */
    private Integer userType;

    /** 点赞数量 */
    private Integer likedTimes;

    /** 评论数量 */
    private Integer replyTimes;

    /** 是否被隐藏 */
    private Boolean hidden;
}
