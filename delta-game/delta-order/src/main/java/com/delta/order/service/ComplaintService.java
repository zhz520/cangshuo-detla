package com.delta.order.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.delta.common.domain.PageQuery;
import com.delta.order.dto.ComplaintDetailVO;
import com.delta.order.entity.Complaint;

import java.util.Collection;
import java.util.List;

public interface ComplaintService extends IService<Complaint> {
    boolean canAccessOrder(Long orderId, Long actorId, String actorType);

    boolean canAccessUser(Long actorId, String actorType, Long ownerUserId);

    List<Long> ownerScope(Long actorId, String actorType);

    Page<Complaint> pageForOwners(PageQuery query, Collection<Long> ownerIds);

    ComplaintDetailVO getDetail(Complaint complaint);

    void createComplaint(Complaint complaint, Long actorId, String actorType);

    void appealComplaint(Complaint complaint, Complaint appeal);
}
