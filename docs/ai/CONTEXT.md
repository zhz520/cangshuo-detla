# Delta AI 操作上下文

这是 AI 每次任务唯一需要常读的操作上下文。不要通读 `docs/`，也不要从历史发布记录推断当前状态。

## 1. 权威顺序

1. 用户当前指令和明确范围。
2. 安全、租户、资金、发布红线：本文件 + `docs/VIBE_CODING.md`。
3. 当前代码行为：实际源码、配置和测试。
4. 当前版本与生产记录：`python scripts/ai_context.py state` + `docs/CURRENT_STATE.md`。
5. 业务规则：`docs/BUSINESS_RULES.md`。
6. 构建、发布和恢复：`docs/OPERATIONS.md`。
7. 外部服务配置：`docs/CONFIGURATION.md`。
8. 历史：D 盘归档，只用于追溯，不用于判断“现在”。

若规则与事实冲突：规则决定允许什么，现场观测决定目前实际是什么。不得用过期文档覆盖当前代码/生产证据，也不得用“现在能跑”反推规则允许。

## 2. 项目拓扑

- `cangshuo-detla`：最新产品源码，包含后端、管理后台、客户/打手 H5、部署工具。
- `cangshuo-detla-authorize`：独立授权运营控制面，保存客户、项目、实例、维护期、权益、功能订单、Release 和服务组计划。
- JAX/742/747：同一共享服务组，但三套独立数据库、Redis 命名空间、上传文件、管理员和授权实例。
- 520：独立演示线，不是共享组成员，不能复制其数据库、配置、镜像或密钥。
- 客户副本目录不是最新源码；发布必须从本仓库正式标签和构建产物产生。

## 3. 不可违反的硬规则

### 数据与租户

- 不跨客户复制 MySQL、Redis、uploads、`.env`、支付证书、短信密钥、公众号密钥、管理员账号和业务数据。
- 请求必须显式解析租户；数据库、Redis key、文件路径和异步线程不能回退默认租户。
- 跨租户文件请求必须拒绝；同名相对路径不能串图。
- 删除 `PLATFORM_STATE_PATH` 会改变实例 UUID，必须重新登记授权。

### 权限与安全

- 后端必须校验权限和资源归属；前端隐藏按钮不是安全边界。
- 管理员/客服/用户/打手/游客身份分开，Token 不混用。
- 敏感操作写操作日志；客服不能越权退款、删除或改资金。
- 密码、JWT、API v3 密钥、私钥、短信 AccessKey、GitHub Token、完整证件号和完整手机号不得进入日志、文档、截图或对话。
- 上传必须校验真实文件签名、MIME、扩展名、大小和解码，不信任浏览器声明。

### 资金

- 金额、支付状态、退款状态、订单金额和授权状态以服务端数据库为准。
- 支付回调必须验签并校验商户号、AppID、金额、币种、交易号和本地支付单；重复回调幂等。
- 退款/余额/结算/提现使用数据库事务和行锁，不能只依赖 Redis 锁。
- 钱包和打手钱包不能为负；交易余额方向必须满足业务定义。
- `WITHDRAW`、`WITHDRAW_COMPLETE` 只影响冻结/结算，不按普通余额交易计算。
- 资金改动必须运行 `deploy/migration/financial_reconciliation.py` 的 12 项检查，真实 MySQL 验证不能由 H2 替代。

### 发布

- 正式版本 `MAJOR.MINOR.PATCH`；标签不可移动、复用和重打。
- 三端版本必须对齐；正式资产必须从干净工作区构建，带 SHA-256。
- 共享组只接受 `cangshuo-shared-release-v1` + `cangshuo-shared-worker.service`；不得使用实例更新页、`/api/client/update-plan` 或旧 `worker.py`。
- 共享计划必须有维护窗口、租户集合、三制品摘要、逐租户备份、健康检查和回退。
- 执行器不接受任意命令、路径、容器名或数据库迁移。

### 数据库

- 默认只做新增表、可空列或安全默认值；不删除字段、不改写历史数据。
- 新结构同时维护启动兼容和独立迁移文件；迁移必须可重复执行。
- H2 不能证明 MySQL 锁、方言、索引和并发安全。

## 4. 后端边界

主链路：`Controller -> Service -> Mapper/JdbcTemplate -> MySQL/Redis`。

- 19 个 Maven 模块：common、system、user、player、product、order、pay、message、cs、platform、workbench、reporting、identity、notification、marketing、workforce、governance、finance、admin。
- `delta-admin` 是唯一启动模块和后台聚合层；新业务优先进入对应模块。
- 业务模块不得依赖 `delta-admin`；`delta-common` 不得依赖业务模块。
- Controller 不直接依赖 Mapper 或 `JdbcTemplate`；复杂事务放 Service。
- 结构拆分必须保持 endpoint、OpenAPI 和数据库行为不变。

## 5. 前端入口

