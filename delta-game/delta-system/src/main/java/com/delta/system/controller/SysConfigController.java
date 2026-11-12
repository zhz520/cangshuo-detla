package com.delta.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Arrays;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.notification.NotificationTemplateSources;
import com.delta.system.entity.SysConfig;
import com.delta.system.dto.RechargePackage;
import com.delta.system.service.SysConfigService;
import com.delta.system.support.SysConfigMetadataRepair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
@Slf4j
public class SysConfigController {
    private final SysConfigService sysConfigService;

    /** 兼容旧版 application.yml：首次生成后台配置项时沿用原有开关值。 */
    @Value("${wx.pay.enabled:false}")
    private boolean wxPayEnabledFallback;

    @GetMapping("/list")
    public R<List<SysConfig>> list() {
        ensureBuiltinConfigs();
        List<SysConfig> list=sysConfigService.list(new LambdaQueryWrapper<SysConfig>().orderByAsc(SysConfig::getId));
        repairMetadata(list);
        list.forEach(c -> {if(secret(c.getConfigKey()) && c.getConfigValue()!=null && !c.getConfigValue().isBlank()) c.setConfigValue("********");});
        return R.ok(list);
    }

    /**
     * Repairs metadata written by old ad-hoc SQL scripts that did not set the MySQL client charset.
     * Only names, groups and remarks are touched; business values and secrets are never rewritten here.
     */
    private void repairMetadata(List<SysConfig> configs) {
        for (SysConfig config : configs) {
            String name = SysConfigMetadataRepair.name(config.getConfigKey(), config.getConfigName());
            String group = SysConfigMetadataRepair.group(config.getConfigKey(), config.getConfigGroup());
            String remark = SysConfigMetadataRepair.remark(config.getConfigKey(), config.getRemark());
            boolean nameChanged = !Objects.equals(name, config.getConfigName());
            boolean groupChanged = !Objects.equals(group, config.getConfigGroup());
            boolean remarkChanged = !Objects.equals(remark, config.getRemark());
            if (!nameChanged && !groupChanged && !remarkChanged) continue;

            SysConfig update = new SysConfig();
            update.setId(config.getId());
            if (nameChanged) {
                update.setConfigName(name);
                config.setConfigName(name);
            }
            if (groupChanged) {
                update.setConfigGroup(group);
                config.setConfigGroup(group);
            }
            if (remarkChanged) {
                update.setRemark(remark);
                config.setRemark(remark);
            }
            try {
                sysConfigService.updateById(update);
                log.warn("Repaired mojibake system config metadata for {}", config.getConfigKey());
            } catch (Exception error) {
                log.warn("Failed to persist repaired system config metadata for {}: {}",
                        config.getConfigKey(), error.getMessage());
            }
        }
    }

    @PutMapping
    public R<Void> update(@RequestBody SysConfig config) {
        updateValue(config);
        return R.ok();
    }

    @OpLog(module = "system", operation = "修改系统配置")
    @PutMapping("/batch")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public R<Void> batchUpdate(@RequestBody List<SysConfig> configs) {
        if(configs==null || configs.size()>200) return R.fail(400,"配置数量无效");
        configs.forEach(this::updateValue);
        return R.ok();
    }

    @GetMapping("/recharge-packages")
    public R<List<RechargePackage>> rechargePackages() {
        ensureBuiltinConfigs();
        return R.ok(sysConfigService.getRechargePackages(false));
    }

