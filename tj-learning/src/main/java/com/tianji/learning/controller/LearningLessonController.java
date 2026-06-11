package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.service.ILearningLessonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


/**
 * <p>
 * 学生课程表 前端控制器
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-10
 */
@RestController
@RequestMapping("/lessons")
@Api(tags = "我的课表相关接口")
@RequiredArgsConstructor
public class LearningLessonController {

    private final ILearningLessonService lessonService;

    /**
     * 分页查询相关接口
     * @param query
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("分页查询相关接口")
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query){
        return lessonService.queryMyLessons(query);
    }

    /**
     * 根据id查询指定课程的学习状态
     * @param courseId
     * @return
     */
    @GetMapping("/{courseId}")
    @ApiOperation("根据id查询指定课程的学习状态")
    public LearningLessonVO queryLessonStatusByCourseId(@PathVariable("courseId") Long courseId){
        return lessonService.queryLessonStatusByCourseId(courseId);
    }

    /**
     * 根据课程id删除指定课程
     * @param courseId
     */
    @DeleteMapping("/{courseId}")
    @ApiOperation("根据课程id删除指定课程")
    public void deleteCourseFromLesson(@PathVariable("courseId") Long courseId){
        lessonService.deleteCourseFromLesson(null, courseId);
    }

    /**
     * 校验指定课程是否是课表中的有效数据
     * @param courseId
     * @return
     */
    @GetMapping("/{courseId}/valid")
    @ApiOperation("校验指定课程是否是课表中的有效数据")
    public Long isLessonValid(@PathVariable("courseId") Long courseId){
        return lessonService.isLessonValid(courseId);
    }

    /**
     * 统计该课程的学习人数
     * @param courseId
     * @return
     */
    @GetMapping("/{courseId}/count")
    public Integer countLearningPersonByCourse(@PathVariable("courseId") Long courseId){
        return lessonService.countLearningPersonByCourse(courseId);
    }
}
