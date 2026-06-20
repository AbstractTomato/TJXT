package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.constants.Constant;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {


    private final InteractionQuestionMapper questionMapper;
    private final UserClient userClient;

    /**
     * 新增回答或评论
     * @param dto
     */
    @Override
    @Transactional
    public void addReply(ReplyDTO dto) {
        //如果dto中的answerId == null,表明这是对问题的回答
        //如果dto中的answerId != null,表明这是对问题的评论

        //1.获取当前登录用户id
        Long userId = UserContext.getUser();

        //查询当前问题
        InteractionQuestion question = questionMapper.selectById(dto.getQuestionId());
        if (question == null){
            throw new BadRequestException("问题不存在!");
        }

        //把ReplyDTO拷贝成InteractionReply
        InteractionReply reply = BeanUtils.copyBean(dto, InteractionReply.class);

        //设置replyId并保存
        reply.setUserId(userId);
        save(reply);

        //先判断是回答还是评论
        Long answerId = dto.getAnswerId();
        //评论:更新回答的replyTimes
        if (answerId != null){
            //首先查这个评论对应的回答
            InteractionReply answer = getById(answerId);
            if (answer == null){
                throw new BadRequestException("回答不存在!");
            }

            Integer replyTimes = answer.getReplyTimes();
            answer.setReplyTimes(replyTimes == null ? 1 : replyTimes + 1);

            updateById(answer);
        }else {//回答:更新问题的 latestAnswerId 和 answerTimes
            question.setLatestAnswerId(reply.getId());

            Integer answerTimes = question.getAnswerTimes();
            question.setAnswerTimes(answerTimes == null ? 1 : answerTimes + 1);

        }

        //如果是学生提交,把问题的status改成UN_CHECK
        if (Boolean.TRUE.equals(dto.getIsStudent())){
            question.setStatus(QuestionStatus.UN_CHECK);
        }

        //更新问题
        questionMapper.updateById(question);
    }

    /**
     * 分页查询回复
     * @param query
     * @param isAdmin
     * @return
     */
    @Override
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, Boolean isAdmin) {
        //1.参数校验
        if (query.getQuestionId() == null && query.getAnswerId() == null){
            throw new BadRequestException("问题和回答的id不能同时为空!");
        }

        //admin如果是true的话,是管理端.反之是用户端
        boolean admin = Boolean.TRUE.equals(isAdmin);

        //2.分页查询回答或评论
        Page<InteractionReply> page = lambdaQuery()
                //问题id不为空,表明查询回答,不是查评论
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                //查回答id,如果回答id为空,设为零,表明是回答.如果answerId不为空,查评论
                .eq(InteractionReply::getAnswerId, query.getAnswerId() == null ? 0L : query.getAnswerId())
                //默认是学生端查看,查看不到被管理员端隐藏的回答或评论
                .eq(!admin, InteractionReply::getHidden, false)
                .page(query.toMpPage(
                        new OrderItem(Constant.DATA_FIELD_NAME_LIKED_TIME, false),
                        new OrderItem(Constant.DATA_FIELD_NAME_CREATE_TIME, true)
                ));
        List<InteractionReply> records = page.getRecords();

        //3.没有数据,返回空分页
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }

        //4.收集用户id
        Set<Long> userIds = new HashSet<>();
        for (InteractionReply record : records) {
            //判断是否需要展示回答用户的信息
            boolean show = !Boolean.TRUE.equals(record.getAnonymity()) || admin;
            if (show){
                userIds.add(record.getUserId());

                if (record.getTargetUserId() != null && record.getTargetUserId() > 0){
                    userIds.add(record.getTargetUserId());
                }
            }
        }

        //5.批量查询用户信息
        Map<Long, UserDTO> userDTOMap = new HashMap<>();
        if (CollUtils.isNotEmpty(userIds)){
            List<UserDTO> userDTOList = userClient.queryUserByIds(userIds);
            if (CollUtils.isNotEmpty(userDTOList)){
                userDTOMap = userDTOList.stream()
                        .collect(Collectors.toMap(UserDTO::getId, u -> u));
            }
        }

        //6.组装ReplyVO
        List<ReplyVO> voList = new ArrayList<>();
        for (InteractionReply record : records) {
            ReplyVO replyVO = BeanUtils.copyBean(record, ReplyVO.class);

            boolean show = !Boolean.TRUE.equals(record.getAnonymity()) || admin;

            //非匿名
            if (show){
                UserDTO user = userDTOMap.get(record.getUserId());
                if (user != null){
                    replyVO.setUserName(user.getName());
                    replyVO.setUserIcon(user.getIcon());
                    replyVO.setUserType(user.getType());
                }

                if (record.getTargetUserId() != null && record.getTargetUserId() > 0){
                    UserDTO targetUser = userDTOMap.get(record.getTargetUserId());
                    if (targetUser != null){
                        replyVO.setTargetUserName(targetUser.getName());
                    }
                }
            }

            //todo 后续写点赞功能
            replyVO.setLiked(false);

            voList.add(replyVO);
        }

        //7.返回分页结果
        return PageDTO.of(page, voList);
    }


    /**
     * 回答：
     * id = 201
     * answer_id = 0
     *
     * 评论1：
     * id = 301
     * answer_id = 201
     *
     * 评论2：
     * id = 302
     * answer_id = 201
     * @param id
     * @param hidden
     */
    @Override
    @Transactional
    public void hiddenReplyAdmin(Long id, Boolean hidden) {
        //1.根据回答/评论的id查数据
        InteractionReply reply = getById(id);

        //2.如果不存在,抛异常
        if (reply == null){
            throw new BadRequestException("问题或回答不存在!");
        }

        //3.更新当前这条数据的hidden字段
        reply.setHidden(hidden);
        updateById(reply);

        //4.判断当前回答是回答或评论
        Long answerId = reply.getAnswerId();
        //是评论
        if (answerId != null && answerId > 0){
            return;
        }

        //5.如果是回答,同步更新它下面所有评论的hidden字段
        lambdaUpdate()
                .set(InteractionReply::getHidden, hidden)
                .eq(InteractionReply::getAnswerId, id)
                .update();

    }


    /**
     * 管理端根据 id 查询回答或评论详情
     * @param id
     * @return
     */
    @Override

    public ReplyVO queryReplyVOByIdAdmin(Long id) {
        //1.根据id查询回答或评论
        InteractionReply reply = getById(id);

        //2.如果不存在,抛异常
        if (reply == null){
            throw new BadRequestException("回答或评论不存在!");
        }

        //3.收集需要查询的用户id
        Set<Long> userIds = new HashSet<>();
        userIds.add(reply.getUserId());

        //3.1如果有目标用户id,也加进去
        if (reply.getTargetUserId() != null && reply.getTargetUserId() > 0){
            userIds.add(reply.getTargetUserId());
        }

        //4.批量查询用户信息
        Map<Long, UserDTO> userDTOMap = new HashMap<>();
        if (CollUtils.isNotEmpty(userIds)){
            List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
            if (CollUtils.isNotEmpty(userDTOS)){
                userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
            }
        }

        //5.拷贝成replyVO
        ReplyVO vo = BeanUtils.copyBean(reply, ReplyVO.class);

        //6.填充用户信息和目标用户信息
        UserDTO user = userDTOMap.get(reply.getUserId());
        if (user != null){
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
            vo.setUserType(user.getType());
        }

        UserDTO targetUser = userDTOMap.get(reply.getTargetUserId());
        if (targetUser != null){
            vo.setTargetUserName(targetUser.getName());
        }

        //todo设置点赞数
        vo.setLiked(false);

        //7.返回VO
        return vo;
    }

}
