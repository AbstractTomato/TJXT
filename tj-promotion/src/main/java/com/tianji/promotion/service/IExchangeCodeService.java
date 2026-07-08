package com.tianji.promotion.service;

import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 兑换码 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-07-06
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {

    //异步生成兑换码
    void asyncGenerateExchangeCode(Coupon coupon);

    //修改bitMap中的值
    boolean updateExchangeMark(long serialNum, boolean b);
}
