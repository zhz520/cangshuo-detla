package com.delta.user.dto;
import jakarta.validation.constraints.*;
public record PasswordResetRequest(@NotBlank @Pattern(regexp="1[0-9]{10}") String phone,
 @NotBlank @Size(max=36) String requestId,@NotBlank @Pattern(regexp="[0-9]{6}") String code,
 @NotBlank @Size(max=32) String password,@NotBlank @Size(max=32) String confirmation) {}
