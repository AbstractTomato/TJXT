package com.tianji.learning.domain.po;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;


@Data
@TableName("learning_record")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class LearningRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 课程id
     */
    private Long lessonId;

    /**
     * 小节id
     */
    private Long sectionId;

    /**
     * 当前视频的观看时长
     */
    private Integer moment;

    /**
     * 该小节是否学习完
     */
    private Boolean finished;

    /**
     * 课程的创建时间
     */
    private LocalDateTime createTime;

    /**
     * 课程的观看完对应的时间
     */
    private LocalDateTime finishTime;

    /**
     * 最近一次观看的时间
     */
    private LocalDateTime updateTime;

}
