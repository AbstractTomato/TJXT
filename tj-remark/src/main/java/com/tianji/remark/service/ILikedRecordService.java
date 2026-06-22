package com.tianji.remark.service;

import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-21
 */
public interface ILikedRecordService extends IService<LikedRecord> {


    //新增或取消点赞
    void addOrCancelLikeRecord(LikeRecordFormDTO recordFormDTO);

    //查询指定业务id的点赞状态
    Set<Long> isBizLiked(List<Long> bizIds);

    //从redis中读取某一种业务类型的点赞数量变化,然后批量发送MQ
    void readLikedTimesAndSendMessage(String bizType, int maxBizSize);
}
