package com.delta.pay.service;

/** 微信支付场景；落库到 pay_method，避免不同 AppID/OpenID 复用同一商户订单号。 */
public enum WechatPayScene {
    MINIAPP("WECHAT_MINIAPP"),
    OFFICIAL_ACCOUNT("WECHAT_OFFICIAL"),
    H5("WECHAT_H5");

    private final String payMethod;

    WechatPayScene(String payMethod) {
        this.payMethod = payMethod;
    }

    public String payMethod() {
        return payMethod;
    }
}
