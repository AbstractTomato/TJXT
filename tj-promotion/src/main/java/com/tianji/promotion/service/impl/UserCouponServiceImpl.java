package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.utils.CodeUtil;
import com.tianji.promotion.utils.MyLock;
import com.tianji.promotion.utils.RedisLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-07-08
 */
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;
    private final RedissonClient redissonClient;

    /**
     * 用户领取优惠券
     * @param couponId
     */
    @Lock(name = "lock:coupon:#{couponId}")
    @Override
    public void receiveCoupon(Long couponId) {
        //1.查询优惠券,从redis中查
        Coupon coupon = queryCouponByCache(couponId);
        //2.判断优惠券是否存在
        if (coupon == null){
            throw new BizIllegalException("优惠券不存在!");
        }
        //3.判断优惠券已领数量是否已达上限
        if (coupon.getTotalNum() <= 0){
            throw new BizIllegalException("优惠券已发完!");
        }

        //校验限领数量
        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + couponId;
        Long count = redisTemplate.opsForHash().increment(key, UserContext.getUser().toString(), 1);
        if (count > coupon.getUserLimit()){
            throw new BizIllegalException("超出限领数量");
        }

        redisTemplate.opsForHash().increment(PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId, "totalNum", -1);

        //发送mq消息
        UserCouponDTO uc = new UserCouponDTO();
        uc.setUserId(UserContext.getUser());
        uc.setCouponId(couponId);
        mqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE,MqConstants.Key.COUPON_RECEIVE, uc);


    }

    /*从redis中查询*/
    private Coupon queryCouponByCache(Long couponId) {
        //定义key
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;
        //查询
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (CollUtils.isEmpty(entries)){
            return null;
        }
        //反序列化
        return BeanUtils.mapToBean(entries, Coupon.class, false, CopyOptions.create());
    }

    /**
     * 兑换码兑换优惠券
     * @param code
     */
    @Override
    @Transactional
    public void exchangeCoupon(String code) throws InterruptedException {
        //1.校验并解析兑换码
        long serialNum = CodeUtil.parseCode(code);
        //2.校验是否已经兑换过（Redis bitmap）
        boolean exchanged = exchangeCodeService.updateExchangeMark(serialNum, true);
        if (exchanged) {
            throw new BizIllegalException("兑换码已使用!");
        }
        // 声明在 try 外面，方便 catch 块访问做回滚
        String userLimitKey = null;
        String cacheKey = null;
        Long userId = null;
        boolean redisModified = false;
        try {
            //3.查询兑换码
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在!");
            }
            //4.是否过期
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(exchangeCode.getExpiredTime())) {
                throw new BizIllegalException("兑换码已过期!");
            }
            //5.获取用户id
            userId = UserContext.getUser();
            //6.查询优惠券
            Long couponId = exchangeCode.getExchangeTargetId();
            Coupon coupon = couponMapper.selectById(couponId);
            if (coupon == null) {
                throw new BizIllegalException("优惠券不存在!");
            }
            //7.校验优惠券是否正在发放中
            if (coupon.getStatus() != CouponStatus.ISSUING) {
                throw new BizIllegalException("优惠券不在发放期!");
            }

            //初始化 key（外层已声明，此处赋值）
            userLimitKey = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + couponId;
            cacheKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;

            //8.加分布式锁
            RLock lock = redissonClient.getLock("lock:coupon:" + couponId);
            if (!lock.tryLock(1, TimeUnit.SECONDS)) {
                throw new BizIllegalException("系统繁忙，请稍后重试");
            }
            try {
                //9.Redis 校验限领数量
                Long count = redisTemplate.opsForHash().increment(userLimitKey, userId.toString(), 1);
                if (count > coupon.getUserLimit()) {
                    redisTemplate.opsForHash().increment(userLimitKey, userId.toString(), -1);
                    throw new BizIllegalException("超出限领数量");
                }

                //如果缓存不存在，先初始化（兼容定时发放场景，缓存可能尚未写入）
                if (Boolean.FALSE.equals(redisTemplate.hasKey(cacheKey))) {
                    Map<String, String> map = new HashMap<>(4);
                    map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueBeginTime())));
                    map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueEndTime())));
                    map.put("totalNum", String.valueOf(coupon.getTotalNum()));
                    map.put("userLimit", String.valueOf(coupon.getUserLimit()));
                    redisTemplate.opsForHash().putAll(cacheKey, map);
                }
                Long remain = redisTemplate.opsForHash().increment(cacheKey, "totalNum", -1);
                if (remain < 0) {
                    //回滚库存
                    redisTemplate.opsForHash().increment(cacheKey, "totalNum", 1);
                    //回滚限领
                    redisTemplate.opsForHash().increment(userLimitKey, userId.toString(), -1);
                    throw new BizIllegalException("优惠券已发完!");
                }

                //两个redis都已经操作成功
                redisModified = true;

                //11.发送 MQ 消息（和手动领取走同一条消费者链路）
                UserCouponDTO uc = new UserCouponDTO();
                uc.setUserId(userId);
                uc.setCouponId(couponId);
                mqHelper.send(
                    MqConstants.Exchange.PROMOTION_EXCHANGE,
                    MqConstants.Key.COUPON_RECEIVE,
                    uc
                );
                //12.更新兑换码状态
                exchangeCodeService.lambdaUpdate()
                        .set(ExchangeCode::getUserId, userId)
                        .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                        .eq(ExchangeCode::getId, exchangeCode.getId())
                        .update();
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            // 重置兑换码 bitmap
            exchangeCodeService.updateExchangeMark(serialNum, false);
            // 如果 Redis 计数器已被修改，需要回滚
            if (redisModified) {
                redisTemplate.opsForHash().increment(userLimitKey, userId.toString(), -1);
                redisTemplate.opsForHash().increment(cacheKey, "totalNum", 1);
            }
            throw e;
        }
    }


    @Override
    @Transactional
    public void checkAndCreateUserCoupon(UserCouponDTO uc){
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null){
            throw new BizIllegalException("优惠券不存在!");
        }

        Long userId = uc.getUserId();

        int result = couponMapper.incrIssueNum(coupon.getId());
        if (result == 0){
            throw new BizIllegalException("优惠券库存不足!");
        }

        //7.新增用户券
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setCouponId(coupon.getId());
        userCoupon.setUserId(userId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null){
            termBeginTime = now;
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        userCoupon.setTermBeginTime(termBeginTime);
        userCoupon.setTermEndTime(termEndTime);

        save(userCoupon);
    }
}
