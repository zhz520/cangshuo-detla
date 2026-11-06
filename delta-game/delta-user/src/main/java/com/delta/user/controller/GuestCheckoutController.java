package com.delta.user.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.user.dto.GuestLookupRequest;
import com.delta.user.dto.GuestOrderCreateRequest;
import com.delta.user.dto.GuestOrderSession;
import com.delta.user.service.GuestCheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/guest")
@RequiredArgsConstructor
public class GuestCheckoutController {
    private final GuestCheckoutService guestCheckout;

    @PostMapping("/orders")
    public R<GuestOrderSession> create(@Valid @RequestBody GuestOrderCreateRequest request, HttpServletRequest web) {
        return R.ok(guestCheckout.create(request, web.getRemoteAddr()));
    }

    @PostMapping("/lookup")
    public R<GuestOrderSession> lookup(@Valid @RequestBody GuestLookupRequest request, HttpServletRequest web) {
        return R.ok(guestCheckout.lookup(request, web.getRemoteAddr()));
    }

    @PostMapping("/bind")
    public R<Void> bind(@Valid @RequestBody GuestLookupRequest request) {
        guestCheckout.bind(SecurityUtils.getUserId(), request);
        return R.ok();
    }
}
