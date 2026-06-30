package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-29
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;

    /**
     * 添加积分记录
     * @param message
     * @param type
     */
    @Override
    public void addPointsRecord(SignInMessage message, PointsRecordType type) {
        //如果消息不完整,直接忽略
        if (message.getUserId() == null || message.getPoints() == null){
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        //获得的真实积分
        int realPoints = message.getPoints();

        //获取该积分类型的每日上限,0表示没有上限
        int maxPoints = type.getMaxPoints();
        //如果此时积分有上限,求此时该用户在今天这个时间段，这个业务类型获取多少积分
        if (maxPoints > 0){


            //开始时间
            LocalDateTime begin = DateUtils.getDayStartTime(now);
            //结束时间
            LocalDateTime end = DateUtils.getDayEndTime(now);

            //查询用户今日该业务已得积分
            int currentPoints = queryUserPointsByTypeAndDate(message.getUserId(), type, begin, end);

            //由于没有求和,自己手写
            /*lambdaQuery()
                    .eq(PointsRecord::getUserId, message.getUserId())
                    .eq(PointsRecord::getType, type)
                    .between(PointsRecord::getCreateTime, begin, end);*/

            //如果当前获取的积分大于等于该业务一天中能获取的最大积分,直接返回
            if (currentPoints >= maxPoints){
                return;
            }

            if (message.getPoints() + currentPoints > maxPoints){
                realPoints = maxPoints - currentPoints;
            }
        }

        PointsRecord pointsRecord = new PointsRecord();
        pointsRecord.setPoints(realPoints);
        pointsRecord.setUserId(message.getUserId());
        pointsRecord.setType(type);
        save(pointsRecord);

        //累计积分数据到redis中
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key, message.getUserId().toString(), realPoints);

    }

    /**
     * 查询今日获得的积分
     */
    @Override
    public List<PointsStatisticsVO> queryMyPointsToday() {
        //1.获取用户信息
        Long userId = UserContext.getUser();
        //2.获取日期信息
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);

        //3.构建查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .between(PointsRecord::getCreateTime, begin, end);
        //4.查询
        List<PointsRecord> list = getBaseMapper().queryUserPointsByDate(wrapper);
        if (CollUtils.isEmpty(list)){
            return CollUtils.emptyList();
        }

        //5.封装返回
        List<PointsStatisticsVO> vos = new ArrayList<>(list.size());
        for (PointsRecord po : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(po.getType().getDesc());
            vo.setMaxPoints(po.getType().getMaxPoints());
            vo.setPoints(po.getPoints());

            vos.add(vo);
        }

        return vos;
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
                queryHistoryBoardList(season, query.getPageNo(), query.getPageSize()); //查询历史榜单

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

    //查询历史榜单
    private List<PointsBoard> queryHistoryBoardList(Long season,
                                                    @Min(value = 1, message = "页码不能小于1") Integer pageNo,
                                                    @Min(value = 1, message = "每页查询数量不能小于1") Integer pageSize) {


    }

    //查询当前榜单
    private List<PointsBoard> queryCurrentBoardList(String key,
                                                    @Min(value = 1, message = "页码不能小于1") Integer pageNo,
                                                    @Min(value = 1, message = "每页查询数量不能小于1") Integer pageSize) {
        int from = (pageNo - 1) * pageSize;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().
                reverseRangeWithScores(key, from, from + pageSize - 1);

        //健壮性判断
        if (CollUtils.isEmpty(tuples)){
            return CollUtils.emptyList();
        }

        //封装
        int rank = 1;
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

    private int queryUserPointsByTypeAndDate(Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end) {
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(type != null, PointsRecord::getType, type)
                .between(begin != null && end != null, PointsRecord::getCreateTime, begin, end);
        //调用mapper,查询结果
        Integer points = getBaseMapper().queryUserPointsByTypeAndDate(wrapper);

        return points == null ? 0 : points;
    }
}
