# 超大类拆分计划

本文件只记录拆分方案，不代表已经完成。当前优先级低于剩余 3 个 Controller 和 1.18.4 发布门禁。

## 1. PaymentServiceImpl（约 49KB）

建议按资金场景拆为：

- `PaymentCreationService`：订单支付、充值、押金、余额支付创建。
- `PaymentNotificationService`：支付回调验签、幂等入账、支付查单与关闭。
- `PaymentRefundService`：退款执行、退款通知、退款结果同步和冲正。
- `PlayerDepositPaymentService`：押金应缴、已缴、最近支付单和押金退款。
- `PaymentViewService`：支付单查询、脱敏展示和后台列表。

风险点：回调重复、金额/币种校验、事务边界、订单状态推进、押金和余额联动。拆分前必须保证现有支付/退款测试全绿，并补真实 MySQL 金路径。

## 2. SysConfigController（约 48KB）

建议拆为：

- `SysConfigAdminController`：后台配置列表、单条/批量保存。
- `PublicSiteConfigController`：公开站点配置组装。
- `RechargePackageAdminController`：充值套餐管理。
- `SysConfigValidationService`：公众号/短信模板、组合校验和敏感项规则。
- `SysConfigBootstrapService`：内置配置补齐、历史配置迁移和元数据修复。

风险点：敏感配置脱敏、配置分组稳定性、公开字段过滤、旧配置迁移。

## 3. OrderRefundWorkflowService（约 36KB）

建议拆为：

- `RefundRequestService`：客服/管理员退款申请和状态机。
- `RefundReviewService`：审核、拒绝、金额校验和权限。
- `RefundProviderService`：微信退款调用和结果映射。
- `RefundAuditService`：审计记录、通知和异常恢复。

风险点：两阶段退款、重复通知、处理中状态、退款金额累计和财务对账。

## 4. DatabaseCompatibility（约 46KB）

建议按表组拆为多个兼容组件：

- 用户/钱包/实名
- 订单/支付/退款/结算
- 打手/押金/提现
- 营销/邀请/会员
- 通知/公众号/短信
- 系统配置/权限/日志

保留一个启动编排器按顺序执行各组件，迁移文件保持可重复执行。风险点是旧库字段、索引和默认值差异，必须同时保留 H2 兼容测试和真实 MySQL 验证。

## 5. 建议顺序

1. 完成剩余 3 个 Controller 服务化。
2. 完成 1.18.4 发布与真实支付/退款验收。
3. 拆 `SysConfigController`，它主要是读配置和校验，风险低于支付。
4. 拆 `PaymentServiceImpl` 和退款工作流，按“创建、回调、退款、查单”逐段迁移。
5. 最后拆 `DatabaseCompatibility`，每拆一组都跑旧库升级测试。

## 6. 防止再次膨胀

- 新增 Controller 不直接依赖 `JdbcTemplate`；ArchUnit 已冻结现有违规。
- 新增金额/状态逻辑必须进 Service，并补幂等和并发测试。
- 单个 Java 类超过约 20KB 时在评审中说明原因，优先按业务场景拆，不按工具类硬拆。
