package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;

/**
 * <p>
 * 互动提问的问题表 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
public interface IInteractionQuestionService extends IService<InteractionQuestion> {

    //新增一个互动问题
    void newQuestion(QuestionFormDTO questionDTO);

    //分页查询互动问题
    PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query);

    //根据id查询互动问题
    QuestionVO queryQuestionById(Long id);

    //管理端分页查询互动问题
    PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query);

    //管理端隐藏或显示问题
    void hiddenQuestionAdmin(Long id, Boolean hidden);

    //管理端根据id查询问题详情
    QuestionAdminVO queryQuestionAdminVOById(Long id);
}
