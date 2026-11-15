package com.delta.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.delta.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("category")
public class Category extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String icon;
    private Integer sortOrder;
    private Integer status;
    private Long parentId;
    /** 前端分类图标显示尺寸（rpx），默认 56。 */
    private Integer iconSize;
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private java.util.List<Category> children;
}
