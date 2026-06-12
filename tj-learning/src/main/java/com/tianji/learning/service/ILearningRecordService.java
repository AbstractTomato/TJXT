package com.tianji.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningRecord;

import javax.validation.Valid;

public interface ILearningRecordService extends IService<LearningRecord> {

    //查询学习记录
    LearningLessonDTO queryLearningRecordByCourseId(Long courseId);

    //添加学习记录
    void addLearningRecord(LearningRecordFormDTO formDTO);
}
