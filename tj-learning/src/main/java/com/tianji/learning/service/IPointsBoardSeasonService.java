package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {

    //查询赛季id
    Integer querySeasonByTime(LocalDateTime time);


}
