package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsRecord;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mq.message.SignInMessage;

import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
public interface IPointsRecordService extends IService<PointsRecord> {

    void addPointsRecord(SignInMessage message, PointsRecordType pointsRecordType);

    //查询当日积分获取情况
    List<PointsStatisticsVO> queryMyPointsToday();

    //根据赛季查询积分榜
    PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query);
}
