package com.tianji.promotion.service.impl;

import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 用户领取优惠券
     * @param couponId
     */
    @Override
    public void receiveCoupon(Long couponId) {
        //1.查询优惠券
        Coupon coupon = couponMapper.selectById(couponId);
        //2.判断优惠券是否存在
        if (coupon == null){
            throw new BizIllegalException("优惠券不存在!");
        }
        //3.判断优惠券已领数量是否已达上限
        if (coupon.getIssueNum() >= coupon.getTotalNum()){
            throw new BizIllegalException("优惠券已发完!");
        }
        //4.判断时间
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(coupon.getIssueEndTime()) || now.isBefore(coupon.getIssueBeginTime())){
            throw new BizIllegalException("当前不在优惠券发行时间之内!");
        }
        //5.判断当前用户领取的该优惠券数量是否超出限制
        Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .eq(UserCoupon::getCouponId, couponId)
                .count();
        if (count == null || count >= coupon.getUserLimit()){
            throw new BizIllegalException("已超出限制!");
        }
        //6.将优惠券发行数量 + 1
        couponMapper.incrIssueNum(couponId);
        //7.新增用户券
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setCouponId(couponId);
        userCoupon.setUserId(UserContext.getUser());

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

    /**
     * 兑换码兑换优惠券
     * @param code
     */
    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        //1.校验并解析兑换码
        long serialNum = CodeUtil.parseCode(code);
        //2.校验是否已经兑换过
        boolean exchanged = exchangeCodeService.updateExchangeMark(serialNum, true);
        if (exchanged){
            throw new BizIllegalException("兑换码已使用!");
        }
        try {
            //3.查询兑换码
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null){
                throw new BizIllegalException("优惠券不存在!");
            }
            //4.是否过期
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(exchangeCode.getExpiredTime())){
                throw new BizIllegalException("兑换码已过期!");
            }
            //5.校验限领数量
            //5.1.获取用户id信息
            Long userId = UserContext.getUser();
            //5.2.拿到兑换码对应的优惠券
            Long couponId = exchangeCode.getExchangeTargetId();
            Coupon coupon = couponMapper.selectById(couponId);
            //5.3.查询数量
            Integer count = this.lambdaQuery()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getCouponId, couponId)
                    .count();
            if (count >= coupon.getUserLimit()){
                throw new BizIllegalException("已超出限制!");
            }

            //6.更新优惠券已发放数量 + 1
            couponMapper.incrIssueNum(couponId);

            //7.新增一个用户券
            UserCoupon userCoupon = new UserCoupon();
            userCoupon.setCouponId(couponId);
            userCoupon.setUserId(UserContext.getUser());

            LocalDateTime termBeginTime = coupon.getTermBeginTime();
            LocalDateTime termEndTime = coupon.getTermEndTime();
            if (termBeginTime == null){
                termBeginTime = now;
                termEndTime = termBeginTime.plusDays(coupon.getTermDays());
            }
            userCoupon.setTermBeginTime(termBeginTime);
            userCoupon.setTermEndTime(termEndTime);

            save(userCoupon);

            //8.更新兑换码的状态
            exchangeCodeService.lambdaUpdate()
                    .set(ExchangeCode::getUserId, userId)
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .eq(ExchangeCode::getId, exchangeCode.getId())
                    .update();
        } catch (Exception e) {
            //重置兑换码在位图中的状态为0
            exchangeCodeService.updateExchangeMark(serialNum, false);
            throw e;
        }
    }
}
