package com.delta.order.dto;

import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderProgress;
import lombok.Data;

import java.util.List;

/**
 * 投诉详情视图对象（客服端与用户端共用）
 */
@Data
public class ComplaintDetailVO {
    private Complaint complaint;
    private Order order;

    private String userNickname;
    private String userAvatar;

    private String playerNickname;
    private String playerAvatar;

    /** 订单进度列表（按时间升序） */
    private List<OrderProgress> progress;

    /** 当前投诉的私有附件 token；旧公开图片只返回不可用标记，不回传 URL。 */
    private List<ComplaintAttachmentView> attachments;

    /** 申诉阶段的私有附件 token；旧公开图片只返回不可用标记，不回传 URL。 */
    private List<ComplaintAttachmentView> appealAttachments;
}

