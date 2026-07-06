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
