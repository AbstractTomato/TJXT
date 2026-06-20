package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * @author Sh1nley
 * @since 2026-06-17
 */
@RestController
@RequestMapping("/admin/questions")
@Api(tags = "管理端互动问答的相关接口")
@RequiredArgsConstructor
public class InteractionQuestionAdminController {

    private final IInteractionQuestionService questionService;

    /**
     * 管理端分页查询互动问题
     * @param query
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("管理端分页查询互动问题")
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query){
        return questionService.queryQuestionPageAdmin(query);
    }


    /**
     * 管理端隐藏或显示问题
     * @param id
     * @param hidden
     */
    @PutMapping("/{id}/hidden/{hidden}")
    @ApiOperation("管理端隐藏或显示问题")
    public void hiddenQuestionAdmin(
        @PathVariable("id") Long id,
        @PathVariable("hidden") Boolean hidden){
        questionService.hiddenQuestionAdmin(id, hidden);
    }


    /**
     * 管理端根据id查询问题详情
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("管理端根据id查询问题详情")
    public QuestionAdminVO queryQuestionAdminVOById(@PathVariable("id") Long id){
        return questionService.queryQuestionAdminVOById(id);
    }


}
