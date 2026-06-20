package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionReply;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;

/**
 * <p>
 * 互动问题的回答或评论 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-16
 */
public interface IInteractionReplyService extends IService<InteractionReply> {

    //用户端添加回答或评论
    void addReply(ReplyDTO dto);

    //分页查询回答或评论, 用户端和管理端复用
    PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, Boolean isAdmin);

    //管理端隐藏或显示问题
    void hiddenReplyAdmin(Long id, Boolean hidden);

    //管理端根据id查询问题详情
    ReplyVO queryReplyVOByIdAdmin(Long id);
}
