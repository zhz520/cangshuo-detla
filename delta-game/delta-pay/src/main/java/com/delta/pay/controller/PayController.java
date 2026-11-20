package com.delta.pay.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.pay.entity.Payment;
import com.delta.pay.entity.Transaction;
import com.delta.pay.service.PaymentService;
import com.delta.pay.service.TransactionService;
import com.delta.pay.service.WechatOfficialPayService;
import com.delta.pay.service.WechatPayScene;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderService;
import com.delta.system.service.SysConfigService;
import com.delta.user.entity.User;
import com.delta.user.service.UserService;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.h5.model.H5Info;
import com.wechat.pay.java.service.payments.h5.model.PrepayResponse;
import com.wechat.pay.java.service.payments.h5.model.SceneInfo;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction.TradeStateEnum;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@RestController
@RequestMapping("/pay")
@RequiredArgsConstructor
public class PayController {
    private final PaymentService paymentService;
    private final TransactionService transactionService;
    private final OrderService orderService;
    private final WxPayConfiguration wxPayConfiguration;
    private final UserService userService;
    private final CrossModuleMapper crossModuleMapper;
    private final WechatOfficialPayService officialPay;
    private final SysConfigService sysConfigService;

    @Value("${wx.pay.key-store-path:}")
    private String configuredKeyStorePath;

    /** 文件上传目录已经由生产 Compose 持久化，支付证书默认放在其 private 子目录。 */
    @Value("${FILE_UPLOAD_PATH:./uploads/}")
    private String fileUploadPath;
    @Autowired(required = false)
    private TenantProperties tenantProperties;

    @PostMapping("/wx/{orderId}")
    public R<Map<String, String>> wxPay(@PathVariable Long orderId) {
        if (!channelEnabled("wx.pay.miniapp-enabled")) return R.fail(403, "小程序支付已关闭");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) {
            return R.fail("微信支付未启用，请使用余额支付或联系管理员配置支付证书");
        }

        Long userId = SecurityUtils.getUserId();
        Payment payment = paymentService.createWxPayment(orderId, userId, WechatPayScene.MINIAPP, clients.getConfig().appId());

        User user = userService.getById(userId);
        Order order = orderService.getById(orderId);
        String description = (order != null && order.getProductName() != null && !order.getProductName().isEmpty())
                ? order.getProductName() : "订单支付";
        try {
            PrepayWithRequestPaymentResponse response = createPrepayOrder(clients.getJsapiService(), clients.getConfig(), clients.getConfig().appId(),
                    payment, user.getOpenid(), description);

            payment.setWxPrepayId(response.getPackageVal().replace("prepay_id=", ""));
            paymentService.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payment>().eq(Payment::getId,payment.getId()).set(Payment::getWxPrepayId,payment.getWxPrepayId()));

