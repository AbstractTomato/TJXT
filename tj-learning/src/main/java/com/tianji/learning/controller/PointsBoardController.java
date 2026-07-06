package com.tianji.learning.controller;


import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.service.IPointsRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 学霸天梯榜 前端控制器
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
@Api(tags = "积分排行榜相关接口")
public class PointsBoardController {
    private final IPointsBoardService boardService;

    /**
     * 根据赛季查询积分榜
     * @param query
     * @return
     */
    @GetMapping
    @ApiOperation("根据赛季查询积分榜")
    public PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query){
        return boardService.queryPointsBoardBySeason(query);
    }
}
