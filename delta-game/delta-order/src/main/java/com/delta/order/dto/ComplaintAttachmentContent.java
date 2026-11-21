package com.delta.order.dto;

/** Decoded private image returned to an authorized caller. */
public record ComplaintAttachmentContent(byte[] bytes, String contentType) {
}
