package com.delta.product.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.delta.common.utils.ImageListUtils;
import com.delta.common.exception.BusinessException;
import com.delta.product.entity.Product;
import com.delta.product.enums.ProductLimitTypeEnum;
import com.delta.product.mapper.ProductMapper;
import com.delta.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    @Override
    public boolean save(Product product) {
        normalizeProduct(product);
        return super.save(product);
    }

    @Override
    public boolean updateById(Product product) {
        normalizeProduct(product);
        return super.updateById(product);
    }

    @Override
    public boolean saveOrUpdate(Product product) {
        normalizeProduct(product);
        return super.saveOrUpdate(product);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Product product = new Product();
        product.setId(id);
        product.setStatus(status);
        super.updateById(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateStatusBatch(List<Long> ids, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BusinessException(400, "商品状态无效");
        List<Product> products = requireProducts(ids);
        for (Product product : products) {
            product.setStatus(status);
            updateById(product);
        }
        return products.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateCategoryBatch(List<Long> ids, Long categoryId) {
        if (categoryId == null) throw new BusinessException(400, "请选择商品分类");
        List<Product> products = requireProducts(ids);
        for (Product product : products) {
            product.setCategoryId(categoryId);
            updateById(product);
        }
        return products.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int adjustPriceBatch(List<Long> ids, String mode, BigDecimal value) {
        if (value == null) throw new BusinessException(400, "请输入调整数值");
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!Set.of("SET", "PERCENT").contains(normalized)) throw new BusinessException(400, "价格调整方式无效");
        if ("SET".equals(normalized) && value.signum() < 0) throw new BusinessException(400, "商品价格不能为负数");
        if ("PERCENT".equals(normalized) && value.compareTo(BigDecimal.valueOf(-100)) < 0) {
            throw new BusinessException(400, "价格下调比例不能低于 100%");
        }
        List<Product> products = requireProducts(ids);
        for (Product product : products) {
            BigDecimal current = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
            BigDecimal next = "SET".equals(normalized)
                    ? value
                    : current.multiply(BigDecimal.ONE.add(value.movePointLeft(2)));
            if (next.signum() < 0 || next.compareTo(BigDecimal.valueOf(100_000_000L)) > 0) {
                throw new BusinessException(400, "商品价格超出允许范围");
            }
            product.setPrice(next.setScale(2, RoundingMode.HALF_UP));
            updateById(product);
        }
        return products.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int copyAsDrafts(List<Long> ids) {
        List<Product> products = requireProducts(ids);
        for (Product source : products) {
            Product copy = new Product();
            copy.setCategoryId(source.getCategoryId());
            String suffix = "（副本）";
            String name = source.getName() == null ? "" : source.getName();
            copy.setName((name.length() + suffix.length() > 128 ? name.substring(0, 128 - suffix.length()) : name) + suffix);
            copy.setSubtitle(source.getSubtitle());
            copy.setDescription(source.getDescription());
            copy.setCoverImage(source.getCoverImage());
            copy.setImages(source.getImages());
            copy.setPrice(source.getPrice());
            copy.setStatus(0);
            copy.setSortOrder(source.getSortOrder());
            copy.setSalesCount(0);
            copy.setIsRecommend(0);
            copy.setRecommendCategoryId(null);
            copy.setAvgRating(BigDecimal.ZERO);
            copy.setReviewCount(0);
            copy.setCommissionRate(source.getCommissionRate());
            copy.setPerUserLimitType(source.getPerUserLimitType());
            copy.setPerUserLimitEnabled(source.getPerUserLimitEnabled());
            copy.setPerUserLimitCount(source.getPerUserLimitCount());
            save(copy);
        }
        return products.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBatch(List<Long> ids) {
        List<Product> products = requireProducts(ids);
        if (!products.isEmpty()) removeByIds(products.stream().map(Product::getId).toList());
        return products.size();
    }

    private List<Product> requireProducts(List<Long> ids) {
        List<Product> products = listByIds(ids);
        if (products.size() != ids.size()) {
            throw new BusinessException(409, "部分商品不存在或已删除，请刷新列表后重试");
        }
        return products;
    }

    private void normalizeProduct(Product product) {
        if (product == null) {
            return;
        }
        product.setImages(ImageListUtils.normalizeSafe(product.getImages(), 12));
        normalizePerUserLimit(product);
    }

    private void normalizePerUserLimit(Product product) {
        ProductLimitTypeEnum limitType = ProductLimitTypeEnum.resolve(
                product.getPerUserLimitType(),
                product.getPerUserLimitEnabled(),
                product.getPerUserLimitCount());
        product.setPerUserLimitType(limitType.getCode());
        if (!limitType.isLimited()) {
            product.setPerUserLimitEnabled(0);
            product.setPerUserLimitCount(null);
            return;
        }
        product.setPerUserLimitEnabled(1);
        product.setPerUserLimitCount(1);
    }

}
