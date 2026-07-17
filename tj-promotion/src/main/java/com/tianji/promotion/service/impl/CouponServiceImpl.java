package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponScopeVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.codehaus.groovy.classgen.FinalVariableAnalyzer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author Sh1nley
 * @since 2026-07-06
 */
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    private final ICouponScopeService couponScopeService;
    private final CategoryCache categoryCache;
    private final IExchangeCodeService exchangeCodeService;
    private final IUserCouponService userCouponService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 新增优惠券接口
     * @param dto
     */
    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO dto) {
        //1.保存优惠券信息
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        save(coupon);

        //如果没有限定范围,直接返回
        if (!Boolean.TRUE.equals(dto.getSpecific())){
            return;
        }
        //2.保存限定范围
        //2.1.拿到业务范围id
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)){
            throw new BadRequestException("限定范围不能为空!");
        }
        //2.2.抓换成po
        List<CouponScope> couponScopes = scopes.stream()
                .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(coupon.getId()))
                .collect(Collectors.toList());

        //2.3.保存
        couponScopeService.saveBatch(couponScopes);
    }

    /**
     * 分页查询优惠券
     * @param query
     * @return
     */
    @Override
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query) {
        Integer type = query.getType();
        Integer status = query.getStatus();
        String name = query.getName();
        //1.分页查询
        Page<Coupon> page = lambdaQuery()
                .eq(type != null, Coupon::getDiscountType, type)
                .eq(status != null, Coupon::getStatus, status)
                .like(StringUtils.isNotBlank(name), Coupon::getName, name)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }

        List<CouponPageVO> couponPageVOS = BeanUtils.copyList(records, CouponPageVO.class);

        return PageDTO.of(page, couponPageVOS);

    }

    /**
     * 修改优惠券
     * @param id
     * @param dto
     */
    @Override
    @Transactional
    public void updateCouponById(Long id, CouponFormDTO dto) {
        //1.参数校验
        if (id == null || dto == null){
            throw new BadRequestException("非法参数或优惠券id不一致!");
        }
        //2.根据id查询优惠券
        Coupon coupon = getById(id);
        //3.判断优惠券是否存在
        if (coupon == null){
            throw new BadRequestException("优惠券不存在!");
        }
        //4.判断优惠券的状态,只允许待发放的状态才允许修改
        if (coupon.getStatus() != CouponStatus.DRAFT){
            throw new BadRequestException("只有待发放的优惠券才能修改!");
        }
        //5.DTO转PO
        Coupon updateCoupon = BeanUtils.copyBean(dto, Coupon.class);

        //6.更新coupon表,同步更新coupon_scope,删除旧范围,新增新范围
        updateById(updateCoupon);

        if (!Boolean.TRUE.equals(dto.getSpecific())){
            couponScopeService.remove(new QueryWrapper<CouponScope>().eq("coupon_id", id));
        }

        if (Boolean.TRUE.equals(dto.getSpecific())){
            List<Long> scopes = dto.getScopes();
            if (CollUtils.isEmpty(scopes)){
                throw new BadRequestException("限定范围不能为空!");
            }

            List<CouponScope> couponScopesList = scopes.stream()
                    .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(id).setType(1))
                    .collect(Collectors.toList());

            couponScopeService.saveBatch(couponScopesList);
        }

    }

    /**
     * 根据id查询优惠券
     */
    @Override
    public CouponDetailVO queryCouponById(Long id){
        if (id == null){
            throw new BadRequestException("优惠券id不能为空!");
        }
        Coupon coupon = getById(id);
        if (coupon == null){
            throw new BadRequestException("优惠券不存在!");
        }

        CouponDetailVO couponDetailVO = BeanUtils.copyBean(coupon, CouponDetailVO.class);

        if (!Boolean.TRUE.equals(coupon.getSpecific())){
            return couponDetailVO;
        }

        List<CouponScope> list = couponScopeService.lambdaQuery()
                .eq(CouponScope::getCouponId, id)
                .list();

        if (CollUtils.isEmpty(list)){
            return couponDetailVO;
        }

        List<CouponScopeVO> scopeVOS = list.stream()
                .map(scope -> new CouponScopeVO(scope.getBizId(), categoryCache.getNameByLv3Id(scope.getBizId())))
                .collect(Collectors.toList());

        couponDetailVO.setScopes(scopeVOS);
        return couponDetailVO;
    }

    /**
     * 发放优惠券
     * @param dto
     */
    @Override
    public void beginIssue(CouponIssueFormDTO dto) {
        //1.1.查询优惠券
        Coupon coupon = getById(dto.getId());
        if (coupon == null){
            throw new BadRequestException("优惠券不存在!");
        }

        //1.2.只有待发放和暂停状态的优惠券才能发放
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE){
            throw new BizIllegalException("该状态的优惠券不能发放!");
        }

        //2.判断优惠券是否立即发放,开始发放时间为null或者发放时间小于当前时间,都代表立即发放
        LocalDateTime issueBeginTime = coupon.getIssueBeginTime();
        LocalDateTime now = LocalDateTime.now();

        boolean isBegin = issueBeginTime == null || !now.isBefore(issueBeginTime);

        //3.更新优惠券
        Coupon couponPO = BeanUtils.copyBean(dto, Coupon.class);
        if (isBegin){
            //如果要立刻发放,更新状态和发放时间
            couponPO.setStatus(CouponStatus.ISSUING);
            couponPO.setIssueBeginTime(now);
        }else {
            //如果不是立刻发放,修改状态为未开始
            couponPO.setStatus(CouponStatus.UN_ISSUE);
        }

        //4.更新数据库
        updateById(couponPO);

        //添加缓存
        if (isBegin){
            coupon.setIssueBeginTime(couponPO.getIssueBeginTime());
            coupon.setIssueEndTime(couponPO.getIssueEndTime());
            cacheCouponInfo(coupon);
        }

        //5.判断是否需要生成兑换码
        //只有待发放状态和手动兑换的优惠券才能生成兑换码
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT){
            //生成兑换码,需要知道优惠券的数量,过期时间以及对应的优惠券的id
            //过期时间的就是发放的结束时间
            coupon.setIssueEndTime(couponPO.getIssueEndTime());
            exchangeCodeService.asyncGenerateExchangeCode(coupon);
        }
    }

    /**
     * 将优惠券的信息添加到缓存中
     */
    private void cacheCouponInfo(Coupon coupon) {
        Map<String, String> map = new HashMap<>(4);
        map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueBeginTime())));
        map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueEndTime())));
        map.put("totalNum", String.valueOf(coupon.getTotalNum()));
        map.put("userLimit", String.valueOf(coupon.getUserLimit()));

        redisTemplate.opsForHash().putAll(PromotionConstants.COUPON_CACHE_KEY_PREFIX + coupon.getId(), map);
    }

    /**
     * 查询正在发放中的,手动领取的优惠券
     * @return
     */
    @Override
    public List<CouponVO> queryIssuingCoupon() {
        //1.1.查询正在发放的优惠券
        List<Coupon> coupons = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getType, ObtainType.PUBLIC)
                .list();

        //1.2.拿到发放中的优惠券的id集合
        List<Long> couponIds = coupons.stream().map(Coupon::getId).collect(Collectors.toList());

        //1.3.去用户券中查当前用户已经领取的优惠券
        List<UserCoupon> userCoupons = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .in(UserCoupon::getCouponId, couponIds)
                .list();

        //1.4.当前用户已经领取的优惠券的数量,根据couponId来分组,key是id,value是数量
        Map<Long, Long> issuedMap = userCoupons.stream()
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //1.5.当前用户已经领取的但未使用的优惠券的数量
        Map<Long, Long> unusedMap = userCoupons.stream()
                .filter(userCoupon -> userCoupon.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //数据封装
        List<CouponVO> couponVOS = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            CouponVO couponVO = BeanUtils.copyBean(coupon, CouponVO.class);

            //判断是否可以领取:已经被领取的数量 < 优惠券的总数量 && 当前用户领取的数量 < 每人限领的数量
            couponVO.setAvailable(coupon.getIssueNum() < coupon.getTotalNum()
                                    && issuedMap.getOrDefault(coupon.getId(), 0L) < coupon.getUserLimit());
            //判断是否可以使用:当前用户已经领取 && 未使用的优惠券数量> 0
            couponVO.setReceived(unusedMap.getOrDefault(coupon.getId(), 0L) > 0);

            couponVOS.add(couponVO);
        }

        return couponVOS;
    }

    /**
     *暂停发放优惠券
     * @param id
     */
    @Override
    @Transactional
    public void pauseIssueCouponById(Long id) {
        //根据id查询优惠券
        Coupon coupon = getById(id);
        if (coupon == null){
            throw new BizIllegalException("优惠券不存在!");
        }

        //只有发放中和未开始的优惠券才能被暂停
        if (coupon.getStatus() != CouponStatus.UN_ISSUE && coupon.getStatus() != CouponStatus.ISSUING){
            throw new BizIllegalException("只有发放中和未开始的优惠券才能被暂停");
        }
        //更新优惠券的状态为暂停
        boolean success = this.lambdaUpdate()
                .eq(Coupon::getId, id)
                .in(Coupon::getStatus, CouponStatus.UN_ISSUE, CouponStatus.ISSUING)
                .set(Coupon::getStatus, CouponStatus.PAUSE)
                .update();
        if (!success){
            throw new BizIllegalException("更新失败!");
        }

        //删除redis中的缓存
        redisTemplate.delete(PromotionConstants.COUPON_CACHE_KEY_PREFIX + id);
    }

    /**
     * 定时任务：将到达发放开始时间的优惠券状态从 UN_ISSUE 更新为 ISSUING
     */
    @Override
    @Transactional
    public void checkAndIssueCoupons() {
        //查询所有"未开始"且"开始时间已到"的优惠券
        List<Coupon> coupons = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.UN_ISSUE)
                .le(Coupon::getIssueBeginTime, LocalDateTime.now())
                .list();

        if (CollUtils.isEmpty(coupons)){
            return;
        }

        //转换成id
        List<Long> couponIds = coupons.stream().map(Coupon::getId).collect(Collectors.toList());

        //批量更新
        boolean success = this.lambdaUpdate()
                .eq(Coupon::getStatus, CouponStatus.UN_ISSUE)
                .set(Coupon::getStatus, CouponStatus.ISSUING)
                .in(Coupon::getId, couponIds)
                .update();

        if (!success){
            throw new BizIllegalException("发放失败!");
        }

        //写入缓存
        coupons.forEach(this::cacheCouponInfo);
    }

    /**
     * 定时任务：将到达发放结束时间的优惠券状态从 ISSUING 更新为 FINISHED
     */
    @Override
    @Transactional
    public void checkAndFinishCoupons() {
        List<Coupon> coupons = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .le(Coupon::getIssueEndTime, LocalDateTime.now())
                .list();
        if (CollUtils.isEmpty(coupons)){
            return;
        }

        List<Long> couponIds = coupons.stream().map(Coupon::getId).collect(Collectors.toList());

        boolean success = this.lambdaUpdate()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .set(Coupon::getStatus, CouponStatus.FINISHED)
                .in(Coupon::getId, couponIds)
                .update();

        if (!success){
            throw new BizIllegalException("结束发放失败!");
        }

        // 批量将未使用的兑换码标记为过期
        couponIds.forEach(couponId -> {
            exchangeCodeService.lambdaUpdate()
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.EXPIRED)
                    .eq(ExchangeCode::getExchangeTargetId, couponId)
                    .eq(ExchangeCode::getStatus, ExchangeCodeStatus.UNUSED)
                    .update();
        });

        //清理redis缓存
        couponIds.forEach(id -> redisTemplate.delete(PromotionConstants.COUPON_CACHE_KEY_PREFIX + id));
    }

    /**
     * 删除优惠券
     */
    @Transactional
    @Override
    public void deleteCouponById(Long id){
        if (id == null){
            throw new BadRequestException("优惠券id不能为空!");
        }
        Coupon coupon = getById(id);
        if (coupon == null){
            throw new BadRequestException("优惠券不存在!");
        }
        removeById(id);

        couponScopeService.remove(new QueryWrapper<CouponScope>().eq("coupon_id", id));
    }
}
