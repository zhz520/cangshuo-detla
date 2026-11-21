package com.delta.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A private evidence token or an explicit unavailable legacy marker. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintAttachmentView {
    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_UNAVAILABLE = "UNAVAILABLE";

    private String token;
    private String kind;
}
