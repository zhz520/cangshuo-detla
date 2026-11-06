package com.delta.user.dto;

public record GuestOrderSession(String token, Long guestUserId, String nickname, String maskedPhone, Long orderId) {}