            Map<String, String> payParams = new HashMap<>();
            payParams.put("timeStamp", response.getTimeStamp());
            payParams.put("nonceStr", response.getNonceStr());
            payParams.put("package", response.getPackageVal());
            payParams.put("signType", response.getSignType());
            payParams.put("paySign", response.getPaySign());
            return R.ok(payParams);
        } catch (ServiceException e) {
            log.error("微信支付V3下单失败, orderId={}, code={}", orderId, e.getErrorCode(), e);
            if ("ORDERPAID".equals(e.getErrorCode())) {
                return R.fail(4010, "该订单已支付，请勿重复支付");
            }
            return R.fail("发起微信支付失败，请稍后重试");
        } catch (Exception e) {
            log.error("微信支付V3下单失败, orderId={}", orderId, e);
            return R.fail("发起微信支付失败，请稍后重试");
        }
    }

    /** 小程序余额充值，充值套餐由后台配置，支付金额只取实际充值金额。 */
    @PostMapping("/recharge/wx/{packageId}")
    public R<Map<String, String>> rechargeWxPay(@PathVariable String packageId) {
        if (!channelEnabled("wx.pay.miniapp-enabled")) return R.fail(403, "小程序支付已关闭");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) {
            return R.fail("微信支付未启用，暂无法充值");
        }
        Long userId = SecurityUtils.getUserId();
        Payment payment = paymentService.createRechargePayment(packageId, userId, WechatPayScene.MINIAPP, clients.getConfig().appId());
        User user = userService.getById(userId);
        if (user == null || user.getOpenid() == null || user.getOpenid().isBlank()) {
            return R.fail("用户信息不完整，无法发起支付");
        }
        try {
            PrepayWithRequestPaymentResponse response = createPrepayOrder(clients.getJsapiService(), clients.getConfig(), clients.getConfig().appId(),
                    payment, user.getOpenid(), "余额充值");
            payment.setWxPrepayId(response.getPackageVal().replace("prepay_id=", ""));
            paymentService.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payment>().eq(Payment::getId,payment.getId()).set(Payment::getWxPrepayId,payment.getWxPrepayId()));
            Map<String, String> payParams = new HashMap<>();
            payParams.put("paymentNo", payment.getPaymentNo());
            payParams.put("timeStamp", response.getTimeStamp());
            payParams.put("nonceStr", response.getNonceStr());
            payParams.put("package", response.getPackageVal());
            payParams.put("signType", response.getSignType());
            payParams.put("paySign", response.getPaySign());
            return R.ok(payParams);
        } catch (ServiceException e) {
            log.error("余额充值微信下单失败, packageId={}, code={}", packageId, e.getErrorCode(), e);
            return R.fail("发起余额充值失败，请稍后重试");
        } catch (Exception e) {
            log.error("余额充值微信下单失败, packageId={}", packageId, e);
            return R.fail("发起余额充值失败，请稍后重试");
        }
    }

    /** H5 余额充值，供浏览器端复用同一充值套餐。 */
    @PostMapping("/recharge/h5/{packageId}")
    public R<Map<String, String>> rechargeH5Pay(@PathVariable String packageId, HttpServletRequest httpRequest) {
        if (!channelEnabled("wx.pay.h5-enabled")) return R.fail(403, "微信外 H5 支付已关闭");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) {
            return R.fail("微信支付未启用，暂无法充值");
        }
        Payment payment = paymentService.createRechargePayment(packageId, SecurityUtils.getUserId(), WechatPayScene.H5, clients.getConfig().appId());
        try {
            com.wechat.pay.java.service.payments.h5.model.PrepayRequest request =
                    new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
            request.setAppid(clients.getConfig().appId());
            request.setMchid(clients.getConfig().mchId());
            request.setDescription("余额充值");
            request.setOutTradeNo(payment.getPaymentNo());
            request.setNotifyUrl(clients.getConfig().notifyUrl());
            com.wechat.pay.java.service.payments.h5.model.Amount amount =
                    new com.wechat.pay.java.service.payments.h5.model.Amount();
            amount.setTotal(com.delta.common.utils.Money.cents(payment.getAmount()));
            amount.setCurrency("CNY");
            request.setAmount(amount);
            SceneInfo sceneInfo = new SceneInfo();
            sceneInfo.setPayerClientIp(getClientIp(httpRequest));
            H5Info h5Info = new H5Info();
            h5Info.setType(clients.getConfig().h5Type());
            h5Info.setAppName(clients.getConfig().h5AppName());
            sceneInfo.setH5Info(h5Info);
            request.setSceneInfo(sceneInfo);
            PrepayResponse response = clients.getH5Service().prepay(request);
            Map<String, String> result = new HashMap<>();
            result.put("h5Url", response.getH5Url());
            result.put("paymentNo", payment.getPaymentNo());
            return R.ok(result);
        } catch (Exception e) {
            log.error("余额充值 H5 下单失败, packageId={}", packageId, e);
            return R.fail("发起余额充值失败，请稍后重试");
        }
    }

    /**
     * 微信 H5 支付。返回 h5Url，H5 前端跳转到该地址完成支付。
     */
    @PostMapping("/h5/{orderId}")
    public R<Map<String, String>> h5Pay(@PathVariable Long orderId, HttpServletRequest httpRequest) {
        if (!channelEnabled("wx.pay.h5-enabled")) return R.fail(403, "微信外 H5 支付已关闭");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) {
            return R.fail("微信支付未启用，请使用余额支付或联系管理员配置支付证书");
        }

        Long userId = SecurityUtils.getUserId();
        Payment payment = paymentService.createWxPayment(orderId, userId, WechatPayScene.H5, clients.getConfig().appId());
        Order order = orderService.getById(orderId);
        String description = order != null && order.getProductName() != null && !order.getProductName().isBlank()
                ? order.getProductName() : "订单支付";
        try {
            H5Service h5Service = clients.getH5Service();
            com.wechat.pay.java.service.payments.h5.model.PrepayRequest request =
                    new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
            request.setAppid(clients.getConfig().appId());
            request.setMchid(clients.getConfig().mchId());
            request.setDescription(description);
            request.setOutTradeNo(payment.getPaymentNo());
            request.setNotifyUrl(clients.getConfig().notifyUrl());

            com.wechat.pay.java.service.payments.h5.model.Amount amount =
                    new com.wechat.pay.java.service.payments.h5.model.Amount();
            amount.setTotal(com.delta.common.utils.Money.cents(payment.getAmount()));
            amount.setCurrency("CNY");
            request.setAmount(amount);

            SceneInfo sceneInfo = new SceneInfo();
            sceneInfo.setPayerClientIp(getClientIp(httpRequest));
            H5Info h5Info = new H5Info();
            h5Info.setType(clients.getConfig().h5Type());
            h5Info.setAppName(clients.getConfig().h5AppName());
            sceneInfo.setH5Info(h5Info);
            request.setSceneInfo(sceneInfo);

            PrepayResponse response = h5Service.prepay(request);
            Map<String, String> result = new HashMap<>();
            result.put("h5Url", response.getH5Url());
            result.put("paymentNo", payment.getPaymentNo());
            return R.ok(result);
        } catch (ServiceException e) {
            log.error("微信 H5 支付下单失败, orderId={}, code={}", orderId, e.getErrorCode(), e);
            if ("ORDERPAID".equals(e.getErrorCode())) {
                return R.fail(4010, "该订单已支付，请勿重复支付");
            }
            return R.fail("发起微信 H5 支付失败，请稍后重试");
        } catch (Exception e) {
            log.error("微信 H5 支付下单失败, orderId={}", orderId, e);
            return R.fail("发起微信 H5 支付失败，请稍后重试");
        }
    }

    /** 微信内置浏览器公众号支付：返回网页授权地址，授权回调页直接调起 JSAPI。 */
    @PostMapping("/wx/official/{orderId}")
    public R<Map<String, String>> officialPay(@PathVariable Long orderId) {
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) return R.fail("微信支付未启用，请使用余额支付或联系管理员配置支付证书");
        if (!officialPay.runtime().ready()) return R.fail("微信公众号支付尚未启用或配置不完整");
        Long userId = SecurityUtils.getUserId();
        Payment payment = paymentService.createWxPayment(orderId, userId, WechatPayScene.OFFICIAL_ACCOUNT, officialPay.runtime().appId());
        String authorizeUrl = officialPay.authorizationUrl(payment.getPaymentNo(), userId, "ORDER", "/pages/order/detail?id=" + orderId);
        return R.ok(Map.of("authorizeUrl", authorizeUrl, "paymentNo", payment.getPaymentNo()));
    }

    /** 微信内置浏览器公众号余额充值。 */
    @PostMapping("/recharge/official/{packageId}")
    public R<Map<String, String>> rechargeOfficialPay(@PathVariable String packageId) {
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) return R.fail("微信支付未启用，暂无法充值");
        if (!officialPay.runtime().ready()) return R.fail("微信公众号支付尚未启用或配置不完整");
        Long userId = SecurityUtils.getUserId();
        Payment payment = paymentService.createRechargePayment(packageId, userId, WechatPayScene.OFFICIAL_ACCOUNT, officialPay.runtime().appId());
        String authorizeUrl = officialPay.authorizationUrl(payment.getPaymentNo(), userId, "RECHARGE", "/pages/wallet/index");
        return R.ok(Map.of("authorizeUrl", authorizeUrl, "paymentNo", payment.getPaymentNo()));
    }

    /** 微信内置浏览器公众号支付补交打手押金。 */
    @PostMapping("/player-deposit/official")
    public R<Map<String, String>> playerDepositOfficial(@RequestBody(required = false) Map<String, String> body) {
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) return R.fail("微信支付未启用，暂无法支付押金");
        if (!officialPay.runtime().ready()) return R.fail("微信公众号支付尚未启用或配置不完整");
        Long actorId = SecurityUtils.getUserId();
        Long userId = "PLAYER".equals(SecurityUtils.getUserType()) ? crossModuleMapper.selectUserIdByPlayerId(actorId) : actorId;
        if (userId == null) return R.fail("未找到押金所属用户");
        Payment payment = paymentService.createPlayerDepositPayment(userId, WechatPayScene.OFFICIAL_ACCOUNT, officialPay.runtime().appId());
        String scene = body == null ? "" : String.valueOf(body.getOrDefault("scene", ""));
        String returnPath = "WITHDRAW".equals(scene) ? "/pages-player/withdraw/index" : "/pages-player/apply/index?depositReturn=1";
        return R.ok(Map.of(
                "authorizeUrl", officialPay.authorizationUrl(payment.getPaymentNo(), userId, "PLAYER_DEPOSIT", returnPath),
                "paymentNo", payment.getPaymentNo()));
    }

    /** 微信网页授权回调页。页面本身由微信内置浏览器打开，不通过 Bearer 鉴权。 */
    @GetMapping("/wx/official/callback")
    public ResponseEntity<String> officialPayCallback(@RequestParam(required = false) String code,
                                                       @RequestParam(required = false) String state) {
        WechatOfficialPayService.OAuthIdentity identity = null;
        try {
            identity = officialPay.exchange(code, state);
            Payment payment = paymentService.getOne(new LambdaQueryWrapper<Payment>()
                    .eq(Payment::getPaymentNo, identity.state().paymentNo()).last("LIMIT 1"));
            if (payment == null || !identity.state().userId().equals(payment.getUserId()) || !"PAYING".equals(payment.getStatus())) {
                throw new RuntimeException("payment state mismatch");
            }
            if (!identity.state().kind().equals(payment.getBizType())) throw new RuntimeException("payment kind mismatch");
            WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
            if (!wxPayConfiguration.isEnabled() || clients == null) throw new RuntimeException("payment disabled");
            WechatOfficialPayService.RuntimeConfig official = officialPay.runtime();
            if (!WechatPayScene.OFFICIAL_ACCOUNT.payMethod().equals(payment.getPayMethod())
                    || payment.getWxAppId() == null || !official.appId().equals(payment.getWxAppId())) {
                throw new RuntimeException("payment channel mismatch");
            }
            String description = switch (payment.getBizType()) {
                case "ORDER" -> {
                    Order order = orderService.getById(payment.getOrderId());
                    if (order == null || !payment.getUserId().equals(order.getUserId())) throw new RuntimeException("order mismatch");
                    yield order.getProductName() == null || order.getProductName().isBlank() ? "订单支付" : order.getProductName();
                }
                case "RECHARGE" -> "余额充值";
                case "PLAYER_DEPOSIT" -> "打手押金";
                default -> throw new RuntimeException("unsupported payment type");
            };
            PrepayWithRequestPaymentResponse response = createPrepayOrder(clients.getJsapiService(), clients.getConfig(),
                    official.appId(), payment, identity.openid(), description);
            Map<String, String> params = paymentParams(response);
            paymentService.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payment>()
                    .eq(Payment::getId, payment.getId()).set(Payment::getWxPrepayId,
                            response.getPackageVal().replace("prepay_id=", "")));
            String returnUrl = returnUrl(identity.state());
            return html(officialPay.paymentPage(official.appId(), params, returnUrl));
        } catch (ValidationException error) {
            log.error("微信支付应答验签失败，请从商户 {} 的商户平台重新下载微信支付公钥和公钥 ID",
                    wxPayConfiguration.getRuntimeConfig().mchId(), error);
            String returnUrl = identity == null ? "" : returnUrl(identity.state());
            return ResponseEntity.status(503).contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .cacheControl(CacheControl.noStore())
                    .header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
                    .header("Referrer-Policy", "no-referrer")
                    .body(officialPay.errorPage("微信支付配置校验失败，请联系管理员处理。", returnUrl));
        } catch (Exception error) {
            log.error("微信公众号支付授权回调失败", error);
            String returnUrl = identity == null ? "" : returnUrl(identity.state());
            return ResponseEntity.badRequest().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .cacheControl(CacheControl.noStore())
                    .header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
                    .header("Referrer-Policy", "no-referrer")
                    .body(officialPay.errorPage("微信支付暂时不可用，请返回订单重试或联系客服。", returnUrl));
        }
    }

    @PostMapping("/balance/{orderId}")
    public R<Payment> balancePay(@PathVariable Long orderId) {
        if(!paymentService.closeWxPayment(orderId,SecurityUtils.getUserId())) return R.fail(4010,"该订单已通过微信支付，请刷新订单");
        return R.ok(paymentService.createBalancePayment(orderId, SecurityUtils.getUserId()));
    }

    /** 打手入驻押金支付（金额来自 sys_config: player.deposit_amount），返回支付参数及paymentNo供提交申请时校验 */
    @PostMapping("/player-deposit")
    public R<Map<String, Object>> playerDeposit() {
        if (!channelEnabled("wx.pay.miniapp-enabled")) return R.fail(403, "小程序支付已关闭");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) {
            return R.fail("微信支付未启用，暂无法支付押金");
        }

        Long actorId = SecurityUtils.getUserId();
        Long userId = "PLAYER".equals(SecurityUtils.getUserType()) ? crossModuleMapper.selectUserIdByPlayerId(actorId) : actorId;
        if(userId==null) return R.fail("未找到押金所属用户");
        Payment payment = paymentService.createPlayerDepositPayment(userId, WechatPayScene.MINIAPP, clients.getConfig().appId());
        User user = userService.getById(userId);
        if (user == null || user.getOpenid() == null) {
            return R.fail("用户信息不完整，无法发起支付");
        }
        try {
            PrepayWithRequestPaymentResponse response = createPrepayOrder(clients.getJsapiService(), clients.getConfig(), clients.getConfig().appId(),
                    payment, user.getOpenid(), "打手入驻押金");
            payment.setWxPrepayId(response.getPackageVal().replace("prepay_id=", ""));
            paymentService.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payment>().eq(Payment::getId,payment.getId()).set(Payment::getWxPrepayId,payment.getWxPrepayId()));
            Map<String, Object> result = new HashMap<>();
            result.put("paymentNo", payment.getPaymentNo());
            result.put("timeStamp", response.getTimeStamp());
            result.put("nonceStr", response.getNonceStr());
            result.put("package", response.getPackageVal());
            result.put("signType", response.getSignType());
            result.put("paySign", response.getPaySign());
            return R.ok(result);
        } catch (ServiceException e) {
            log.error("打手押金微信支付下单失败, userId={}, code={}", userId, e.getErrorCode(), e);
            return R.fail("发起微信支付失败，请稍后重试");
        } catch (Exception e) {
            log.error("打手押金微信支付下单失败, userId={}", userId, e);
            return R.fail("发起微信支付失败，请稍后重试");
        }
    }

    /** 浏览器押金支付复用押金支付单、服务端金额计算及微信验签回调。 */
    @PostMapping("/player-deposit/h5")
    public R<Map<String, String>> playerDepositH5(HttpServletRequest httpRequest) {
        if (!channelEnabled("wx.pay.h5-enabled")) return R.fail(403, "微信外 H5 支付已关闭");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (!wxPayConfiguration.isEnabled() || clients == null) {
            return R.fail("微信支付未启用，暂无法支付押金");
        }
        Long actorId = SecurityUtils.getUserId();
        Long userId = "PLAYER".equals(SecurityUtils.getUserType())
                ? crossModuleMapper.selectUserIdByPlayerId(actorId) : actorId;
        if (userId == null) return R.fail("未找到押金所属用户");
        Payment payment = paymentService.createPlayerDepositPayment(userId, WechatPayScene.H5, clients.getConfig().appId());
        try {
            com.wechat.pay.java.service.payments.h5.model.PrepayRequest request =
                    new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
            request.setAppid(clients.getConfig().appId());
            request.setMchid(clients.getConfig().mchId());
            request.setDescription("打手押金");
            request.setOutTradeNo(payment.getPaymentNo());
            request.setNotifyUrl(clients.getConfig().notifyUrl());
            com.wechat.pay.java.service.payments.h5.model.Amount amount =
                    new com.wechat.pay.java.service.payments.h5.model.Amount();
            amount.setTotal(com.delta.common.utils.Money.cents(payment.getAmount()));
            amount.setCurrency("CNY");
            request.setAmount(amount);
            SceneInfo scene = new SceneInfo();
            scene.setPayerClientIp(getClientIp(httpRequest));
            H5Info info = new H5Info();
            info.setType(clients.getConfig().h5Type());
            info.setAppName(clients.getConfig().h5AppName());
            scene.setH5Info(info);
            request.setSceneInfo(scene);
            PrepayResponse response = clients.getH5Service().prepay(request);
            return R.ok(Map.of("h5Url", response.getH5Url(), "paymentNo", payment.getPaymentNo()));
        } catch (Exception e) {
            log.error("打手押金 H5 下单失败, userId={}", userId, e);
            return R.fail("发起押金 H5 支付失败，请稍后重试");
        }
    }

    @PostMapping("/wx/notify")
    public org.springframework.http.ResponseEntity<Map<String, String>> wxNotify(HttpServletRequest httpRequest, @RequestBody String jsonData) {
        Map<String, String> response = new HashMap<>();
        try {
            String serial = httpRequest.getHeader("Wechatpay-Serial");
            String nonce = httpRequest.getHeader("Wechatpay-Nonce");
            String timestamp = httpRequest.getHeader("Wechatpay-Timestamp");
            String signature = httpRequest.getHeader("Wechatpay-Signature");

            log.info("收到微信支付V3回调, serial={}", serial);

            paymentService.handleWxPayV3Notify(jsonData, serial, nonce, timestamp, signature);

            response.put("code", "SUCCESS");
            response.put("message", "成功");
            return org.springframework.http.ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("处理微信支付V3回调失败", e);
            response.put("code", "FAIL");
            response.put("message", "处理失败");
            return org.springframework.http.ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/wx/refund-notify")
    public org.springframework.http.ResponseEntity<Map<String,String>> refundNotify(HttpServletRequest request,@RequestBody String body) {
        try {
            paymentService.handleWxRefundV3Notify(body,request.getHeader("Wechatpay-Serial"),request.getHeader("Wechatpay-Nonce"),request.getHeader("Wechatpay-Timestamp"),request.getHeader("Wechatpay-Signature"));
            return org.springframework.http.ResponseEntity.ok(Map.of("code","SUCCESS"));
        } catch(Exception e){log.error("微信退款通知处理失败: errorType={}, reason={}",e.getClass().getSimpleName(),e.getMessage(),e);return org.springframework.http.ResponseEntity.status(500).body(Map.of("code","FAIL","message","处理失败"));}
    }

    @GetMapping("/transactions")
    public R<Page<Transaction>> transactions(PageQuery query) {
        Long userId = SecurityUtils.getUserId();
        return R.ok(transactionService.page(new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getUserId, userId)
                        .eq(Transaction::getUserType, SecurityUtils.getUserType())
                        .orderByDesc(Transaction::getCreatedAt)));
    }

    private PrepayWithRequestPaymentResponse createPrepayOrder(JsapiServiceExtension jsapiServiceExtension,
                                                               WxPayConfiguration.RuntimeConfig wxPayConfiguration,
                                                               String appId,
                                                               Payment payment,
                                                               String openid,
                                                               String description) {
        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId);
        request.setMchid(wxPayConfiguration.mchId());
        request.setDescription(description);
        request.setOutTradeNo(payment.getPaymentNo());
        request.setNotifyUrl(wxPayConfiguration.notifyUrl());

        Amount amount = new Amount();
        amount.setTotal(com.delta.common.utils.Money.cents(payment.getAmount()));
        amount.setCurrency("CNY");
        request.setAmount(amount);

        Payer payer = new Payer();
        payer.setOpenid(openid);
        request.setPayer(payer);

        return jsapiServiceExtension.prepayWithRequestPayment(request);
    }

    private Map<String, String> paymentParams(PrepayWithRequestPaymentResponse response) {
        Map<String, String> params = new HashMap<>();
        params.put("timeStamp", response.getTimeStamp());
        params.put("nonceStr", response.getNonceStr());
        params.put("package", response.getPackageVal());
        params.put("signType", response.getSignType());
        params.put("paySign", response.getPaySign());
        return params;
    }

    private String returnUrl(WechatOfficialPayService.CallbackState state) {
        return state.returnOrigin() + "/#" + state.returnPath();
    }

    private ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/config/upload")
    public R<String> uploadCertificate(@RequestParam("configKey") String configKey,
                                       @RequestParam("file") MultipartFile file) throws Exception {
        if (!"wx.pay.private-key-path".equals(configKey) && !"wx.pay.public-key-path".equals(configKey)) {
            return R.fail("不允许上传该类型的支付文件");
        }
        if (file == null || file.isEmpty()) {
            return R.fail("请选择证书文件");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            return R.fail("证书文件不能超过2MB");
        }
        String originalName = file.getOriginalFilename();
        String lowerName = originalName == null ? "" : originalName.toLowerCase();
        if (!(lowerName.endsWith(".pem") || lowerName.endsWith(".key") || lowerName.endsWith(".txt"))) {
            return R.fail("仅支持 pem、key 或 txt 格式的证书文件");
        }
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String text=new String(file.getBytes(),java.nio.charset.StandardCharsets.US_ASCII);
        if("wx.pay.private-key-path".equals(configKey) ? !text.contains("-----BEGIN PRIVATE KEY-----") : !text.contains("-----BEGIN PUBLIC KEY-----"))
            return R.fail(400,"证书内容不符合要求，请上传对应的PEM密钥");
        String tenantId=tenantProperties!=null&&tenantProperties.isEnabled()?TenantContext.requireId():null;
        Path dir = resolveKeyStoreDirectory(configuredKeyStorePath, fileUploadPath,tenantId).resolve(datePath);
        Files.createDirectories(dir);
        try {Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));} catch(UnsupportedOperationException ignored) {}
        String ext = lowerName.substring(lowerName.lastIndexOf('.'));
        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + ext);
        file.transferTo(target.toFile());
        try {Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));} catch(UnsupportedOperationException ignored) {}
        return R.ok(target.toAbsolutePath().toString());
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private boolean channelEnabled(String configKey) {
        String value = sysConfigService.getConfigValue(configKey, "true");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }

    static Path resolveKeyStoreDirectory(String configuredPath, String uploadPath) {
        return resolveKeyStoreDirectory(configuredPath,uploadPath,null);
    }

    static Path resolveKeyStoreDirectory(String configuredPath, String uploadPath, String tenantId) {
        String configured = configuredPath == null ? "" : configuredPath.trim();
        Path base=configured.isEmpty()
                ?Paths.get(uploadPath == null || uploadPath.isBlank() ? "./uploads/" : uploadPath,"private","wxpay")
                :Paths.get(configured);
        if(tenantId!=null&&!tenantId.isBlank())base=base.resolve(tenantId);
        return base.toAbsolutePath().normalize();
    }
}
