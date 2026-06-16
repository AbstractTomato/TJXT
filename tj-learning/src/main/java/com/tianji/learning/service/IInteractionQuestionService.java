package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.QuestionPageQuery;
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
}
