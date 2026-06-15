package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {

    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;

    /**
     * 思路:
     * 1.获取当前用户信息
     * 2.根据userId和courseId查询learning_lesson表
     * 3.判断是否存在或者是否过期
     *  3.1如果不存在,直接抛异常
     *  3.2如果存在,继续
     * 4.查询lesson对应的所有学习记录
     * 5.封装结果并返回
     */
    @Override
    public LearningLessonDTO queryLearningRecordByCourseId(Long courseId) {

        //1.获取当前用户id
        Long userId = UserContext.getUser();

        //2.根据userId和courseId查询learning_lesson表
        LearningLesson lesson = lessonService.queryByUserAndCourseId(userId, courseId);

        //3.条件判断
        LocalDateTime now = LocalDateTime.now();
        if (lesson == null){
            throw new BizIllegalException("该课程未加入课表!");
        }

        //4.查询对应课表的学习记录
        //select * from learning_record where lesson_id = lessonId;
        List<LearningRecord> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .eq(LearningRecord::getUserId, userId)
                .list();

        //5.封装结果并返回
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));

        return dto;
    }

    /**
     * 添加学习记录
     * @param formDTO
     */
    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO formDTO) {
        Long userId = UserContext.getUser();

        //定义一个变量,表示本次提交是否让本次小节第一次从未学完变成已学完
        boolean finished = false;

        //判断当前提交的是视频小节还是考试小节
        if (formDTO.getSectionType() == SectionType.VIDEO){
            finished = handleVideoRecord(userId, formDTO);
        }else {
            finished = handleExamRecord(userId, formDTO);
        }

        //根据finished的结果来处理课表数据的变化
        if (!finished){
            return;
        }
        handleLearningLessonsChanges(formDTO);
    }


    private void handleLearningLessonsChanges(LearningRecordFormDTO formDTO) {
        //查课表
        LearningLesson learningLesson = lessonService.getById(formDTO.getLessonId());

        //如果课表不存在
        if(learningLesson == null){
            throw new BizIllegalException("课表不存在,无法更新学习进度!");
        }

        //先判断整门课程是否学完
        boolean allFinished = false;

        CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(
                    learningLesson.getCourseId(),
                    false,
                    false
        );
        if (courseInfo == null){
            throw new BizIllegalException("课程不存在,无法更新学习进度!");
        }
        //如果学习的小节数 > 该课程的所有小节数 ,表明该门课程已学完
        allFinished = learningLesson.getLearnedSections() + 1 >= courseInfo.getSectionNum();


        boolean success = lessonService.lambdaUpdate()
                //如果已学习小节数为0,表明是刚开始学习,此时要更改学习状态
                .set(learningLesson.getStatus() == LessonStatus.NOT_BEGIN,
                        LearningLesson::getStatus,
                        LessonStatus.LEARNING)
                //如果整个课程已经学完,更改课程状态
                .set(allFinished,
                        LearningLesson::getStatus,
                        LessonStatus.FINISHED)
                //如果本次是第一次学完一个新小节,已学小节数 + 1
                .setSql("learned_sections = learned_sections + 1")
                //根据课表id更新
                .eq(LearningLesson::getId, learningLesson.getId())
                .update();

        if (!success){
            throw new DbException("更新课表学习进度失败!");
        }


    }

    /**
     * 如果是考试,则直接改成已学完
     * @param userId
     * @param formDTO
     * @return
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO formDTO) {
        LearningRecord learningRecord = BeanUtils.copyBean(formDTO, LearningRecord.class);

        learningRecord.setUserId(userId);
        learningRecord.setFinished(true);
        learningRecord.setFinishTime(formDTO.getCommitTime());

        boolean success = save(learningRecord);

        if (!success){
            throw new DbException("新增考试学习记录失败!");
        }

        return true;
    }


    /**
     * 思路:
     * 1.首先判断有没有学习记录,
     *  1.1 如果没有学习记录,则添加一条学习记录并返回false
     *  1.2 如果有学习记录,判断是不是第一次学完
     *      1.2.1 如果是第一次学完,则更新 finished 和 finishTime, 并返回true
     *      1.2.2 如果不是第一次学完, 只更新播放进度, 返回false
     * @param userId
     * @param formDTO
     * @return
     */
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO formDTO) {
        //查询旧的视频记录,条件是同一个课表id + 同一个小节id
        LearningRecord oldRecord = queryOldRecord(formDTO.getLessonId(), formDTO.getSectionId());
        //判断是否为空
        if (oldRecord == null){
            //表明是第一次学习
            //把前端传来的DTO转换成PO
            LearningRecord record = BeanUtils.copyBean(formDTO, LearningRecord.class);

            //新增学习记录到learning_record表中
            record.setUserId(userId);
            boolean success = save(record);

            //如果保存失败,抛出异常
            if (!success){
                throw new DbException("新增视频学习记录失败!");
            }

            return false;
        }

        //如果已经存在记录,判断此时是不是第一次学完
        //进行条件判断:旧记录之前没有完成 && 当前观看进度达到视频时长的一半
        boolean finished = !Boolean.TRUE.equals(oldRecord.getFinished()) && 2 * formDTO.getMoment() >= formDTO.getDuration();

        if(!finished){
            LearningRecord record = new LearningRecord();
            record.setLessonId(formDTO.getLessonId());
            record.setSectionId(formDTO.getSectionId());
            record.setMoment(formDTO.getMoment());
            record.setId(oldRecord.getId());
            record.setFinished(oldRecord.getFinished());

            taskHandler.addLearningRecordTask(record);

            return false;
        }

        //更新旧的视频学习记录
        boolean success = lambdaUpdate()
                //记录当前视频的观看时间
                .set(LearningRecord::getMoment, formDTO.getMoment())
                //如果是第一次学完,则更新PO中的finished为true
                .set(LearningRecord::getFinished, true)
                //如果是第一次学完,则记录提交时间
                .set(LearningRecord::getFinishTime, formDTO.getCommitTime())
                //根据旧记录上的主键id更新这一条数据
                .eq(LearningRecord::getId, oldRecord.getId())
                .update();

        if (!success){
            throw new DbException("更新视频学习记录失败!");
        }

        //清理缓存
        taskHandler.cleanRecordCache(formDTO.getLessonId(), formDTO.getSectionId());
        return true;
    }


    /**
     * 根据课表id和小节id查询学习记录
     * @param lessonId
     * @param sectionId
     * @return
     */
    private LearningRecord queryOldRecord(Long lessonId, Long sectionId){
        //1.先查缓存
        LearningRecord record = taskHandler.readRecordCache(lessonId, sectionId);
        //2.如果命中,直接返回
        if (record != null){
            return record;
        }
        //3.未命中,查询数据库
        record = lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        //4.写入缓存
        taskHandler.writeRecordIntoCache(record);

        return record;
    }
}
