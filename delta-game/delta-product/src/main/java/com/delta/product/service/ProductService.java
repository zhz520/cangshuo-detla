package com.delta.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.delta.product.entity.Product;

import java.math.BigDecimal;
import java.util.List;

public interface ProductService extends IService<Product> {
    /** 上下架 */
    void updateStatus(Long id, Integer status);

    int updateStatusBatch(List<Long> ids, Integer status);

    int updateCategoryBatch(List<Long> ids, Long categoryId);

    int adjustPriceBatch(List<Long> ids, String mode, BigDecimal value);

    int copyAsDrafts(List<Long> ids);

    int deleteBatch(List<Long> ids);
}
