package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.ICouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 *
 * @author Sh1nley
 * @since 2026-07-06
 */
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
@Api(tags = "优惠券相关接口")
public class CouponController {
    private final ICouponService couponService;

    /**
     * 优惠券新增接口
     */
    @ApiOperation("新增优惠券接口")
    @PostMapping
    public void saveCoupon(@RequestBody @Valid CouponFormDTO dto){
        couponService.saveCoupon(dto);
    }

    /**
     * 分页查询优惠券
     * @param query
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("查询优惠券")
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query){
        return couponService.queryCouponByPage(query);
    }

    /**
     * 根据id查询优惠券
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("根据id查询优惠券")
    public CouponDetailVO queryCouponById(@PathVariable Long id){
        return couponService.queryCouponById(id);
    }

    /**
     * 删除优惠券
     * @param id
     */
    @DeleteMapping("/{id}")
    @ApiOperation("删除优惠券")
    public void deleteCouponById(@PathVariable Long id){
        couponService.deleteCouponById(id);
    }

    /**
     * 暂停发放优惠券
     * @param id
     */
    @ApiOperation("暂停发放优惠券")
    @PutMapping("/{id}/pause")
    public void pauseIssueCouponById(@ApiParam("优惠券id") @PathVariable Long id){
        couponService.pauseIssueCouponById(id);
    }

    /**
     * 修改优惠券
     * @param id
     * @param dto
     */
    @PutMapping("/{id}")
    @ApiOperation("修改优惠券")
    public void updateCouponById(@PathVariable Long id,@RequestBody @Valid CouponFormDTO dto){
        couponService.updateCouponById(id, dto);
    }

    /**
     * 发放优惠券的接口
     */
    @PutMapping("/{id}/issue")
    @ApiOperation("发放优惠券的接口")
    public void beginIssue(@RequestBody @Valid CouponIssueFormDTO dto){
        couponService.beginIssue(dto);
    }

    /**
     * 查询正在发放中的,手动领取的优惠券
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("查询正在发放中的,手动领取的优惠券")
    public List<CouponVO> queryIssuingCoupon(){
        return couponService.queryIssuingCoupon();
    }
}
