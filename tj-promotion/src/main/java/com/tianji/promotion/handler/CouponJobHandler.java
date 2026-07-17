package com.tianji.promotion.handler;

import com.tianji.promotion.service.ICouponService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CouponJobHandler {
    private final ICouponService couponService;

    /**
     * 定时开始发放：每分钟扫描一次
     * 将到达发放开始时间的优惠券状态从 UN_ISSUE 更新为 ISSUING
     */
    @XxlJob("checkCouponIssue")
    public void checkCouponIssue(){
        log.info("==== 定时任务 [checkCouponIssue] 开始执行 ====");
        couponService.checkAndIssueCoupons();
        log.info("==== 定时任务 [checkCouponIssue] 执行完毕 ====");
    }

    /**
     * 定时结束发放：每分钟扫描一次
     * 将到达发放结束时间的优惠券状态从 ISSUING 更新为 FINISHED
     */
    @XxlJob("checkCouponFinish")
    public void checkCouponFinish() {
        log.info("==== 定时任务 [checkCouponFinish] 开始执行 ====");
        couponService.checkAndFinishCoupons();
        log.info("==== 定时任务 [checkCouponFinish] 执行完成 ====");
    }
}
