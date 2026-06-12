package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.*;
import com.tianji.learning.domain.dto.LearningPlanDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.PlanStatus;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import java.sql.Wrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-10
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {

    private final CourseClient courseClient;

    private final LearningRecordMapper recordMapper;

    /**
     * 批量处理数据
     */
    @Override
    @Transactional
    public void addUserLessons(Long userId, List<Long> courseIds) {

        //1.首先需要根据课程id拿到课程数据信息
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(courseIds);
        //判断是否为空
        if (cInfoList == null || cInfoList.isEmpty()){
            log.error("课程信息不存在,无法添加到课表");
            return;
        }
        //2.循环遍历,处理LearningLesson数据
        List<LearningLesson> list = new ArrayList<>(cInfoList.size());

        for (CourseSimpleInfoDTO cInfo : cInfoList) {
            LearningLesson lesson = new LearningLesson();

            //2.1获取当前课程的过期时间(月)
            Integer validDuration = cInfo.getValidDuration();
            //2.2获取当前时间
            LocalDateTime now = LocalDateTime.now();
            lesson.setCreateTime(now); //课程的开始时间

            if (validDuration != null && validDuration > 0){ //需要判断该课程是否有过期时间
                //2.3计算课程的过期时间
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            //2.4填充userId和courseId
            lesson.setUserId(userId);
            lesson.setCourseId(cInfo.getId());

            list.add(lesson);
        }

        //3.批量保存数据进数据库
        saveBatch(list);
    }

    /**
     * 分页查询课程数据
     */
    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {

        //1.获取当前用户id
        //用户的信息存在usercontext之中
        Long userId = UserContext.getUser();


        //2.根据用户id进行分页查询(PO)得到对应的课程信息
        // select * from learning_lessons where user_id = #{userId} order by latest_learn_time limit 0, 5;
        Page<LearningLesson> page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .page(query.toMpPage("latest_learn_time", false));

        List<LearningLesson> records = page.getRecords();//查询到的只是LearningLesson,不是LearningLessonVO
        //健壮性检查
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }


        //3.查询课程信息. LearningLessonVO与PO有些不一样,需要额外的信息,
        //3.1从records中拿到courseId, 用set收集,防止课程id重复
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());

        //3.2查询得到课程信息的集合
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);

        //3.3把课程集合list转换成map,方便封装VO对象.
        //map的key是courseId,value是自己本身
        Map<Long, CourseSimpleInfoDTO> cInfoMap = cInfoList.stream()
                .collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));

        //3.3健壮性检查
        if (CollUtils.isEmpty(cInfoList)){
            throw new BadRequestException("课程信息不存在!");
        }


        //4.封装VO信息返回
        //提前规定好大小,避免后续扩容
        List<LearningLessonVO> list = new ArrayList<>(records.size());

        //根据cInfoMap拿到VO中的其他信息
        for (LearningLesson record : records) {
            //4.1将LearningLesson与LearningLessonVO中相同的字段拷贝,先把PO转换成VO
            LearningLessonVO learningLessonVO = BeanUtils.copyBean(record, LearningLessonVO.class);
            //4.2获取当前课程的封面url, name和sections
            String coverUrl = cInfoMap.get(record.getCourseId()).getCoverUrl();
            String name = cInfoMap.get(record.getCourseId()).getName();
            Integer sectionNum = cInfoMap.get(record.getCourseId()).getSectionNum();
            //4.3将获取的信息填充到LearningLessonVO中
            learningLessonVO.setCourseCoverUrl(coverUrl);
            learningLessonVO.setCourseName(name);
            learningLessonVO.setSections(sectionNum);

            list.add(learningLessonVO);
        }

        return new PageDTO<>(page.getTotal(), page.getPages(), list);
    }

    /**
     * 根据课程id查询课程状态
     * 需要根据用户id和课程id来进行确认.一个用户可以有多个课程,一个课程可被多个用户购买
     */
    @Override
    public LearningLessonVO queryLessonStatusByCourseId(Long courseId) {
        //拿到用户信息
        Long userId = UserContext.getUser();
        LearningLesson lesson = lambdaQuery()
                //条件1,根据用户id判断
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null){
            return null;
        }

        //将LearningLesson对象lesson转换成VO对象,并返回
        return BeanUtils.copyBean(lesson, LearningLessonVO.class);
    }

    /**
     * 根据课程id删除当前用户的指定课程
     * @param userId
     * @param courseId
     */
    @Override
    public void deleteCourseFromLesson(Long userId , Long courseId) {
        if (userId == null){
            //如果controller传入的是null,说明是用户主动删除的
            //从threadlocal中获取用户信息
            userId = UserContext.getUser();
        }

        //根据条件删除用户指定的课程
        remove(Wrappers.<LearningLesson>lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId));

    }

    /**
     * 校验指定课程是否是课表中的有效数据
     * @param courseId
     * @return
     */
    @Override
    public Long isLessonValid(Long courseId) {
        //拿到用户信息
        Long userId = UserContext.getUser();

        //查询得到当前用户的课表
        LearningLesson lesson = getOne(buildUserIdAndCourseIdWrapper(userId, courseId));

        if (lesson == null){
            return null;
        }

        //判断是否过期
        LocalDateTime expireTime = lesson.getExpireTime();
        LocalDateTime now = LocalDateTime.now();

        if (expireTime != null && now.isAfter(expireTime)){
            //证明过期
            return null;
        }

        return lesson.getId();
    }

    /**
     * 统计该课程的学习人数
     * @param courseId
     * @return
     */
    @Override
    public Integer countLearningPersonByCourse(Long courseId) {
        return lambdaQuery()
                .eq(LearningLesson::getCourseId, courseId)
                .in(LearningLesson::getStatus,
                        LessonStatus.NOT_BEGIN.getValue(),
                        LessonStatus.LEARNING.getValue(),
                        LessonStatus.FINISHED.getValue())
                .count();
    }

    /**
     * 创建学习计划,也就是更新learning_lesson表,写入weekFreq,并更新planStatus
     * @param courseId
     * @param freq
     */
    @Override
    public void createLearningPlans(Long courseId, Integer freq) {
        //todo 创建学习计划
        //拿到用户信息
        Long userId = UserContext.getUser();

        //拿到课程信息
        LearningLesson lesson = queryByUserAndCourseId(userId, courseId);

        AssertUtils.isNotNull(lesson, "课程信息不存在!");

        /*LearningLesson l = new LearningLesson();

        l.setId(lesson.getId());
        l.setWeekFreq(freq);

        if (lesson.getPlanStatus() == PlanStatus.NO_PLAN){
            l.setPlanStatus(PlanStatus.PLAN_RUNNING);
        }

        updateById(l);*/

        //另一种写法,明确告诉数据库我只更新这两条数据
        lambdaUpdate()
                .set(LearningLesson::getWeekFreq, freq)
                .set(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .eq(LearningLesson::getId, lesson.getId())
                .update();

    }

    /**
     * 查询学习计划
     * @param query
     * @return
     */
    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        LearningPlanPageVO result = new LearningPlanPageVO();

        Long userId = UserContext.getUser();

        //获取本周的起始时间
        LocalDate now = LocalDate.now();
        LocalDateTime begin = DateUtils.getWeekBeginTime(now);
        LocalDateTime end = DateUtils.getWeekEndTime(now);

        //查询总的统计数
        //1.本周总的已学习小节数量
        Integer weekFinished = recordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, true)
                .gt(LearningRecord::getFinishTime, begin)
                .lt(LearningRecord::getFinishTime, end)
        );
        result.setWeekFinished(weekFinished);

        //2.本周总的计划学习小节数量
        Integer weekTotalPlan = getBaseMapper().queryTotalPlan(userId);
        result.setWeekTotalPlan(weekTotalPlan);

        //todo 学习积分

        //4.查询分页数据
        Page<LearningLesson> page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .page(query.toMpPage("latest_learn_time", false));

        List<LearningLesson> records = page.getRecords();

        if (CollUtils.isEmpty(records)){
            return result.pageInfo(PageDTO.empty(page));
        }

        //查询课表对应的信息
        Map<Long, CourseSimpleInfoDTO> cMap = queryCourseSimpleInfoList(records);
        //统计每一个课程本周已学习小节的数量
        List<IdAndNumDTO> list = recordMapper.countLearnedSections(userId, begin, end);
        Map<Long, Integer> countMap = IdAndNumDTO.toMap(list);

        //组装数据VO
        List<LearningPlanVO> voList = new ArrayList<>(records.size());
        for (LearningLesson record : records) {
            //基础属性拷贝
            LearningPlanVO vo = BeanUtils.copyBean(record, LearningPlanVO.class);
            //填充课程详细信息
            CourseSimpleInfoDTO courseSimpleInfoDTO = cMap.get(record.getCourseId());
            if (courseSimpleInfoDTO != null){
                vo.setCourseName(courseSimpleInfoDTO.getName());
                vo.setSections(courseSimpleInfoDTO.getSectionNum());
            }

            //每个课程的本周已学习小节数量
            vo.setWeekLearnedSections(countMap.getOrDefault(record.getId(), 0));
            voList.add(vo);
        }

        return result.pageInfo(page.getTotal(), page.getPages(), voList);
    }

    @Override
    public LearningLesson queryByUserAndCourseId(Long userId, Long courseId) {
        return getOne(buildUserIdAndCourseIdWrapper(userId, courseId));
    }

    /**
     * 根据learningLesson的集合,查询到课程id,并且封装成map集合
     * @param records
     * @return
     */
    private Map<Long, CourseSimpleInfoDTO> queryCourseSimpleInfoList(List<LearningLesson> records){
        //1.获取课程id的set集合
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());

        //2.查询课程信息
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);

        if (CollUtils.isEmpty(cInfoList)){
            throw new BadRequestException("课程信息不存在!");
        }

        //3.把课程处理成map, key是courId,value是其本身
        Map<Long, CourseSimpleInfoDTO> cMap = cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));

        return cMap;
    }


    /**
     * 私有方法,经常会需要userId和courseId,将它俩的查询条件对象进行返回
     * @param userId
     * @param courseId
     * @return
     */
    private LambdaQueryWrapper<LearningLesson> buildUserIdAndCourseIdWrapper(Long userId, Long courseId){
        return  Wrappers.<LearningLesson>lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId);
    }


}
