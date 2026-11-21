package com.delta.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.entity.Complaint;
import com.delta.order.service.ComplaintService;
import com.delta.order.dto.ComplaintDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/order/complaint")
@RequiredArgsConstructor
public class ComplaintController {
    private final ComplaintService complaintService;
    private final com.delta.common.security.service.RateLimitService limits;

    @PostMapping
    public R<Void> create(@RequestBody Complaint complaint) {
        Complaint supplied=complaint;complaint=new Complaint();complaint.setOrderId(supplied.getOrderId());complaint.setType(supplied.getType());
        complaint.setContent(supplied.getContent());complaint.setImages(supplied.getImages());complaint.setExpectedResult(supplied.getExpectedResult());
        complaint.setAttachmentIds(supplied.getAttachmentIds());
        if(!java.util.Set.of("服务态度","服务质量","代练质量","账号安全","违规操作","其他").contains(complaint.getType()))
            return R.fail(400,"投诉类型不正确");
        if(complaint.getExpectedResult()!=null&&(complaint.getExpectedResult().length()>50||!java.util.Set.of("退款","重新服务","赔偿","道歉").contains(complaint.getExpectedResult())))
            return R.fail(400,"期望结果不正确");
        if(complaint.getContent()==null || complaint.getContent().isBlank() || complaint.getContent().length()>2000) return R.fail(400,"投诉内容长度不符合要求");
        Long actorId = SecurityUtils.getUserId();
        String actorType = SecurityUtils.getUserType();
        limits.check("complaint-create:"+actorId,10,3600);
        if (!complaintService.canAccessOrder(complaint.getOrderId(), actorId, actorType)) {
            return R.fail(404, "订单不存在");
        }
        complaintService.createComplaint(complaint, actorId, actorType);
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<ComplaintDetailVO> detail(@PathVariable Long id) {
        Complaint c = complaintService.getById(id);
        if (c == null) return R.fail("投诉不存在");
        if (!complaintService.canAccessUser(SecurityUtils.getUserId(), SecurityUtils.getUserType(), c.getUserId())) {
            return R.fail("无权查看");
        }
        return R.ok(complaintService.getDetail(c));
    }

    @GetMapping("/my")
    public R<Page<Complaint>> my(PageQuery query) {
        Long actorId = SecurityUtils.getUserId();
        List<Long> owners = complaintService.ownerScope(actorId, SecurityUtils.getUserType());
        return R.ok(complaintService.pageForOwners(query, owners));
    }

    @PostMapping("/{id}/appeal")
    public R<Void> appeal(@PathVariable Long id, @RequestBody Complaint appeal) {
        Complaint c = complaintService.getById(id);
        if (c == null) return R.fail("投诉不存在");
        if (!complaintService.canAccessUser(SecurityUtils.getUserId(), SecurityUtils.getUserType(), c.getUserId())) {
            return R.fail("无权操作");
        }
        limits.check("complaint-appeal:"+SecurityUtils.getUserId(),10,3600);
        complaintService.appealComplaint(c, appeal, SecurityUtils.getUserId(), SecurityUtils.getUserType());
        return R.ok();
    }
}
