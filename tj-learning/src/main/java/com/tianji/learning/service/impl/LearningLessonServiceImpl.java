package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional //批量处理数据
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

}
