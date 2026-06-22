package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.api.dto.remark.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
//@Service
@RequiredArgsConstructor
public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;


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
        Integer likeTimes = lambdaQuery()
                .eq(LikedRecord::getBizId, recordFormDTO.getBizId())
                .count();

        //4.发送MQ通知
        mqHelper.send(
                //指定的交换机
                LIKE_RECORD_EXCHANGE,
                //routing key
                StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, recordFormDTO.getBizType()),
                //消息体,为了方便监听拿消息,构建一个对象
                List.of(LikedTimesDTO.of(recordFormDTO.getBizId(), likeTimes))
        );

    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        //1.获取登录用户信息
        Long userId = UserContext.getUser();

        //2.查询点赞状态
        List<LikedRecord> list = lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .in(LikedRecord::getBizId, bizIds)
                .list();

        //3.返回结果
        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {

    }

    private boolean unlike(LikeRecordFormDTO recordFormDTO) {
        return remove(new QueryWrapper<LikedRecord>().lambda()
                .eq(LikedRecord::getUserId, UserContext.getUser())
                .eq(LikedRecord::getBizId, recordFormDTO.getBizId()));

    }

    private boolean like(LikeRecordFormDTO recordFormDTO) {
        //1.查询点赞记录
        Integer count = lambdaQuery()
                .eq(LikedRecord::getUserId, UserContext.getUser())
                .eq(LikedRecord::getBizId, recordFormDTO.getBizId())
                .count();

        //2.判断是否存在,如果存在,直接返回
        if (count > 0){
            return false;
        }

        //3.如果不存在,直接新增
        LikedRecord likedRecord = BeanUtils.copyBean(recordFormDTO, LikedRecord.class);
        likedRecord.setUserId(UserContext.getUser());
        likedRecord.setCreateTime(LocalDateTime.now());
        save(likedRecord);

        return true;
    }
}
