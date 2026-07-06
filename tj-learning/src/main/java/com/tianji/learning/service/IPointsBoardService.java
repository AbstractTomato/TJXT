package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoard;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;

import javax.validation.constraints.Min;
import java.util.List;

/**
 * <p>
 * 学霸天梯榜 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
public interface IPointsBoardService extends IService<PointsBoard> {

    //根据赛季id来创建表
    void createPointsBoardTableBySeason(Integer seasonId);

    //根据赛季查询积分榜
    PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query);

    //查询当前榜单
    List<PointsBoard> queryCurrentBoardList(String key,
                                            @Min(value = 1, message = "页码不能小于1") Integer pageNo,
                                            @Min(value = 1, message = "每页查询数量不能小于1") Integer pageSize);
}
