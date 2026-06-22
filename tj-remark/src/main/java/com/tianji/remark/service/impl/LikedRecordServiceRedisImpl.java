package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.dto.remark.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constants.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tianji.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-06-21
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikedRecordServiceRedisImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 新增或取消点赞
     * @param recordFormDTO
     */
    @Override
    public void addOrCancelLikeRecord(LikeRecordFormDTO recordFormDTO) {
        //1.基于前端参数,判断是点赞还是取消点赞
        boolean success = recordFormDTO.getLiked() ? like(recordFormDTO) : unlike(recordFormDTO);

        //2.判断是否执行成功,如果执行失败,直接结束任务
        if (!success){
            return;
        }

        //3.如果执行成功,统计点赞总数
        Long likeTimes = redisTemplate.opsForSet()
                .size(RedisConstants.LIKES_BIZ_KEY_PREFIX + recordFormDTO.getBizId());

        if (likeTimes == null){
            return;
        }
        //4.缓存点赞总数到redis
        redisTemplate.opsForZSet().add(
                RedisConstants.LIKES_TIMES_KEY_PREFIX + recordFormDTO.getBizType().toString(),
                recordFormDTO.getBizId().toString(),
                likeTimes
        );

    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        //1.获取登录用户信息
        Long userId = UserContext.getUser();

        List<Object> objects = redisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                StringRedisConnection conn = (StringRedisConnection) connection;

                for (Long bizId : bizIds) {
                    String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + bizId;
                    conn.sIsMember(key, userId.toString());
                }
                return null;
            }
        });

        //3.返回结果
        Set<Long> ans = new HashSet<>();
        for (int i = 0; i < objects.size(); i++) {
            boolean o = (boolean) objects.get(i);
            //索引一一对应
            if (o){
                ans.add(bizIds.get(i));
            }
        }

        return ans;
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        //拼出zset的key
        String key = RedisConstants.LIKES_TIMES_KEY_PREFIX + bizType;

        //从zset中取出并删除最多maxBizSize条数据
        Set<ZSetOperations.TypedTuple<String>> Tuples = redisTemplate.opsForZSet()
                .popMin(key, maxBizSize);

        //如果没有点赞数量变化,直接结束,不需要发送MQ
        if(CollUtils.isEmpty(Tuples)){
            return;
        }

        //准备批量消息集合
        List<LikedTimesDTO> list = new ArrayList<>(Tuples.size());

        for (ZSetOperations.TypedTuple<String> tuple : Tuples) {
            //业务id
            String bizId = tuple.getValue();
            //点赞数量
            Double likedTimes = tuple.getScore();

            //防止脏数据
            if (StringUtils.isBlank(bizId) || likedTimes == null){
                continue;
            }

            //封装MQ消息
            list.add(LikedTimesDTO.of(Long.valueOf(bizId), likedTimes.intValue()));
        }

        //如果最后没有有效消息,不发送MQ
        if (CollUtils.isEmpty(list)){
            return;
        }

        String routingKey = StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, bizType);

        //发送MQ消息
        mqHelper.send(LIKE_RECORD_EXCHANGE,
                routingKey,
                list
        );
    }

    private boolean unlike(LikeRecordFormDTO recordFormDTO) {
        //1.获取用户id
        Long userId = UserContext.getUser();

        //2.获取key
        String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + recordFormDTO.getBizId();

        //3.执行SREM命令
        Long result = redisTemplate.opsForSet().remove(key, userId.toString());

        return result != null && result > 0;
    }

    private boolean like(LikeRecordFormDTO recordFormDTO) {
        //1.获取用户id
        Long userId = UserContext.getUser();

        //2.获取key
        String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + recordFormDTO.getBizId();

        //3.执行SADD命令
        Long result = redisTemplate.opsForSet().add(key, userId.toString());

        return result != null && result > 0;
    }
}
