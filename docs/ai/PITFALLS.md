# 已知坑与必须回归的规则

## 资金

- 提现申请 WITHDRAW：减少可用余额、增加冻结金额。
- 提现完成 WITHDRAW_COMPLETE：只减少冻结金额，可用余额不变。
- 提现拒绝 WITHDRAW_REJECT：减少冻结金额、增加可用余额。
- 余额算术检查必须排除 WITHDRAW 和 WITHDRAW_COMPLETE，否则会把正常提现误报为资金违规。
- 金额使用 BigDecimal/字符串，不用 JS 浮点直接计算。
- 退款、提现、押金必须覆盖幂等、重复回调、并发审核和失败回退。

## 微信支付

- P0 固定规则：配置公众号支付时，手机微信内直接 JSAPI；电脑微信、普通手机浏览器、普通电脑浏览器生成二维码。
- 微信商户平台“基本账户余额不足”会返回 `NOT_ENOUGH`，退款恢复任务会持续重试；这不是数据库财务违规，但必须提醒运营充值并观察待恢复退款。
- 微信退款通知的 `amount.currency` 可能为空；不能因此拒绝已匹配退款单号、金额和交易号的通知，否则微信会持续重试。空币种按人民币处理，非空币种仍必须为 CNY。
- 这条规则同时适用于订单、钱包充值、打手押金、短信套餐、实名套餐和授权中心功能购买。
- 不要根据页面自行改写终端分流；改支付必须回归微信内/微信外、手机/电脑、取消/成功/重复回调。
- 退款通知重复或延迟回调时，必须幂等；不能重复退款，也不能返回成功后丢弃报文。

## 演示账号

- 演示账号只读必须由后端强制，不能只隐藏按钮。
- DEMO 角色可以访问经过白名单审核的只读数据；写、上传、导出、WebSocket 和会变更状态的 GET 必须拒绝。
- 敏感字段必须脱敏；不能把 ADMIN 角色直接给演示账号仅靠前端遮挡。
- 演示账号登录后不应出现“孤儿账号”或无法再次登录的状态。

## 多租户

- 共享组 JAX/742/747 是独立数据库、独立文件目录、独立 Redis 命名空间，不是一个库里的三个租户。
- 同一个 ID 在不同租户可能对应不同业务对象；任何按 ID 查询都必须带租户上下文。
- 文件请求、缩略图、旧 URL、WebSocket、定时任务和异步线程都要验证租户隔离。
- 不能跨客户复制数据库、Redis、uploads、.env、支付证书、短信密钥或管理员账号。

## 前端

- 管理端页面不能直接 request.get/post；请求放 src/api。
- 商品后台“描述”是富文本，前台详情必须渲染 `description` 的安全 HTML，不能只把标签剥离成纯文本摘要；图片需保留 `/file/` URL 和最大宽度限制。
- 页面使用 --el-* 主题 token，不写死 #fff/#333 等颜色。
- 使用 PageHeader/FormSection/AsyncState/DataTable 和统一 toast/confirm，不重复造样式。
- 表格列多时优先压缩/下拉操作，不要让固定列遮挡关键信息。
- 默认、暗黑、拟态三种主题都要检查；不能只验默认主题。
- H5 与后台 H5 是不同入口，不能按窗口宽度自动跳转。

## 契约 / 构建

- H2 的宽松表结构会掩盖真实 MySQL 问题：测试曾漏建 `refund_request`，且 `order_player.role` 未提供值时 H2 未报错；上真实 MySQL 后才暴露。资金、提现、退款和 Workbench 改动必须至少跑一次隔离 MySQL 金路径。
- Knife4j 4.5.0 与 Spring Framework 6.2 不兼容；保持 springdoc 2.8.x。
- 端点路径变化必须有意更新 contracts/endpoint-baseline.json、OpenAPI baseline 和 types。
- 新增页面必须通过 API 边界测试；历史直接请求文件只减不增。
- 发布 ZIP 会自动注入 cangshuo-version.json；归档检查要允许这个注入文件。
- 发布前必须从干净工作区构建，不能拿旧 dist/target 冒充新版本。

## 模块拆分

- 抽 Maven 模块时，源码包名必须和目录一致；不要把新模块继续放在 `com.delta.admin.*` 下，否则 ArchUnit 和检索都无法区分边界。
- 包级私有方法和内部类只能在同包测试中访问。移动模块时要同步移动这类单元测试，或把它们放到新模块的匹配包；否则会出现“主代码能编译、测试找不到符号”的错误。
- 拆出的 `delta-platform`、`delta-workbench`、`delta-reporting`、`delta-identity`、`delta-notification` 不得依赖 `delta-admin`；该规则已由 ArchUnit 强制执行。
- `platform/catalog.json` 仍保留在 `delta-admin` 资源中，因为发布脚本和更新器按 `BOOT-INF/classes/platform/catalog.json` 校验版本。不要只移动文件而不同步打包链路。
- 纯结构拆分必须保持 endpoint 数量、OpenAPI operation 和 schema 不变；用 `check_endpoint_baseline.py`、`check_openapi_baseline.py` 和 clean verify 证明，而不是只看编译成功。
- Controller 不应直接新增 `JdbcTemplate` 查询；ArchUnit 已冻结现有违规，新增违规会失败。查询逻辑优先放模块 Service，并补对应测试。

## 版本 / 发布

- 版本号更高不代表功能集合自动包含旧分支：1.18.0 从 1.16.6 架构线构建，曾遗漏 520 已有的单独押金功能。合并前要按功能提交和迁移比对分支，不只看版本号。
- 远端标签可能已经存在；发布前检查远端，不要覆盖 v1.17.0 这类已有标签。
- 共享组更新只走签名计划、shared_worker 和 cangshuo-shared-worker.service；不要用实例更新页或旧 worker.py。
- 共享组发布必须逐租户备份 MySQL、uploads 和 platform 状态，并保留回退目录。
- 客户后台“尚未接入更新执行器”是共享实例的正常提示，不是故障。
