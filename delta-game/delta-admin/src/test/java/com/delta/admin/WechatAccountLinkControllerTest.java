package com.delta.admin;

import com.delta.admin.controller.WechatAccountLinkController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WechatAccountLinkControllerTest {
    @Test void oldAuthenticatedMergeRouteCannotSkipTicketAndConsent() {
        var result = new WechatAccountLinkController().retired();
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getData()).isNull();
    }
}
