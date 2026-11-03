package com.delta.user.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class H5LoginRequest {
    @NotBlank @Pattern(regexp="1[0-9]{10}")
    private String phone;
    @NotBlank @Pattern(regexp="[0-9]{6}")
    private String code;
    private Boolean privacyAccepted;
    @NotBlank @Size(max=36)
    private String requestId;
}
