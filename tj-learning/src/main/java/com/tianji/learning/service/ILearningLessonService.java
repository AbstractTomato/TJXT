package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.LearningLessonVO;

import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-10
 */
public interface ILearningLessonService extends IService<LearningLesson> {

    //添加课程
    void addUserLessons(Long userId, List<Long> courseIds);

    //分页查询
    PageDTO<LearningLessonVO> queryMyLessons(PageQuery query);

    //根据课程id查询当前用户的课程的学习状态
    LearningLessonVO queryLessonStatusByCourseId(Long courseId);

    //根据课程id删除当前用户的课程
    void deleteCourseFromLesson(Long userId, Long courseId);

    //校验指定课程是否是课表中的有效数据
    Long isLessonValid(Long courseId);

    //统计该课程的学习人数
    Integer countLearningPersonByCourse(Long courseId);
}
