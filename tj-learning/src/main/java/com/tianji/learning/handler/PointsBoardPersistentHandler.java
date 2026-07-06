package com.tianji.learning.handler;

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService seasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;

    @XxlJob("createTableJob")
    public void createPointBoardTableOfLastSeason(){
        //1.获取上月时间
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1);

        //2.查询赛季id
        Integer seasonId = seasonService.querySeasonByTime(lastMonth);

        if (seasonId == null){
            //赛季不存在
            return;
        }

        //3.根据赛季id来创建表
        pointsBoardService.createPointsBoardTableBySeason(seasonId);

    }

    /**
     * 将redis中的数据持久化到数据库
     */
    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB(){
        //1.获取上月时间
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1);

        //2.计算动态表名
        Integer seasonId = seasonService.querySeasonByTime(lastMonth);
        TableInfoContext.setInfo("points_board_" + seasonId);

        //3.拼接key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + lastMonth.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);

        int pageNo = 1;
        int pageSize = 1000;
        //4.查询榜单
        while (true){
            List<PointsBoard> pointsBoardList = pointsBoardService.queryCurrentBoardList(key, pageNo, pageSize);
            if (CollUtils.isEmpty(pointsBoardList)){
                break;
            }

            //5.持久化到数据库
            //5.1.将排名信息写入id
            pointsBoardList.forEach(p -> {
                p.setId(p.getRank().longValue());
                p.setRank(null);
            });

            //5.2.持久化到数据库
            pointsBoardService.saveBatch(pointsBoardList);
            pageNo++;
        }

        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void cleanPointsBoardFromRedis(){
        //1.获取上月时间
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1);
        //2.计算key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + lastMonth.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        //3.删除key
        redisTemplate.unlink(key);

    }
}
