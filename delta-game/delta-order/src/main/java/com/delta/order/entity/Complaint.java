package com.delta.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.delta.common.domain.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("complaint")
public class Complaint extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private Long playerId;
    private String type;
    private String content;
    /** Private tokens only. Legacy public URLs are never serialized to clients. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String images;
    private String expectedResult;
    private String status;
    private String result;
    private BigDecimal refundAmount;
    private String resultReason;
    private String playerPenalty;
    private Long operatorId;
    private LocalDateTime resolvedAt;
    private String appealReason;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String appealImages;
    private String appealResult;
    private Long appealOperatorId;
    private LocalDateTime appealResolvedAt;
    /** 客服查看时间（未读红点：未查看的待处理投诉） */
    private LocalDateTime csReadAt;

    /** Request-only list of opaque upload tokens; never persisted to this entity's table. */
    @TableField(exist = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<String> attachmentIds;
}
