package com.tianji.learning.mapper;

import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 学生课程表 Mapper 接口
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-10
 */
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {
    Integer queryTotalPlan(@Param("userId") Long userId);
}
