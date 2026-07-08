package com.tianji.promotion.service.impl;

import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.utils.CodeUtil;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.tianji.promotion.constants.PromotionConstants.COUPON_CODE_MAP_KEY;
import static com.tianji.promotion.constants.PromotionConstants.COUPON_CODE_SERIAL_KEY;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-07-06
 */
@Service
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {

    BoundValueOperations<String, String> serialOps;
    private final StringRedisTemplate redisTemplate;

    public ExchangeCodeServiceImpl(StringRedisTemplate redisTemplate){
        this.redisTemplate = redisTemplate;
        serialOps = redisTemplate.boundValueOps(COUPON_CODE_SERIAL_KEY);
    }


    /**
     * 异步生成兑换码
     * @param coupon
     */
    @Override
    @Async("generateExchangeCodeExecutor")
    public void asyncGenerateExchangeCode(Coupon coupon) {
        //1.获取序列号
        //1.1.获取这个优惠券发放的数量
        Integer totalNum = coupon.getTotalNum();
        //1.2.得到优惠券的序列号的最大值
        Long result = serialOps.increment(totalNum);
        if (result == null){
            throw new BizIllegalException("生成兑换码序列号失败!");
        }
        //1.3.转换成int类型
        int maxSerialNum = result.intValue();

        //2.生成兑换码
        //2.1.获取优惠券的id和过期时间,对应兑换码的id和过期时间
        Long couponId = coupon.getId();
        LocalDateTime issueEndTime = coupon.getIssueEndTime();

        //存储对象,后续批量保存
        List<ExchangeCode> list = new ArrayList<>(totalNum);

        //2.2.循环生成所有的兑换码
        for (int serialNUm = maxSerialNum - totalNum + 1; serialNUm <= maxSerialNum; serialNUm++){
            //2.2.1.生成兑换码
            String code = CodeUtil.generateCode(serialNUm, couponId);
            //2.2.2.new一个兑换码的对象并保存数据
            ExchangeCode e = new ExchangeCode();
            e.setId(serialNUm);
            e.setCode(code);
            e.setExpiredTime(issueEndTime);
            e.setExchangeTargetId(couponId);
            e.setCreateTime(LocalDateTime.now());

            list.add(e);
        }

        //3.批量保存
        saveBatch(list);
    }

    @Override
    public boolean updateExchangeMark(long serialNum, boolean b) {
        Boolean success = redisTemplate.opsForValue().setBit(COUPON_CODE_MAP_KEY, serialNum - 1, b);
        return success != null && success;
    }
}
