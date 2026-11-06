package com.delta.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record GuestLookupRequest(
        @NotBlank @Pattern(regexp = "^1[0-9]{10}$", message = "请输入正确的手机号") String phone,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6,12}$", message = "查单码须为6至12位数字或字母") String lookupCode) {
    @Override public String toString() { return "GuestLookupRequest[redacted]"; }
}
