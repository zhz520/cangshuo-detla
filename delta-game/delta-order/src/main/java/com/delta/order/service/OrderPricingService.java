package com.delta.order.service;

import com.delta.common.domain.PlayerPriceConfig;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.utils.Money;
import com.delta.order.dto.OrderQuote;
import com.delta.product.entity.Product;
import com.delta.product.service.ProductService;
import com.delta.system.service.SysConfigService;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderPricingService {
    private final ProductService products;
    private final CrossModuleMapper crossModuleMapper;
    private final SysConfigService configs;
    private final JdbcTemplate jdbc;
    private final CouponRedemptionService couponRedemption;

    public OrderQuote quote(Long productId,Long playerId,Integer levelId) {
        return quote(productId,playerId,levelId,null);
    }

    public OrderQuote quote(Long productId,Long playerId,Integer levelId,Long userId) {
        return quote(productId,playerId,levelId,userId,null);
    }

    public OrderQuote quote(Long productId,Long playerId,Integer levelId,Long userId,Long couponId) {
        if(productId==null || productId<=0 || playerId!=null&&playerId<=0 || levelId!=null&&levelId<=0)
            throw new BusinessException(400,"请选择有效的商品和打手");
        if(playerId!=null && levelId!=null) throw new BusinessException(400,"指定打手和指定等级不能同时选择");
        Product product=products.getById(productId);
        if(product==null) throw new BusinessException(404,"商品不存在");
        if(!Integer.valueOf(1).equals(product.getStatus())) throw new BusinessException(400,"商品已下架");
        BigDecimal base=Money.positive(product.getPrice()).setScale(2,RoundingMode.UNNECESSARY);
        PlayerPriceConfig config=null;
        if(playerId!=null) {
            config=crossModuleMapper.selectOrderablePlayerPrice(playerId);
            if(config==null) throw new BusinessException(400,"指定打手暂不可接单，请重新选择");
            int max=Integer.parseInt(configs.getConfigValue("order.max_active_per_player","5"));
            if(crossModuleMapper.selectPlayerActiveOrders(playerId)>=max)
                throw new BusinessException(400,"指定打手当前订单已满，请重新选择");
        } else if(levelId!=null) {
            config=crossModuleMapper.selectEnabledLevelPrice(levelId);
            if(config==null) throw new BusinessException(400,"打手等级已停用，请重新选择");
        }
        BigDecimal rate=config==null?BigDecimal.ZERO:config.getPriceIncreaseRate();
        if(rate==null || rate.signum()<0 || rate.compareTo(new BigDecimal("100"))>0 || rate.stripTrailingZeros().scale()>2)
            throw new BusinessException(400,"打手涨价设置异常，请联系客服");
        BigDecimal extra=base.multiply(rate).movePointLeft(2).setScale(2,RoundingMode.HALF_UP);
        BigDecimal gross=Money.positive(base.add(extra));
        String memberTier=null;
        BigDecimal memberRate=BigDecimal.ONE;
        BigDecimal memberDiscount=BigDecimal.ZERO;
        if(userId!=null&&"true".equalsIgnoreCase(configs.getConfigValue("membership.enabled","false"))){
            try{
                BigDecimal net=jdbc.queryForObject(
                        "SELECT COALESCE(SUM(COALESCE(p.amount,0)-COALESCE(p.refund_amount,0)),0) FROM payment p "+
                                "JOIN `order` o ON o.id=p.order_id WHERE o.user_id=? AND p.status IN ('PAID','REFUNDED')",
                        BigDecimal.class,userId);
                List<MemberTier> tiers=JSON.parseArray(configs.getConfigValue("membership.tiers",
                        "[{\"code\":\"NORMAL\",\"name\":\"普通会员\",\"minSpend\":0,\"discountRate\":1}]"),MemberTier.class);
                if(tiers!=null&&!tiers.isEmpty()){
                    tiers.sort(Comparator.comparing(MemberTier::minSpend));
                    MemberTier selected=tiers.get(0);
                    for(MemberTier tier:tiers)if(net!=null&&net.compareTo(tier.minSpend())>=0)selected=tier;
                    memberTier=selected.name();
                    memberRate=selected.discountRate()==null?BigDecimal.ONE:selected.discountRate();
                    if(memberRate.signum()<0||memberRate.compareTo(BigDecimal.ONE)>0)memberRate=BigDecimal.ONE;
                    memberDiscount=gross.multiply(BigDecimal.ONE.subtract(memberRate)).setScale(2,RoundingMode.HALF_UP);
                    gross=Money.positive(gross.subtract(memberDiscount));
                    memberDiscount=Money.positive(base.add(extra)).subtract(gross);
                }
            }catch(Exception ignored){}
        }
        Long usedCoupon=null;
        String couponName=null;
        BigDecimal couponDiscount=BigDecimal.ZERO;
        if(couponId!=null){
            if(userId==null)throw new BusinessException(401,"登录后才能使用优惠券");
            CouponRedemptionService.CouponDiscount coupon=couponRedemption.quote(userId,couponId,gross);
            usedCoupon=coupon.couponId();couponName=coupon.name();couponDiscount=coupon.discount();
            gross=Money.positive(gross.subtract(couponDiscount));
        }
        OrderQuote quote=new OrderQuote();
        quote.setProductId(productId);quote.setProductName(product.getName());
        quote.setDesignatedPlayerId(playerId);quote.setPlayerLevelId(levelId);
        quote.setPlayerLevelName(config==null?null:config.getLevelName());
        quote.setBaseAmount(base);quote.setPriceIncreaseRate(rate);quote.setPriceIncreaseAmount(extra);
        quote.setMemberTier(memberTier);quote.setMemberDiscountRate(memberRate);quote.setMemberDiscountAmount(memberDiscount);
        quote.setCouponId(usedCoupon);quote.setCouponName(couponName);quote.setCouponDiscountAmount(couponDiscount);
        quote.setAmount(gross);
        return quote;
    }

    public record MemberTier(String code,String name,BigDecimal minSpend,BigDecimal discountRate){}
}
