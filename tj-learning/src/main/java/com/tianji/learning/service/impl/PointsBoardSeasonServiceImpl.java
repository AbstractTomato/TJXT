package com.tianji.learning.service.impl;

import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.mapper.PointsBoardSeasonMapper;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason> implements IPointsBoardSeasonService {

    /**
     * 查询赛季id
     * @param time
     * @return
     */
    @Override
    public Integer querySeasonByTime(LocalDateTime time) {

        PointsBoardSeason res = lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();

        return res == null ? null : res.getId();
    }


}
