# 版本与发布记录

## 1.22.2 · 通知模板修复（候选）

按当前公众号模板目录复核过期的模板配置回执，更新管理端账号页。修复提交：4cd4d27；本记录不代表已验证真实消息送达。

## 1.22.1 · Per-account WeChat semantic field mapping

Resolve signed keyword names and types against each authorized account's own template. Recheck affected semantic scenarios when a new signed revision arrives. Preserve manual mappings; export only keyword structure for operator-reviewed conversions.


## Delta 1.22.0 · 页面切换与加载反馈（候选）

统一管理端和 H5 页面切换、减少动态与错误重试；修复商品读取失败及押金退款页空态图片。保留既有通知、支付和租户配置行为。

## 1.21.0 · 扫码后自动配置公众号模板

- 管理员扫码授权后，后台任务按角色和通知场景核对该公众号的微信模板库，匹配签名方案、字段和关键词顺序。不能安全匹配的场景保留人工处理入口，不覆盖已配置或停用的映射。
- 未找到模板时，仅在有模板库编号和关键词的情况下申请添加；重新同步并验证后才绑定。重试不盲目重复添加。
- 业务通知可以独立选择发件公众号，不改变登录和支付默认号。配置过程不向真实用户发送消息。
- 公众号菜单支持可视化编辑和预览校验；粉丝资料按批同步。

发布前按 [构建、发布与运维](OPERATIONS.md) 独立应用两份新增迁移。公开发布记录不代表任何实例已经部署或完成微信实测。
