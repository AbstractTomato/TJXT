package com.tianji.learning.controller;


import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.service.ILearningRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/learning-records")
@Api(tags = "学习计划相关接口")
@RequiredArgsConstructor
public class LearningRecordController {

    private final ILearningRecordService recordService;


    /**
     * 查询指定课程的学习记录
     * @param courseId
     * @return
     */
    @GetMapping("/course/{courseId}")
    @ApiOperation("查询指定课程的学习记录")
    public LearningLessonDTO queryLearningRecordByCourseId(@PathVariable Long courseId){
        return recordService.queryLearningRecordByCourseId(courseId);
    }

    /**
     * 添加学习记录
     * @param formDTO
     */
    @PostMapping
    public void addLearningRecord(@RequestBody LearningRecordFormDTO formDTO){
        recordService.addLearningRecord(formDTO);
    }

}
