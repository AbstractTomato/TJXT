package com.tianji.api.client.learning;

import com.tianji.api.client.learning.fallback.LearningClientFallback;
import com.tianji.api.dto.learning.QuestionDTO;
import com.tianji.api.dto.learning.QuestionFormDTO;
import com.tianji.api.dto.learning.ReplyDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.common.domain.dto.PageDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(value = "learning-service", url = "${tj.agent.learning-service.url:http://learning-service:8090}", fallbackFactory = LearningClientFallback.class)
public interface LearningClient {

    /**
     * 统计课程学习人数
     * @param courseId 课程id
     * @return 学习人数
     */
    @GetMapping("/lessons/{courseId}/count")
    Integer countLearningLessonByCourse(@PathVariable("courseId") Long courseId);

    /**
     * 校验当前用户是否可以学习当前课程
     * @param courseId 课程id
     * @return lessonId，如果是报名了则返回lessonId，否则返回空
     */
    @GetMapping("/lessons/{courseId}/valid")
    Long isLessonValid(@PathVariable("courseId") Long courseId);

    /**
     * 查询当前用户指定课程的学习进度
     * @param courseId 课程id
     * @return 课表信息、学习记录及进度信息
     */
    @GetMapping("/learning-records/course/{courseId}")
    LearningLessonDTO queryLearningRecordByCourse(@PathVariable("courseId") Long courseId);

    // ============ 互动问答接口（供 tj-agent 知识库入库使用）============

    /**
     * 分页查询互动问题（按课程ID过滤）
     * @param courseId 课程id
     * @param pageNo 页码，默认1
     * @param pageSize 每页大小，默认100
     * @return 互动问题分页数据（含最新回答）
     */
    @GetMapping("/questions/page")
    PageDTO<QuestionDTO> queryQuestionPage(
            @RequestParam("courseId") Long courseId,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "100") Integer pageSize
    );

    /**
     * 根据ID查询互动问题详情（含最新回答内容）
     * @param id 问题id
     * @return 互动问题详情
     */
    @GetMapping("/questions/{id}")
    QuestionDTO queryQuestionById(@PathVariable("id") Long id);

    /**
     * 分页查询某个问题的回答列表（含评论）
     * @param questionId 问题id
     * @param pageNo 页码，默认1
     * @param pageSize 每页大小，默认20
     * @return 回答分页数据
     */
    @GetMapping("/replies/page")
    PageDTO<ReplyDTO> queryReplyPage(
            @RequestParam("questionId") Long questionId,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize
    );

    @PostMapping("/questions")
    void createQuestion(@RequestBody QuestionFormDTO dto);

}