    @PutMapping("/recharge-packages")
    @OpLog(module = "system", operation = "修改余额充值套餐")
    public R<Void> updateRechargePackages(@RequestBody List<RechargePackage> packages) {
        try {
            sysConfigService.saveRechargePackages(packages);
            return R.ok();
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /** 公开接口：获取站点配置（无需登录） */
    @GetMapping("/site")
    public R<Map<String, String>> getSiteConfig() {
        List<SysConfig> list = sysConfigService.list(
                new LambdaQueryWrapper<SysConfig>().in(SysConfig::getConfigKey,java.util.List.of("site_name","site_subtitle","site_logo","site_admin_title","site.staff_web_url")));
        Map<String, String> map = list.stream()
                .collect(Collectors.toMap(SysConfig::getConfigKey, SysConfig::getConfigValue, (a, b) -> b));
        // 打手端抽佣比例（百分比）：从结算配置 settlement.commission_rate 转换，0.1 -> 10
        String commissionRateStr = sysConfigService.getConfigValue("settlement.commission_rate", "0.1");
        try {
            int percent = (int) Math.round(Double.parseDouble(commissionRateStr) * 100);
            map.put("player_commission_rate", String.valueOf(percent));
        } catch (NumberFormatException ignored) {
            map.put("player_commission_rate", "10");
        }
        // 打手押金开关
        map.put("player_deposit_required", sysConfigService.getConfigValue("player.deposit_required", "true"));
        map.put("player_deposit_mode", sysConfigService.getConfigValue("player.deposit_mode",
                "true".equalsIgnoreCase(sysConfigService.getConfigValue("player.deposit_required","true"))?"REQUIRED":"NONE"));
        // 打手押金金额（元）
        map.put("player_deposit_amount", sysConfigService.getConfigValue("player.deposit_amount", "100"));
        map.put("order_purchase_enabled", sysConfigService.getConfigValue("order.purchase_enabled", "true"));
        map.put("order_guest_checkout_enabled", sysConfigService.getConfigValue("order.guest_checkout_enabled", "false"));
        map.put("player_directory_enabled", sysConfigService.getConfigValue("player.directory.enabled", "true"));
        // 微信支付总开关（支付模块运行时动态读取）
        map.put("wx_pay_enabled", sysConfigService.getConfigValue("wx.pay.enabled", "false"));
        map.put("wx_pay_miniapp_enabled", sysConfigService.getConfigValue("wx.pay.miniapp-enabled", "true"));
        map.put("wx_pay_h5_enabled", sysConfigService.getConfigValue("wx.pay.h5-enabled", "false"));
        String officialEnabled = sysConfigService.getConfigValue("wx.pay.official-enabled", "false");
        String officialAppId = sysConfigService.getConfigValue("wx.pay.official-appid", "").trim();
        String officialSecret = sysConfigService.getConfigValue("wx.pay.official-appsecret", "").trim();
        String officialOrigin = sysConfigService.getConfigValue("wx.pay.official-return-origin", "").trim();
        map.put("wx_pay_official_enabled", Boolean.toString("true".equalsIgnoreCase(officialEnabled)
                && !officialAppId.isBlank() && !officialSecret.isBlank() && !officialOrigin.isBlank()));
        map.put("notice_popup_every_visit", sysConfigService.getConfigValue("notice.popup_every_visit", "true"));
        map.put("tech_support_text", sysConfigService.getConfigValue("site.tech_support_text", "KK云网络提供技术支持"));
        map.put("tech_support_url", sysConfigService.getConfigValue("site.tech_support_url", ""));
        map.put("staff_web_url", sysConfigService.getConfigValue("site.staff_web_url", ""));
        map.put("customer_service_mode", sysConfigService.getConfigValue("customer_service.mode", "internal"));
        map.put("customer_service_external_url", sysConfigService.getConfigValue("customer_service.external_url", ""));
        map.put("customer_service_home_icon_visible", sysConfigService.getConfigValue("customer_service.home_icon_visible", "true"));
        return R.ok(map);
    }

    private void ensureBuiltinConfigs() {
        ensureConfig("email.notifications.enabled","启用邮件通知","false","boolean","邮件通知","邮件通知总开关，仅由邮件通知专用页面维护");
        ensureConfig("email.notifications.host","SMTP 服务器","","text","邮件通知","客户自有 SMTP 服务器地址");
        ensureConfig("email.notifications.port","SMTP 端口","465","number","邮件通知","通常 TLS 使用 465，STARTTLS 使用 587");
        ensureConfig("email.notifications.security","SMTP 加密方式","TLS","text","邮件通知","TLS 或 STARTTLS");
        ensureConfig("email.notifications.username","SMTP 邮箱账号","","text","邮件通知","用于 SMTP 身份认证的邮箱账号");
        ensureConfig("email.notifications.password","SMTP 密码","","text","邮件通知","保存后不回显，仅使用邮件通知专用接口维护");
        ensureConfig("email.notifications.from_address","发件邮箱","","text","邮件通知","运营通知的 From 地址");
        ensureConfig("email.notifications.sender_name","发件人名称","运营通知","text","邮件通知","收件人看到的发件人名称");
        ensureConfig("email.notifications.recipients","运营收件邮箱","[]","json","邮件通知","最多 20 个邮箱，自动去重");
        ensureConfig("email.notifications.events","邮件通知事件","[]","json","邮件通知","只排队已勾选的事件，由专用页面维护");
        ensureConfig("settlement.cs_rate","客服提成占俱乐部抽成比例","0","text","结算配置","0至1，按下单时快照计算；如0.2表示俱乐部抽成的20%计入客服提成台账");
        ensureConfig("settlement.hold_hours","订单收益售后观察期","72","number","结算配置","订单确认后先进入待结算收益，观察期结束且无投诉、退款时转为可提现余额；单位小时，0至720");
        ensureConfig("player.invite_required","仅允许邀请入驻","false","boolean","入驻配置","开启后入驻必须使用有效邀请链接");
        ensureConfig("risk.freeze_threshold","违规冻结阈值","3","text","内容风控","系统拦截或人工明确标记的违规累计达到此次数后冻结接单");
        ensureConfig("risk.media.review_mode","图片语音审核方式","AUTO","text","内容风控","AUTO：优先自动检测，未配置或不可用时转人工；MANUAL：不调用自动检测，直接进入人工审核");
        ensureConfig("risk.media.endpoint","图片语音系统检测接口","","text","内容风控","HTTPS检测网关，执行图片OCR与语音转写风控，返回verdict: SAFE或BLOCK；未配置或不可用时转人工审核");
        ensureConfig("risk.media.token","检测接口令牌","","text","内容风控","仅发送到已配置的HTTPS检测接口，保存后不回显");
        ensureConfig("wechat.official.enabled","启用公众号模板通知","false","boolean","公众号通知","公众号模板消息与原小程序订阅通知相互独立");
        ensureConfig("wechat.official.app_id","公众号AppID","","text","公众号通知","填写具备模板消息权限的公众号AppID");
        ensureConfig("wechat.official.app_secret","公众号AppSecret","","text","公众号通知","保存后不回显");
        ensureConfig("wechat.official.redirect_url","客户与打手绑定回调页","","text","公众号通知","客户 H5 的HTTPS地址，例如 https://shop.example.com/# 前的主页面地址；授权后会回到客户 H5 并继续完成客户或打手绑定");
        syncConfigMetadata("wechat.official.redirect_url","客户与打手绑定回调页","客户 H5 的HTTPS地址，例如 https://shop.example.com/# 前的主页面地址；授权后会回到客户 H5 并继续完成客户或打手绑定");
        ensureConfig("wechat.official.staff_redirect_url","管理员与客服绑定回调页","","text","公众号通知","工作人员后台的HTTPS绑定页，例如 https://staff.example.com/h5/wechat-binding；旧 /mobile/wechat-binding 配置会自动改写；不含#和查询参数，并在微信后台配置该后台域名为网页授权域名");
        syncConfigMetadata("wechat.official.staff_redirect_url","管理员与客服绑定回调页","工作人员后台的HTTPS绑定页，例如 https://staff.example.com/h5/wechat-binding；旧 /mobile/wechat-binding 配置会自动改写；不含#和查询参数，并在微信后台配置该后台域名为网页授权域名");
        migrateLegacyStaffRedirect();
        ensureConfig("wechat.official.templates.user","客户专属模板组","{}","json","公众号通知","按事件直接填写微信模板ID和参数；支持当前官方thing/character_string/time/amount等字段类型");
        ensureConfig("wechat.official.templates.player","打手专属模板组","{}","json","公众号通知","按事件直接填写微信模板ID和参数；支持当前官方thing/character_string/time/amount等字段类型");
        ensureConfig("wechat.official.templates.admin","管理员专属模板组","{}","json","公众号通知","按事件直接填写微信模板ID和参数；支持当前官方thing/character_string/time/amount等字段类型");
        ensureConfig("wechat.official.templates.cs","客服专属模板组","{}","json","公众号通知","按事件直接填写微信模板ID和参数；支持当前官方thing/character_string/time/amount等字段类型");
        for(String event:java.util.List.of("new_order","order_accepted","order_completed","material_reviewed","withdraw_reviewed"))ensureConfig("wechat.official.template."+event,"模板映射："+event,"{}","json","公众号通知","JSON：templateId及fields；系统数据源可在公众号通知配置页按分类查看，字段名需与微信模板一致");
        ensureConfig("site.tech_support_text", "技术支持文字", "KK云网络提供技术支持", "text", "站点配置",
                "显示在用户、打手和客服“我的”页面底部，最多80个字符");
        ensureConfig("site.tech_support_url", "技术支持链接", "", "text", "站点配置",
                "可填写 http:// 或 https:// 开头的链接；留空时底部文字不可点击");
        ensureConfig("site.staff_web_url", "工作人员后台地址", "", "text", "站点配置",
                "管理员与客服使用的 HTTPS 后台 Origin，例如 https://jgsapi.example.com；客户H5旧工作人员入口会跳转到这里");
        ensureConfig("site.customer_web_url", "客户 H5 站点地址", "", "text", "站点配置",
                "微信网页登录完成后的客户 H5 HTTPS Origin，例如 https://shop.example.com；不含路径、查询参数和片段");
        ensureConfig("customer_service.mode", "客服接入方式", "internal", "text", "客服配置",
                "internal：使用系统内置客服；external：跳转第三方企业微信客服链接");
        ensureConfig("customer_service.external_url", "第三方企业微信客服链接", "", "text", "客服配置",
                "选择 external 时填写企业微信客服的 HTTPS 链接；未填写时仍使用内置客服");
        ensureConfig("customer_service.home_icon_visible", "首页客服悬浮图标", "true", "boolean", "客服配置",
                "关闭后首页不显示悬浮客服图标；商品详情页和“我的”页面客服入口不受影响");
        ensureConfig("notice.popup_every_visit", "首页公告每次刷新都弹窗", "true", "boolean", "公告配置",
                "开启后浏览器刷新会展示已发布的首页公告，切换页面不会重复弹出；关闭后仅在新版本公告首次出现时提醒");
        ensureConfig("order.purchase_enabled", "启用前台购买", "true", "boolean", "订单配置",
                "关闭后前台隐藏全部立即购买和立即点单按钮，并禁止创建新订单；已有订单仍可支付和处理");
        ensureConfig("order.guest_checkout_enabled", "允许游客下单", "false", "boolean", "订单配置",
                "开启后未登录用户可用手机号和查单码创建游客订单；游客订单使用独立影子账户，不写入正式会员统计");
        ensureConfig("order.cooperation.enabled", "启用协同打单", "true", "boolean", "订单配置",
                "关闭后不再接受新的队友邀请；已经确认的队伍仍可继续履约、退款和结算");
        ensureConfig("auth.phone.enabled", "启用手机验证码登录", "true", "boolean", "账号与登录",
                "关闭后网页端不再提供手机验证码登录；密码辅助入口和微信登录分别受独立开关控制");
        ensureConfig("auth.password.enabled", "启用密码辅助登录", "true", "boolean", "账号与登录",
                "关闭后网页端不显示密码登录入口；已设置密码的账号仍可通过其他已启用方式登录");
        ensureConfig("auth.wechat.enabled", "启用网页微信登录", "false", "boolean", "账号与登录",
                "开启后还需配置公众号 AppID 和 AppSecret；仅影响网页登录，不改变公众号通知绑定");
        ensureConfig("auth.primary_method", "网页主登录方式", "PHONE", "text", "账号与登录",
                "可选 PHONE 或 WECHAT；微信未完成配置时客户端自动回退可用登录方式");
        for(String[] item:java.util.List.of(
                new String[]{"identity.auto.enabled","启用自动二要素实名","false","boolean","实名配置","开启后仍受授权中心服务余额和实名策略约束"},
                new String[]{"identity.prompt_mode","实名提醒方式","OFF","text","实名配置","OFF/REMIND/REQUIRED；REQUIRED 只限制配置指定的业务动作"},
                new String[]{"membership.enabled","启用用户会员等级","false","boolean","会员配置","关闭后停止新增会员权益，既有等级和历史记录保留"},
                new String[]{"membership.tiers","会员等级规则","[{\"code\":\"NORMAL\",\"name\":\"普通会员\",\"minSpend\":0,\"discountRate\":1},{\"code\":\"SILVER\",\"name\":\"白银会员\",\"minSpend\":500,\"discountRate\":0.98},{\"code\":\"GOLD\",\"name\":\"黄金会员\",\"minSpend\":2000,\"discountRate\":0.95}]","json","会员配置","按有效净消费成长；minSpend 单位元，discountRate 为0到1之间的折扣率"},
                new String[]{"marketing.coupon.issue_enabled","启用优惠券发放","false","boolean","营销配置","关闭后停止领券和新发券，已发有效券按原规则处理"},
                new String[]{"marketing.coupon.redeem_enabled","启用优惠券核销","true","boolean","营销配置","紧急停用开关；关闭后新订单不能使用优惠券"},
                new String[]{"marketing.invite.enabled","启用邀请奖励","false","boolean","营销配置","停止新邀请归因和新奖励，已满足资格的奖励继续履约"},
                new String[]{"marketing.invite.reward_amount","邀请首单奖励金额","10","number","营销配置","被邀请人首个合格订单确认后记录的奖励金额，单位元"},
                new String[]{"display.spend_rank.enabled","启用消费排行","false","boolean","展示配置","关闭后隐藏榜单及公开接口，基础财务数据保留"},
                new String[]{"display.purchase_feed.enabled","启用最近购买动态","false","boolean","展示配置","关闭后停止公开展示，不停止订单和支付处理"}))
            ensureConfig(item[0],item[1],item[2],item[3],item[4],item[5]);
        ensureConfig("player.directory.enabled", "展示打手页面", "true", "boolean", "打手配置",
                "关闭后隐藏用户端底部打手入口，并阻止直接打开打手列表或详情页面");
        ensureConfig("sms.aliyun.otp_enabled","启用短信验证码登录","false","boolean","短信配置","仅在验证码模板和短信风控验收后开启");
        ensureConfig("sms.aliyun.template_code.verification","短信验证码模板 Code","","text","短信配置","已审核模板，使用code作为验证码参数名");
        ensureConfig("player.deposit_mode", "打手押金政策",
                "true".equalsIgnoreCase(sysConfigService.getConfigValue("player.deposit_required","true"))?"REQUIRED":"NONE",
                "text", "打手配置", "REQUIRED：入驻必须缴纳；NONE：全部不收；BEFORE_WITHDRAW：入驻免交，提现前按等级补交");
        ensureConfig("player.deposit_amount", "打手入驻押金金额", "100", "number", "打手配置",
                "开启打手押金时的应支付金额，单位：元");
        ensureConfig("withdraw.require_identity_match", "提现校验收款人实名", "true", "boolean", "提现配置",
                "开启后收款账户姓名必须与已通过实名或入驻真实姓名一致，避免换绑他人账户收款");
        ensureConfig("wallet.recharge.packages", "余额充值套餐",
                "[{\"id\":\"100\",\"name\":\"基础套餐\",\"amount\":100,\"bonus\":10,\"enabled\":true,\"sort\":1},{\"id\":\"300\",\"name\":\"进阶套餐\",\"amount\":300,\"bonus\":40,\"enabled\":true,\"sort\":2},{\"id\":\"500\",\"name\":\"畅玩套餐\",\"amount\":500,\"bonus\":100,\"enabled\":true,\"sort\":3}]",
                "json", "余额配置", "配置用户充值金额及赠送余额，单位：元");
        ensureConfig("wx.pay.enabled", "启用微信支付", String.valueOf(wxPayEnabledFallback), "boolean", "微信支付配置",
                "关闭后小程序和 H5 均不可发起微信支付，但余额支付不受影响");
        ensureConfig("wx.pay.miniapp-enabled", "启用小程序支付", "true", "boolean", "微信支付配置",
                "独立开关；开启后所有支付页面均可选择小程序支付");
        ensureConfig("wx.pay.h5-enabled", "启用 H5 支付", "false", "boolean", "微信支付配置",
                "仅在 H5 站点已实际部署并可访问时开启；未开启时移动端浏览器回退公众号二维码");
        ensureConfig("wx.pay.official-enabled", "启用微信公众号支付", "false", "boolean", "微信支付配置",
                "独立开关；开启后 H5 支付入口优先使用公众号支付，不按是否微信内置浏览器隐藏");
        ensureConfig("wx.pay.appid", "小程序支付 AppID", "", "text", "微信支付配置",
                "仅小程序支付使用；须与商户号绑定。公众号支付请填写下方独立公众号 AppID");
        ensureConfig("wx.pay.official-appid", "公众号支付 AppID", "", "text", "微信支付配置",
                "公众号支付使用；必须是已完成认证、已绑定商户号且配置网页授权域名的公众号 AppID");
        ensureConfig("wx.pay.official-appsecret", "公众号 AppSecret", "", "text", "微信支付配置",
                "用于 snsapi_base 网页授权换取公众号 OpenID；保存后不回显");
        ensureConfig("wx.pay.official-return-origin", "公众号支付站点 Origin", "", "text", "微信支付配置",
                "填写 https://客户正式域名，不含路径；授权回调固定为 /api/pay/wx/official/callback");
        ensureConfig("wx.pay.mch-id", "微信支付商户号", "", "text", "微信支付配置",
                "微信支付商户平台的商户号");
        ensureConfig("wx.pay.notify-url", "支付回调地址", "", "text", "微信支付配置",
                "例如：https://你的域名/api/pay/wx/notify，必须是微信可访问的 HTTPS 地址");
        ensureConfig("wx.pay.refund-notify-url", "退款回调地址", "", "text", "微信支付配置",
                "可选；留空时由支付回调地址自动推导为 /api/pay/wx/refund-notify");
        ensureConfig("wx.pay.api-v3-key", "微信支付 API v3 密钥", "", "text", "微信支付配置",
                "微信商户平台 API 安全中设置的 API v3 密钥");
        ensureConfig("wx.pay.private-key-path", "商户私钥文件", "", "file", "微信支付配置",
                "上传 apiclient_key.pem 或 .key 文件，保存后立即生效");
        ensureConfig("wx.pay.cert-serial-no", "商户证书序列号", "", "text", "微信支付配置",
                "微信支付商户证书序列号");
        ensureConfig("wx.pay.public-key-path", "微信支付公钥文件", "", "file", "微信支付配置",
                "上传微信支付公钥 pub_key.pem，保存后立即生效");
        ensureConfig("wx.pay.public-key-id", "微信支付公钥 ID", "", "text", "微信支付配置",
                "微信支付平台展示的微信支付公钥 ID");
        ensureConfig("wx.pay.h5-type", "H5 客户端类型", "WAP", "text", "微信支付配置",
                "填写 WAP、IOS 或 ANDROID；普通手机浏览器使用 WAP");
        ensureConfig("wx.pay.h5-app-name", "H5 应用名称", "三角洲服务", "text", "微信支付配置",
                "微信 H5 支付场景中的应用名称");
        ensureConfig("sms.aliyun.enabled", "启用阿里云短信提醒", "false", "boolean", "短信配置",
                "开启后聊天页和后台业务事件均可发送短信提醒");
        ensureConfig("sms.aliyun.access_key_id", "阿里云 AccessKey ID", "", "text", "短信配置",
                "阿里云 RAM 用户的 AccessKey ID");
        ensureConfig("sms.aliyun.access_key_secret", "阿里云 AccessKey Secret", "", "text", "短信配置",
                "阿里云 RAM 用户的 AccessKey Secret");
        ensureConfig("sms.aliyun.endpoint", "阿里云短信 Endpoint", "dysmsapi.aliyuncs.com", "text", "短信配置",
                "短信服务默认 Endpoint，一般无需修改");
        ensureConfig("sms.aliyun.sign_name", "阿里云短信签名", "", "text", "短信配置",
                "已审核通过的短信签名");
        ensureConfig("sms.aliyun.template_code.cs_message_reminder", "消息提醒模板 Code", "SMS_504365043", "text", "短信配置",
                "用户、打手、客服端通用的消息提醒短信模板");
        ensureConfig("sms.aliyun.template_code.player_finish_order", "通知老板结单模板 Code", "SMS_504575043", "text", "短信配置",
                "打手端通知老板结单使用的固定内容短信模板");
        ensureConfig("sms.aliyun.template_code.player_order_assigned", "打手被指派模板 Code", "SMS_504890091", "text", "短信配置",
                "后台自动发送给被指派打手的固定内容短信模板");
        ensureConfig("sms.aliyun.template_code.player_teammate_invited", "打手被邀请模板 Code", "SMS_504775096", "text", "短信配置",
                "后台自动发送给被邀请打手的固定内容短信模板");
        ensureConfig("sms.notification.templates.user", "客户业务短信模板组", "{}", "json", "短信配置",
                "JSON对象；按new_order/order_status/refund_status/complaint_status/staff_review分组配置templateCode和fields");
        ensureConfig("sms.notification.templates.player", "打手业务短信模板组", "{}", "json", "短信配置",
                "JSON对象；按new_order/order_status/refund_status/complaint_status/staff_review分组配置templateCode和fields");
        ensureConfig("sms.notification.templates.admin", "管理员业务短信模板组", "{}", "json", "短信配置",
                "JSON对象；按new_order/order_status/refund_status/complaint_status/staff_review分组配置templateCode和fields");
        ensureConfig("sms.notification.templates.cs", "客服业务短信模板组", "{}", "json", "短信配置",
                "JSON对象；按new_order/order_status/refund_status/complaint_status/staff_review分组配置templateCode和fields");
        ensureConfig("sms.aliyun.cooldown_seconds", "客服短信提醒冷却秒数", "60", "number", "短信配置",
                "客服发送短信提醒的冷却时间");
        ensureConfig("sms.aliyun.user_player_cooldown_seconds", "用户/打手短信冷却秒数", "600", "number", "短信配置",
                "用户端和打手端全局发送短信提醒冷却时间");
        ensureConfig("sms.aliyun.player_fee", "打手短信提醒扣费金额", "0.05", "number", "短信配置",
                "打手每次成功发送短信提醒时从余额中扣除的金额");

        // 删除已废弃的配置项
        sysConfigService.remove(new LambdaQueryWrapper<SysConfig>()
                .in(SysConfig::getConfigKey, Arrays.asList(
                        "sms.aliyun.template_code.player_invite_login",
                        "sms.aliyun.template_code.user_allow_login",
                        "sms.aliyun.template_code.user_notify_player"
                )));
        // 将旧名称“客服消息提醒模板 Code”更新为“消息提醒模板 Code”
        SysConfig msgTpl = sysConfigService.getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "sms.aliyun.template_code.cs_message_reminder"));
        if (msgTpl != null && !"消息提醒模板 Code".equals(msgTpl.getConfigName())) {
            msgTpl.setConfigName("消息提醒模板 Code");
            msgTpl.setRemark("用户、打手、客服端通用的消息提醒短信模板");
            sysConfigService.updateById(msgTpl);
        }
        SysConfig wxAppId = sysConfigService.getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "wx.pay.appid"));
        if (wxAppId != null && !"小程序支付 AppID".equals(wxAppId.getConfigName())) {
            wxAppId.setConfigName("小程序支付 AppID");
            wxAppId.setRemark("仅小程序支付使用；公众号支付请填写独立的公众号 AppID");
            sysConfigService.updateById(wxAppId);
        }
        for (String[] meta : java.util.List.of(
                new String[]{"wx.pay.miniapp-enabled", "启用小程序支付", "独立开关；开启后所有支付页面均可选择小程序支付"},
                new String[]{"wx.pay.h5-enabled", "启用 H5 支付", "独立开关；开启后所有 H5 支付页面均可选择 H5 支付，不再按浏览器类型自动隐藏"},
                new String[]{"wx.pay.official-enabled", "启用微信公众号支付", "独立开关；开启后 H5 支付入口优先使用公众号支付，不按是否微信内置浏览器隐藏"})) {
            SysConfig toggle = sysConfigService.getOne(new LambdaQueryWrapper<SysConfig>()
                    .eq(SysConfig::getConfigKey, meta[0]));
            if (toggle != null && (!meta[1].equals(toggle.getConfigName()) || !meta[2].equals(toggle.getRemark()))) {
                toggle.setConfigName(meta[1]);
                toggle.setRemark(meta[2]);
                sysConfigService.updateById(toggle);
            }
        }
    }

    private boolean secret(String key) {return java.util.Set.of("wx.pay.api-v3-key","wx.pay.official-appsecret","sms.aliyun.access_key_secret","risk.media.token","wechat.official.app_secret","email.notifications.password").contains(key==null?"":key);}
    private void updateValue(SysConfig submitted) {
        SysConfig old=submitted.getId()==null?null:sysConfigService.getById(submitted.getId());
        if(old==null) throw new com.delta.common.exception.BusinessException(404,"配置项不存在");
        String key=old.getConfigKey(),value=submitted.getConfigValue();
        if(key!=null && key.startsWith("email.notifications.")) throw new com.delta.common.exception.BusinessException(409,"邮件通知配置请使用专用设置页面保存");
        if(com.delta.system.service.RewardGrabRulesService.KEY.equals(key)) return; // Edited through validated dedicated settings only.
        if(value==null || secret(key) && "********".equals(value)) return;
        if(value.length()>10000) throw new com.delta.common.exception.BusinessException(400,"配置内容过长");
        if("sms.aliyun.endpoint".equals(key) && !"dysmsapi.aliyuncs.com".equals(value)) throw new com.delta.common.exception.BusinessException(400,"短信服务地址无效");
        if("wx.pay.api-v3-key".equals(key) && !value.isEmpty() && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length!=32)
            throw new com.delta.common.exception.BusinessException(400,"微信API v3密钥须为32字节");
        if("wx.pay.official-return-origin".equals(key) && !value.isBlank())try{
            var uri=java.net.URI.create(value.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                    ||(uri.getPath()!=null&&!uri.getPath().isEmpty()&&!"/".equals(uri.getPath())))throw new IllegalArgumentException();
            value=uri.getScheme().toLowerCase()+"://"+uri.getRawAuthority();
        }catch(Exception e){throw new com.delta.common.exception.BusinessException(400,"公众号支付站点 Origin 须是不含路径的 HTTPS 地址");}
        if(java.util.Set.of("settlement.commission_rate","settlement.cs_rate").contains(key)) {
            try {var n=new java.math.BigDecimal(value);if(n.signum()<0 || n.compareTo(java.math.BigDecimal.ONE)>0) throw new IllegalArgumentException();}
            catch(Exception e){throw new com.delta.common.exception.BusinessException(400,"抽佣比例须在0至1之间");}
        }
        if("settlement.hold_hours".equals(key)) {
            try {int n=Integer.parseInt(value);if(n<0||n>720)throw new IllegalArgumentException();}
            catch(Exception e){throw new com.delta.common.exception.BusinessException(400,"售后观察期须为0至720小时");}
        }
        if("withdraw.require_identity_match".equals(key)&&!java.util.Set.of("true","false").contains(value))
            throw new com.delta.common.exception.BusinessException(400,"提现实名校验开关无效");
        if("auth.primary_method".equals(key)&&!java.util.Set.of("PHONE","WECHAT").contains(value))
            throw new com.delta.common.exception.BusinessException(400,"网页主登录方式只能是 PHONE 或 WECHAT");
        if("identity.prompt_mode".equals(key)&&!java.util.Set.of("OFF","REMIND","REQUIRED").contains(value))
            throw new com.delta.common.exception.BusinessException(400,"实名提醒方式只能是 OFF、REMIND 或 REQUIRED");
        if(key.startsWith("wechat.official.template.")) validateOfficialTemplate(value);
        if(key.startsWith("wechat.official.templates.")) validateOfficialRoleTemplates(value);
        if(key.startsWith("sms.notification.templates.")) validateSmsRoleTemplates(value);
        if(java.util.Set.of("risk.media.endpoint","wechat.official.redirect_url","wechat.official.staff_redirect_url").contains(key)&&!value.isBlank())try{var uri=java.net.URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();}catch(Exception e){throw new com.delta.common.exception.BusinessException(400,"请填写有效HTTPS地址");}
        if("risk.freeze_threshold".equals(key))try{int n=Integer.parseInt(value);if(n<1||n>100)throw new IllegalArgumentException();}catch(Exception e){throw new com.delta.common.exception.BusinessException(400,"违规阈值须为1至100之间整数");}
        if("site.tech_support_text".equals(key)) {
            value = value.trim();
            if(value.isEmpty() || value.length()>80) throw new com.delta.common.exception.BusinessException(400,"技术支持文字须为1至80个字符");
        }
        if("site.tech_support_url".equals(key)) {
            value = value.trim();
            if(!value.isEmpty()) {
                try {
                    java.net.URI uri = java.net.URI.create(value);
                    if(uri.getHost()==null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                        throw new IllegalArgumentException();
                } catch(Exception e) {
                    throw new com.delta.common.exception.BusinessException(400,"技术支持链接须为有效的 http 或 https 地址");
                }
            }
        }
        if("site.staff_web_url".equals(key) && !value.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(value.trim());
                if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                        || uri.getQuery()!=null || uri.getFragment()!=null
                        || (uri.getPath()!=null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
                    throw new IllegalArgumentException();
                value = uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getRawAuthority();
            } catch(Exception e) {
                throw new com.delta.common.exception.BusinessException(400,"工作人员后台地址须为不含路径的 HTTPS Origin");
            }
        }
        if("site.customer_web_url".equals(key) && !value.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(value.trim());
                if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                        || uri.getQuery()!=null || uri.getFragment()!=null
                        || (uri.getPath()!=null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
                    throw new IllegalArgumentException();
                value = uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getRawAuthority();
            } catch(Exception e) {
                throw new com.delta.common.exception.BusinessException(400,"客户 H5 站点地址须为不含路径的 HTTPS Origin");
            }
        }
        if("customer_service.mode".equals(key)) {
            if(!java.util.Set.of("internal","external").contains(value)) throw new com.delta.common.exception.BusinessException(400,"客服接入方式不正确");
        }
        if("customer_service.home_icon_visible".equals(key)) {
            if(!java.util.Set.of("true","false").contains(value)) throw new com.delta.common.exception.BusinessException(400,"首页客服图标显示设置不正确");
        }
        if(java.util.Set.of("order.purchase_enabled","order.guest_checkout_enabled","player.directory.enabled").contains(key)
                && !java.util.Set.of("true","false").contains(value)) {
            throw new com.delta.common.exception.BusinessException(400,"功能开关设置不正确");
        }
        if("customer_service.external_url".equals(key) && !value.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
                    throw new IllegalArgumentException();
            } catch(Exception e) {
                throw new com.delta.common.exception.BusinessException(400,"企业微信客服链接须为有效的 HTTPS 地址");
            }
        }
        if("player.deposit_mode".equals(key)) {
            if(!java.util.Set.of("REQUIRED","NONE","BEFORE_WITHDRAW").contains(value)) throw new com.delta.common.exception.BusinessException(400,"打手押金政策不正确");
        }
        if("risk.media.review_mode".equals(key)) {
            if(!java.util.Set.of("AUTO","MANUAL").contains(value)) throw new com.delta.common.exception.BusinessException(400,"图片语音审核方式不正确");
        }
        if(java.util.Set.of("withdraw.min_amount","player.deposit_amount","sms.aliyun.player_fee").contains(key)) {
            try{var n=new java.math.BigDecimal(value);if(n.signum()<0 || n.stripTrailingZeros().scale()>2 || n.compareTo(new java.math.BigDecimal("1000000"))>0)throw new IllegalArgumentException();}
            catch(Exception e){throw new com.delta.common.exception.BusinessException(400,"金额配置无效");}
        }
        if(java.util.Set.of("withdraw.max_daily_count","order.auto_confirm_hours",
                "order.pay_deadline_minutes","order.max_active_per_player").contains(key)) {
            try {
                int n = Integer.parseInt(value);
                if (n < 1 || n > 1000) throw new IllegalArgumentException();
            } catch (Exception e) {
                throw new com.delta.common.exception.BusinessException(400,"次数与时长须为1至1000之间的整数");
            }
        }
        SysConfig update=new SysConfig();update.setId(old.getId());update.setConfigValue(value);sysConfigService.updateById(update);
    }

    private void validateOfficialTemplate(String value) {
        try {
            var obj = com.alibaba.fastjson2.JSON.parseObject(value);
            validateOfficialTemplateNode(obj);
        } catch (Exception e) {
            throw new com.delta.common.exception.BusinessException(400,"模板映射须包含templateId和有效fields字段映射");
        }
    }

    private void validateOfficialTemplateNode(com.alibaba.fastjson2.JSONObject obj) {
        if (obj == null) throw new IllegalArgumentException();
        if (obj.isEmpty()) return;
        if (obj.getString("templateId") == null || obj.getString("templateId").isBlank()) throw new IllegalArgumentException();
        var fields = obj.getJSONObject("fields");
        if (fields == null || fields.isEmpty() || fields.size() > 20) throw new IllegalArgumentException();
        for (String field : fields.keySet()) {
            if (!field.matches("[A-Za-z_]+[0-9]*")
                    || !NotificationTemplateSources.KEYS.contains(fields.getString(field))) {
                throw new IllegalArgumentException();
            }
        }
        String url = obj.getString("url");
        if (url != null && !url.isBlank()) {
            if (url.length() > 2000) throw new IllegalArgumentException();
            var uri = java.net.URI.create(url.trim());
            String scheme = java.util.Objects.toString(uri.getScheme(),"").toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("http","https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        }
        var mini = obj.getJSONObject("miniprogram");
        if (mini != null) {
            String appid = java.util.Objects.toString(mini.getString("appid"),"").trim();
            String pagepath = java.util.Objects.toString(mini.getString("pagepath"),"").trim();
            if (!appid.isBlank() || !pagepath.isBlank()) {
                if (!appid.matches("[A-Za-z0-9_-]{2,64}") || pagepath.isBlank() || pagepath.length() > 200) throw new IllegalArgumentException();
            }
        }
    }

    private void validateOfficialRoleTemplates(String value) {
        try {
            var templates = com.alibaba.fastjson2.JSON.parseObject(value);
            if (templates == null) throw new IllegalArgumentException();
            java.util.Set<String> groups = java.util.Set.of("new_order","order_status","order_accepted","order_completed","material_reviewed","withdraw_reviewed","refund_status","complaint_status","staff_review");
            for (String group : templates.keySet()) {
                if (!groups.contains(group)) throw new IllegalArgumentException();
                Object node = templates.get(group);
                if (!(node instanceof com.alibaba.fastjson2.JSONObject template)) throw new IllegalArgumentException();
                validateOfficialTemplateNode(template);
            }
        } catch (Exception e) {
            throw new com.delta.common.exception.BusinessException(400,"角色模板组须按事件分组配置templateId和有效fields字段映射");
        }
    }

    private void validateSmsRoleTemplates(String value) {
        try {
            var templates = com.alibaba.fastjson2.JSON.parseObject(value);
            if (templates == null) throw new IllegalArgumentException();
            java.util.Set<String> groups = java.util.Set.of("new_order","order_status","refund_status","complaint_status","staff_review");
            java.util.Set<String> sources = NotificationTemplateSources.KEYS;
            for (String group : templates.keySet()) {
                if (!groups.contains(group)) throw new IllegalArgumentException();
                Object node = templates.get(group);
                if (!(node instanceof com.alibaba.fastjson2.JSONObject template)) throw new IllegalArgumentException();
                if (template.isEmpty()) continue;
                if (template.getString("templateCode") == null || template.getString("templateCode").isBlank()) throw new IllegalArgumentException();
                var fields = template.getJSONObject("fields");
                if (fields == null || fields.isEmpty()) throw new IllegalArgumentException();
                for (String field : fields.keySet()) {
                    if (!field.matches("[A-Za-z_]+[0-9]*") || !sources.contains(fields.getString(field))) throw new IllegalArgumentException();
                }
            }
        } catch (Exception e) {
            throw new com.delta.common.exception.BusinessException(400,"短信角色模板组须按事件分组配置templateCode和有效fields字段映射");
        }
    }

    private void ensureConfig(String key, String name, String value, String valueType, String group, String remark) {
        boolean exists = sysConfigService.exists(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (exists) return;
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigName(name);
        config.setConfigValue(value);
        config.setValueType(valueType);
        config.setConfigGroup(group);
        config.setRemark(remark);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        sysConfigService.save(config);
    }

    /** 一次性修正旧版工作人员公众号回调路径，避免 /mobile 下线后绑定失败。 */
    private void migrateLegacyStaffRedirect() {
        SysConfig config = sysConfigService.getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "wechat.official.staff_redirect_url"));
        if (config == null || config.getConfigValue() == null
                || !config.getConfigValue().contains("/mobile/wechat-binding")) return;
        SysConfig update = new SysConfig();
        update.setId(config.getId());
        update.setConfigValue(config.getConfigValue().replace("/mobile/wechat-binding", "/h5/wechat-binding"));
        update.setUpdatedAt(LocalDateTime.now());
        sysConfigService.updateById(update);
    }

    private void syncConfigMetadata(String key, String name, String remark) {
        SysConfig config = sysConfigService.getOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (config == null || (name.equals(config.getConfigName()) && remark.equals(config.getRemark()))) return;
        SysConfig update = new SysConfig();
        update.setId(config.getId());
        update.setConfigName(name);
        update.setRemark(remark);
        sysConfigService.updateById(update);
    }
}
