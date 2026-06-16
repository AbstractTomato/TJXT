package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
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
        question.setId(userId);
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
}
