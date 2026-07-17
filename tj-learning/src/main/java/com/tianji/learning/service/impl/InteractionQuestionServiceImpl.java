package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final IInteractionReplyService replyService;
    private final UserClient userClient;
    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;


    /**
     * 新增一个互动问题
     * @param questionDTO
     */
    @Override
    public void newQuestion(QuestionFormDTO questionDTO) {
        //1.获取当前登录用户id
        Long userId = UserContext.getUser();
        //2.数据封装
        InteractionQuestion question = BeanUtils.copyBean(questionDTO, InteractionQuestion.class);
        question.setUserId(userId);
        //3.写入数据库
        save(question);
    }


    /**
     * 分页查询互动问题
     * @param query
     * @return
     */
    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        //1.参数校验,课程id和小节id不能同时为空
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if(courseId == null && sectionId == null){
            throw new BadRequestException("课程id和小节id不能同时为空!");
        }

        //2.分页查询
        Page<InteractionQuestion> page = lambdaQuery()
                //不需要数据库中description这个字段,这个字段是问题描述字段,很占内存
                .select(InteractionQuestion.class, info -> !info.getProperty().equals("description"))
                //是否只查当前用户自己提问的问题
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, UserContext.getUser())
                //查询当前课程的问题
                .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                //查询课程这个小节的问题
                .eq(sectionId != null, InteractionQuestion::getSectionId, sectionId)
                //问题没有被隐藏
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();

        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }

        //3.根据user_id和latest_answer_id查询提问者和最近一次回答的信息
        Set<Long> userIds = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();

        //3.1.获取问题当中的提问者id和最近一次回答的id
        for (InteractionQuestion record : records) {
            if (!record.getAnonymity()){//只查询非匿名用户的id
                userIds.add(record.getUserId());
            }

            answerIds.add(record.getLatestAnswerId());
        }
        //3.2.根据最近一次回答id查询最近一次回答信息
        answerIds.remove(null);
        Map<Long, InteractionReply> replyMap = new HashMap<>(answerIds.size());
        if (CollUtils.isNotEmpty(answerIds)) {
            List<InteractionReply> replies = replyService.listByIds(answerIds);
            for (InteractionReply reply : replies) {
                replyMap.put(reply.getId(), reply);

                if (!reply.getAnonymity()){//非匿名
                    userIds.add(reply.getUserId());
                }
            }
        }

        //3.3.根据提问者id查询提问者信息(远程调用)
        userIds.remove(null);
        Map<Long, UserDTO> userDTOMap = new HashMap<>(userIds.size());
        if(CollUtils.isNotEmpty(userIds)){
            List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
            userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, user -> user));
        }

        //4.封装VO
        List<QuestionVO> voList = new ArrayList<>(records.size());

        for (InteractionQuestion record : records) {
            //4.1.将PO转为VO
            QuestionVO questionVO = BeanUtils.copyBean(record, QuestionVO.class);
            //4.2.封装提问者的信息
            if (!record.getAnonymity()){
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                if (userDTO != null) {
                    questionVO.setUserName(userDTO.getName());
                    questionVO.setUserIcon(userDTO.getIcon());
                }
            }
            //4.3.封装最新一次回答的信息
            InteractionReply reply = replyMap.get(record.getLatestAnswerId());
            if(reply != null){
                questionVO.setLatestReplyContent(reply.getContent());
                if (!reply.getAnonymity()){
                    UserDTO user = userDTOMap.get(reply.getUserId());
                    questionVO.setLatestReplyUser(user.getName());
                }
            }

            voList.add(questionVO);
        }
        return PageDTO.of(page, voList);
    }


    /**
     * 根据id查询互动问题
     * @param id
     * @return
     */
    @Override
    public QuestionVO queryQuestionById(Long id) {
        //1.根据id查询数据
        InteractionQuestion question = getById(id);
        //2.数据校验
        if (question == null || question.getHidden()){
            //没有数据或问题被隐藏
            return null;
        }

        //3.远程调用,查询提问者的信息
        UserDTO user = null;
        if (!question.getAnonymity()){
            user = userClient.queryUserById(question.getUserId());
        }
        //4.封装VO
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);
        if (user != null){
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }

        return vo;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        //1.处理课程名称,得到课程id
        List<Long> courseIds = null;
        if (StringUtils.isNotBlank(query.getCourseName())){//1.1.前端传来的课程id不为空
            courseIds = searchClient.queryCoursesIdByName(query.getCourseName());

            if (CollUtils.isEmpty(courseIds)){//如果根据课程名称搜索得到的结果为空,则直接返回null
                return PageDTO.empty(0L, 0L);
            }
        }

        //2.分页查询
        Integer status = query.getStatus();
        LocalDateTime beginTime = query.getBeginTime();
        LocalDateTime endTime = query.getEndTime();

        //得到分页结果
        Page<InteractionQuestion> page = lambdaQuery()
                .in(courseIds != null, InteractionQuestion::getCourseId, courseIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(beginTime != null, InteractionQuestion::getCreateTime, beginTime)
                .lt(endTime != null, InteractionQuestion::getCreateTime, endTime)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }

        //3.准备VO需要的数据,用户数据,课程数据,章节数据
        Set<Long> userIds = new HashSet<>();
        Set<Long> cIds = new HashSet<>(); //课程ids
        Set<Long> catalogueIds = new HashSet<>();

        //3.1.获取各种数据的id集合
        for (InteractionQuestion record : records) {
            userIds.add(record.getUserId());
            cIds.add(record.getCourseId());
            catalogueIds.add(record.getChapterId()); //章id
            catalogueIds.add(record.getSectionId()); //节id
        }
        //3.2.根据id查询用户
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = new HashMap<>(userDTOS.size());
        if (CollUtils.isNotEmpty(userDTOS)){
            userMap = userDTOS.stream()
                    .collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

        //3.3.根据id查询课程
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);
        Map<Long, CourseSimpleInfoDTO> courseMap = new HashMap<>(cInfoList.size());
        if (CollUtils.isNotEmpty(cInfoList)){
            courseMap = cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        }
        //3.4.根据id查询章节
        List<CataSimpleInfoDTO> cataInfos = catalogueClient.batchQueryCatalogue(catalogueIds);
        Map<Long, String> cataMap = new HashMap<>(cataInfos.size());
        if (CollUtils.isNotEmpty(cataInfos)){
            cataMap = cataInfos.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }

        //4.封装VO
        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion record : records) {
            //4.1.属性拷贝,将PO转VO
            QuestionAdminVO vo = BeanUtils.copyBean(record, QuestionAdminVO.class);
            //4.2.用户信息
            UserDTO userDTO = userMap.get(record.getUserId());
            if (userDTO != null){
                vo.setUserName(userDTO.getName());
            }

            //4.3.课程信息及分类信息
            CourseSimpleInfoDTO cInfo = courseMap.get(record.getCourseId());
            if (cInfo != null){
                vo.setCourseName(cInfo.getName());
                List<Long> categoryIds = cInfo.getCategoryIds();
                String categoryName = categoryCache.getCategoryNames(categoryIds);
                vo.setCategoryName(categoryName);
            }

            //4.4.章节信息
            String chapterName = cataMap.getOrDefault(record.getChapterId(), "");
            String sectionName = cataMap.getOrDefault(record.getSectionId(), "");
            vo.setChapterName(chapterName);
            vo.setSectionName(sectionName);

            voList.add(vo);
        }

        return PageDTO.of(page, voList);
    }

    /**
     * 管理端隐藏或显示问题
     * @param id
     * @param hidden
     */
    @Override
    @Transactional
    public void hiddenQuestionAdmin(Long id, Boolean hidden) {
        //1.根据id查询问题
        InteractionQuestion question = getById(id);

        //2.如果问题不存在,直接抛异常
        if (question == null){
            throw new BadRequestException("要隐藏或显示的问题不存在!");
        }

        //3.只更新问题的hidden字段
        boolean success = lambdaUpdate()
                .set(InteractionQuestion::getHidden, hidden)
                .eq(InteractionQuestion::getId, id)
                .update();

        //也要将这个问题下的回复和评论做隐藏
        boolean update = replyService.lambdaUpdate()
                .set(InteractionReply::getHidden, hidden)
                .eq(InteractionReply::getQuestionId, id)
                .update();

        //4.如果更新失败,抛异常
        if (!success || !update){
            throw new BadRequestException("更新问题显示状态失败!");
        }
    }


    /**
     * 管理端根据id查询问题详情
     * @param id
     * @return
     */
    @Override
    public QuestionAdminVO queryQuestionAdminVOById(Long id) {
        //1.校验参数是否合法
        if (id == null){
            throw new BadRequestException("非法参数!");
        }

        //2.查问题
        InteractionQuestion question = getById(id);
        if (question == null){
            throw new BadRequestException("问题不存在!");
        }

        //3.拷贝成VO
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);

        //4.查提问人
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null){
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }

        //5.查课程信息和分类
        CourseFullInfoDTO course = courseClient.getCourseInfoById(question.getCourseId(), false, true);
        if(course != null){
            vo.setCourseName(course.getName());
            vo.setCategoryName(categoryCache.getCategoryNames(course.getCategoryIds()));

            //6.查老师信息
            List<Long> teacherIds = course.getTeacherIds();
            if (CollUtils.isNotEmpty(teacherIds)){
                List<UserDTO> teachers = userClient.queryUserByIds(teacherIds);
                if (CollUtils.isNotEmpty(teachers)){
                    vo.setTeacherName(
                            teachers.stream()
                                    .map(UserDTO::getName)
                                    .collect(Collectors.joining("/"))
                    );
                }
            }
        }

        //7.查章节信息
        Set<Long> cataIds = new HashSet<>();
        cataIds.add(question.getChapterId());
        cataIds.add(question.getSectionId());
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(cataIds);

        if (CollUtils.isNotEmpty(catas)){
            Map<Long, String> cataMap = new HashMap<>();
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
            vo.setChapterName(cataMap.getOrDefault(question.getChapterId(), ""));
            vo.setSectionName(cataMap.getOrDefault(question.getSectionId(), ""));
        }
        //8.把问题状态改成已查看
        boolean success = lambdaUpdate()
                .set(InteractionQuestion::getStatus, QuestionStatus.CHECKED)
                .eq(InteractionQuestion::getId, id)
                .update();

        if (!success){
            throw new BadRequestException("问题状态更改失败!");
        }

        //9.返回VO
        return vo;


    }
}
