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

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
@RestController
@RequestMapping("/admin/replies")
@RequiredArgsConstructor
@Api(tags = "管理端回答相关的接口")
public class InteractionReplyAdminController {

    private final IInteractionReplyService replyService;

    /**
     * 管理端分页查询问答
     * @param query
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("管理端分页查询问答")
    public PageDTO<ReplyVO> queryReplyPageAdmin(ReplyPageQuery query){
        return replyService.queryReplyPage(query, true);
    }

    @PutMapping("/{id}/hidden/{hidden}")
    @ApiOperation("管理端隐藏问答")
    public void hiddenReplyAdmin(
            @PathVariable("id") Long id,
            @PathVariable("hidden") Boolean hidden){
        replyService.hiddenReplyAdmin(id, hidden);
    }


    @GetMapping("/{id}")
    @ApiOperation("管理端根据 id 查询回答或评论详情")
    public ReplyVO queryReplyVOByIdAdmin(@PathVariable("id") Long id){
        //管理员根据 reply 表主键 id 查询某一条回答或评论的详情
        return replyService.queryReplyVOByIdAdmin(id);
    }

}
