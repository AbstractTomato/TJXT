package com.tianji.learning.service.impl;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;


    /**
     * 签到功能的接口
     * @return
     */
    @Override
    public SignResultVO addSignRecords() {
        //1.签到
        //1.1. 获取用户信息
        Long userId = UserContext.getUser();
        //1.2.获取日期
        LocalDate now = LocalDate.now();
        //1.3.拼接key
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        //1.4.计算offset
        int offset = now.getDayOfMonth() - 1;
        //1.5.保存签到信息
        Boolean exit = redisTemplate.opsForValue().setBit(key, offset, true);
        if (BooleanUtils.isTrue(exit)){
            throw new BizIllegalException("不允许重复签到!");
        }

        //2.计算连续签到的天数
        int signDays = countSignDays(key, now.getDayOfMonth());

        //3.计算签到得分
        int rewardPoints = 0;
        switch (signDays){
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }

        //4.保存积分明细
        mqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));


        //5.封装返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(signDays);
        vo.setRewardPoints(rewardPoints);
        return vo;
    }

    @Override
    public Byte[] querySignRecords() {
        Long userId = UserContext.getUser();

        LocalDate now = LocalDate.now();

        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);

        int dayOfMonth = now.getDayOfMonth();

        List<Long> results = redisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if (CollUtils.isEmpty(results) || results.get(0) == null){
            return new Byte[0];
        }

        //redis返回的是一个十进制的数,通过位运算还原成二进制
        int num = results.get(0).intValue();

        Byte[] res = new Byte[dayOfMonth];

        //从最后一天开始解析,取出来的结果中,今天签到的结果在最低位
        int offset = dayOfMonth - 1;

        while (offset >= 0){
            res[offset] = (byte) (num & 1);
            //继续解析前一天
            offset--;
            num >>>= 1;
        }

        return res;
    }

    //计算连续签到了多少天
    //从后向前遍历
    private int countSignDays(String key, int len) {
        //1.获取从本月第一天开始,到今天所有的签到记录
        List<Long> results = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(len)).valueAt(0));
        //如果为空,表明没有查到,直接返回0即可.
        if (CollUtils.isEmpty(results)){
            return 0;
        }

        int num = results.get(0).intValue();
        //2.定义一个计数器,用来计算到今天为止,已经连续签到了多少天
        int count = 0;

        //3.循环,与1做与运算,得到最后一个bit,判断是否为0,如果为0,则提前终止
        while ((num & 1) == 1){
            //4.如果不为0,计数器+1, 向右移动一位,此时倒数第二位变成倒数第一位
            count++;
            //右移
            num >>>= 1;
        }

        return count;
    }
}
