package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constant.LearningConstant;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.validation.constraints.Min;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;

    /**
     * 根据赛季id来创建表
     * @param seasonId
     */
    @Override
    public void createPointsBoardTableBySeason(Integer seasonId) {
        getBaseMapper().createPointsBoardTable("points_board_" + seasonId);
    }

    /**
     * 根据赛季查询积分榜
     * @param query
     * @return
     */
    @Override
    public PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query) {
        //判断是否是查询当前赛季
        Long season = query.getSeason();
        boolean isCurrentSeason = season == null || season == 0;

        //获取redis的key
        LocalDateTime now = LocalDateTime.now();
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);

        //初始化我的榜单
        //1.查询我的积分和排名
        PointsBoard myBoard = isCurrentSeason ?
                queryMyCurrentBoard(key) : //查询的是当前榜单(redis)
                queryMyHistoryBoard(season); //查询的是之前的榜单(mysql)


        //2.查询榜单列表
        List<PointsBoard> list = isCurrentSeason ?
                queryCurrentBoardList(key, query.getPageNo(), query.getPageSize()) : //查询当前榜单
                queryHistoryBoardList(query); //查询历史榜单

        //封装返回
        PointsBoardVO pointsBoardVO = new PointsBoardVO();

        //处理我的榜单信息
        if (myBoard != null){
            pointsBoardVO.setPoints(myBoard.getPoints());
            pointsBoardVO.setRank(myBoard.getRank());
        }
        //如果榜单为空,直接返回即可
        if (CollUtils.isEmpty(list)){
            return pointsBoardVO;
        }

        //处理榜单列表信息
        Set<Long> userIds = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, String> userDTOMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userDTOS)){
            userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, UserDTO::getName));
        }

        //定义VO集合
        List<PointsBoardItemVO> vos = new ArrayList<>();
        for (PointsBoard pointsBoard : list) {
            PointsBoardItemVO vo = new PointsBoardItemVO();
            vo.setPoints(pointsBoard.getPoints());
            vo.setRank(pointsBoard.getRank());
            vo.setName(userDTOMap.get(pointsBoard.getUserId()));

            vos.add(vo);
        }

        pointsBoardVO.setBoardList(vos);

        return pointsBoardVO;
    }

    //查询历史榜单列表
    private List<PointsBoard> queryHistoryBoardList(PointsBoardQuery query) {
        String tableName = LearningConstant.POINTS_BOARD_TABLE_PREFIX + query.getSeason();

        TableInfoContext.setInfo(tableName);

        try {
            Page<PointsBoard> page = this.page(query.toMpPage());

            List<PointsBoard> records = page.getRecords();
            if (CollUtils.isEmpty(records)){
                return CollUtils.emptyList();
            }

            for (PointsBoard record : records) {
                record.setRank(record.getId().intValue());
            }

            return records;
        }finally {
            TableInfoContext.remove();
        }

    }

    //查询当前榜单
    @Override
    public List<PointsBoard> queryCurrentBoardList(String key,
                                                   @Min(value = 1, message = "页码不能小于1") Integer pageNo,
                                                   @Min(value = 1, message = "每页查询数量不能小于1") Integer pageSize) {
        int from = (pageNo - 1) * pageSize;
        int end = from + pageSize - 1;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().
                reverseRangeWithScores(key, from, end);

        //健壮性判断
        if (CollUtils.isEmpty(tuples)){
            return CollUtils.emptyList();
        }

        //封装
        int rank = from + 1;
        List<PointsBoard> list = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String userId = tuple.getValue();
            Double points = tuple.getScore();

            if (userId == null || points == null){
                continue;
            }
            PointsBoard pointsBoard = new PointsBoard();
            pointsBoard.setUserId(Long.valueOf(userId));
            pointsBoard.setPoints(points.intValue());
            pointsBoard.setRank(rank);
            rank++;

            list.add(pointsBoard);
        }

        return list;
    }


    //查询我的历史榜单
    private PointsBoard queryMyHistoryBoard(Long season) {
        Long userId = UserContext.getUser();

        String tableName = LearningConstant.POINTS_BOARD_TABLE_PREFIX + season;
        TableInfoContext.setInfo(tableName);

        try {
            Optional<PointsBoard> opt = this.lambdaQuery()
                    .eq(PointsBoard::getUserId, userId)
                    .oneOpt();

            if (opt.isEmpty()){
                return null;
            }

            PointsBoard pointsBoard = opt.get();
            pointsBoard.setRank(pointsBoard.getId().intValue());

            return pointsBoard;
        }finally {
            TableInfoContext.remove();
        }
    }

    //查询我的当前榜单
    private PointsBoard queryMyCurrentBoard(String key) {
        //获取当前用户信息
        String userId = UserContext.getUser().toString();
        //绑定redis中的key
        BoundZSetOperations<String, String> ops = redisTemplate.boundZSetOps(key);
        //查询积分
        Double points = ops.score(userId);
        //查询排名
        Long rank = ops.reverseRank(userId);
        //封装返回
        PointsBoard pointsBoard = new PointsBoard();
        pointsBoard.setPoints(points == null ? 0 : points.intValue());
        pointsBoard.setRank(rank == null ? 0 : rank.intValue() + 1);

        return pointsBoard;
    }
}
