package com.delta.order.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComplaintAttachmentTokenCodecTest {
    private final ComplaintAttachmentTokenCodec codec = new ComplaintAttachmentTokenCodec(
            "test-secret-that-is-long-enough-for-attachment-tokens-2026");

    @Test
    void tokenIsOpaqueAndAuthenticatesItsMetadata() {
        ComplaintAttachmentToken expected = new ComplaintAttachmentToken(7L, "USER", 10L, "tenant-a",
                "0123456789abcdef0123456789abcdef.jpg", 1_700_000_000_000L);

        String token = codec.encode(expected);

        assertThat(token).startsWith("ca1.");
        assertThat(token).doesNotContain("/file/", "tenant-a", "0123456789abcdef");
        assertThat(codec.decode(token)).contains(expected);
    }

    @Test
    void malformedOrTamperedTokensAreRejected() {
        String token = codec.encode(new ComplaintAttachmentToken(7L, "USER", 10L, "tenant-a",
                "0123456789abcdef0123456789abcdef.png", 1_700_000_000_000L));

        assertThat(codec.decode("/file/2026/11/21/public.png")).isEmpty();
        assertThat(codec.decode(token.substring(0, token.length() - 1) + "A")).isEmpty();
        assertThat(codec.decode(token + "x")).isEmpty();
        assertThat(new ComplaintAttachmentTokenCodec("another-secret-that-is-long-enough-2026")
                .decode(token)).isEmpty();
    }
}
