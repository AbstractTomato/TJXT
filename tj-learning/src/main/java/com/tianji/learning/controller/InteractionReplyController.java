package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
@RestController
@RequestMapping("/replies")
@RequiredArgsConstructor
@Api(tags = "回答相关的接口")
public class InteractionReplyController {

    private final IInteractionReplyService replyService;


    /**
     * 新增回答或评论
     * 如果dto中的answerId == null,表明这是对问题的回答
     * 如果dto中的answerId != null,表明这是对问题的评论
     * @param dto
     */
    @PostMapping
    @ApiOperation("新增回答或评论")
    public void addReply(@RequestBody @Validated ReplyDTO dto){
        replyService.addReply(dto);
    }


    /**
     * 用户端分页查询回答或评论
     * @param query
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("用户端分页查询回答或评论")
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query){
        return replyService.queryReplyPage(query, false);
    }



}
