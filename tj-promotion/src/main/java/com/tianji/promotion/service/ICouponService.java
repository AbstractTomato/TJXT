package com.tianji.promotion.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;

import javax.validation.Valid;
import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 服务类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-07-06
 */
public interface ICouponService extends IService<Coupon> {

    //新增优惠券接口
    void saveCoupon(@Valid CouponFormDTO dto);

    //分页查询优惠券
    PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query);

    //修改优惠券
    void updateCouponById(Long id, CouponFormDTO dto);

    //删除优惠券
    void deleteCouponById(Long id);

    //根据id查询优惠券
    CouponDetailVO queryCouponById(Long id);

    //发放优惠券的接口
    void beginIssue(@Valid CouponIssueFormDTO dto);

    //查询正在发放中的,手动领取的优惠券
    List<CouponVO> queryIssuingCoupon();

}
