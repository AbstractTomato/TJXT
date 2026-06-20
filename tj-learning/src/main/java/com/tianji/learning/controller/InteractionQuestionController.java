package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动提问的问题表 前端控制器
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
@RestController
@RequestMapping("/questions")
@Api(tags = "互动问答的相关接口")
@RequiredArgsConstructor
public class InteractionQuestionController {

    private final IInteractionQuestionService questionService;


    /**
     * 新增一个互动问题
     * @param questionDTO
     */
    @PostMapping
    @ApiOperation("新增一个互动问题")
    public void newQuestion(@RequestBody @Valid QuestionFormDTO questionDTO){
        questionService.newQuestion(questionDTO);
    }

    /**
     * 分页查询互动问题
     * @param query
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("分页查询互动问题")
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query){
        return questionService.queryQuestionPage(query);
    }


    /**
     * 根据id查询互动问题
     * @param id
     * @return
     */
    @GetMapping("{id}")
    @ApiOperation("根据id查询互动问题")
    public QuestionVO queryQuestionById(@PathVariable("id") Long id){
        return questionService.queryQuestionById(id);
    }
}