- 管理后台 PC 固定 `/`，工作人员 H5 固定 `/h5`；不按窗口宽度自动跳转。
- 客户 H5 / 打手 H5 在 `delta-mp`；页面必须注册到 `pages.json`，看到 Vue 文件不代表可访问。
- 页面请求统一走 `src/api/*` 或 `delta-mp/api/*`，不散落 URL。
- 管理端权限三层：`meta.roles`、`meta.permission`、`meta.feature`；后端仍二次校验。
- 主题必须覆盖默认、暗黑、拟态；不能只改一个页面。

## 6. 最容易漏的业务规则

- 微信优先但 AppID 不满足时降级手机号登录并提示；临时 Ticket 不能访问核心接口。
- 微信未绑定手机号只能换正式 Token 前绑定；命中已有手机号必须安全合并，不能制造死锁。
- 手机号验证码登录不强制设置密码；密码管理在个人中心。
- 商品联系客服购买不创建订单、不绕过暂停购买的服务端校验。
- 打手指派候选要按等级和状态过滤，按钮不可点只是体验；后端仍拒绝不合格打手。
- 协同订单 B 失效时，A 有权换 C；换人校验 A 的订单归属、B 当前状态、C 资格和人数/分成。
- 已配置公众号支付时：微信内直付，微信外生成二维码；电脑端支付成功后轮询并自动跳转。该规则不得随意改。
- 小程序支付与公众号支付独立；微信 OAuth AppID 与支付 AppID 不混用。
- 短信和实名由授权中心统一接入或客户自配；发送成功才计费，失败不能计费。
- 功能商城只展示已购买且已启用权益；授权中心可见不等于客户本地启用。
- 系统配置开关必须保存、回读并真正影响前后台；不允许要求运营手填 JSON。
- 演示账号系统配置只读；后端也要拒绝写请求。

## 7. 已知高风险坑

| ID | 风险 | 必查 |
| --- | --- | --- |
| P0-file-scope | `GET /file/**` 当前允许匿名访问公开媒体；必须确认路径/租户隔离、私有目录拒绝、MIME 和缓存安全 | `SecurityConfig`、静态代理、跨租户 404、`/file/wxpay/**`、缩略图 |
| P0-tenant-async | `@Async`、定时任务、WebSocket 丢失租户 | 传播执行器、Redis/文件/数据库租户键 |
| P0-money-arithmetic | 把提现完成当普通扣款导致误报/错账 | 排除 `WITHDRAW/WITHDRAW_COMPLETE`，真实 MySQL 对账 |
| P0-refund-replay | 退款重复回调/空币种/金额不一致 | 幂等、金额、交易号、币种、日志 |
| P0-pay-browser | 微信内直付、微信外二维码规则被破坏 | 公众号、桌面轮询、CSP、IPv4 出口 |
| P1-config-no-effect | 后台配置能保存但前端/后端不读取 | 保存后回读、开关关闭路径、未配置提示 |
| P1-pages-unregistered | 新增 H5 页面未写 `pages.json` | 构建产物、路由、H5/小程序构建 |
| P1-admin-permission | 只隐藏按钮没做后端权限 | 管理员/客服/越权负向测试 |
| P1-static-mime | JS/图片 200 但返回 HTML | 公网状态码、Content-Type、实际内容 |
| P1-doc-drift | 当前版本/计划写入多份文档后冲突 | `ai_context.py validate --strict` |
| P2-old-worktree | 在落后副本修改 | `git worktree list`、HEAD、分支、dirty |

## 8. 任务路由

`docs/ai/routing.json` 是机器规则。常用逻辑：

- 支付/退款/提现/押金/结算 → 资金 P0；幂等、并发、租户、真实 MySQL。
- 认证/权限/资源归属 → 安全 P0；正常和拒绝路径。
- 数据库/迁移/`DatabaseCompatibility` → 启动兼容 + 独立迁移 + MySQL。
- 共享组/租户/文件/Redis → 跨租户拒绝、异步传播、逐租户备份。
- 版本/Release/执行器 → 打包、摘要、标签不可变、计划审计。
- 普通后端模块 → 模块依赖、模块测试、ArchUnit。
- 管理端 views/router/api → PC/H5、权限、构建、API 边界。
- `delta-mp` → `pages.json`、H5/小程序分别构建、真机未验证要标注。
- `deploy/**` → 执行器、迁移、回滚、备份，不把代码测试当生产验收。

## 9. 开始任务的最低动作

```powershell
cd D:\codeStudy\code\cangshuo-detla
git status --short --branch
python scripts/ai_context.py brief --task "<当前任务>"
```

然后只读命中的模块、测试和文档。若 `brief` 报未映射路径或未知领域，先补 `routing.json`，不要凭空猜。

## 10. 完成与停止条件

- 行为已实现，权限/租户/资金/异常路径已验证。
- 测试和构建有当前证据；不复制旧结果。
- 数据库/版本/发布说明同步，真实未验证项明确写出。
- 发现新坑：更新本文件或 `routing.json`，并尽量补测试。
- 不能把“提交了代码”“CI 通过”“容器 healthy”混同为“业务已验证”或“客户已验收”。

