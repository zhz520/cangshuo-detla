# Delta 电竞业务系统

已读取 VIBE_CODING.md v2026-11-21.2。

Delta 是覆盖用户、打手、客服和平台管理员的电竞陪玩/代练/护航业务系统，包含 Spring Boot 后端、Vue 3 管理后台和 UniApp 客户/打手 H5/小程序。

当前版本和生产状态只以 [docs/CURRENT_STATE.md](docs/CURRENT_STATE.md) 为准；历史情况见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。

## 技术栈

- 后端：Java 17、Spring Boot、MyBatis-Plus/JdbcTemplate、MySQL 8、Redis 7。
- 管理后台：Vue 3、Vite、Element Plus、Pinia。
- 移动端：UniApp、Vue 3、H5、微信小程序。
- 部署：Docker Compose、Nginx、共享服务组签名发布。

## 业务范围

- 商品、分类、动态下单字段和富文本详情。
- 用户/打手/客服/管理员多角色登录、权限和资源归属。
- 下单、支付、派单、抢单、协同打单、换人、履约、评价、投诉和售后。
- 微信支付/退款、钱包、押金、结算、提现审核和财务对账。
- 短信、实名、邮件、公众号通知和站内消息。
- 会员、优惠券、邀请、打赏、抢单规则、主题和功能商城。
- 共享服务组、授权中心、版本发布、备份和回退。

## 代码结构

```text
delta-game/          Spring Boot 多模块后端，入口 delta-admin
delta-admin-ui/      Vue 3 管理后台（PC / 与工作人员 /h5）
delta-mp/            客户 H5、打手 H5、小程序
deploy/              Compose、Nginx、更新器、迁移和 QA 工具
contracts/           endpoint / OpenAPI / TypeScript 契约基线
docs/                当前规则、架构、业务、运维和发布文档
scripts/             AI 上下文、模块边界、版本和契约检查
```

后端 19 个模块的职责、依赖方向和风险见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [docs/README.md](docs/README.md) | 文档索引和当前文档集 |
| [docs/ai/CONTEXT.md](docs/ai/CONTEXT.md) | AI 唯一常读上下文 |
| [docs/VIBE_CODING.md](docs/VIBE_CODING.md) | 研发、版本、发布、安全规则 |
| [docs/CURRENT_STATE.md](docs/CURRENT_STATE.md) | 当前版本和生产状态 |
| [docs/BUSINESS_RULES.md](docs/BUSINESS_RULES.md) | 业务不变量和状态规则 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 构建、发布、备份和回退 |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | 登录、支付、短信、实名、通知配置 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 版本摘要和最近发布证据 |

历史材料已归档到 `D:\CodexData\archive\cangshuo-detla-docs-before-consolidation-2026-11-21`，不能作为当前版本依据。

## 本地开发

### 环境

- JDK 17
- Node.js 22+
- MySQL 8、Redis 7（或使用 `deploy/baota/docker-compose.yml`）

### 后端

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-game
mvn -pl delta-admin -am clean verify
```

### 管理后台

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-admin-ui
npm ci
npm test
npm run build
```

### H5

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-mp
npm ci
npm test
npm run build:h5
```

### AI / 维护入口

```powershell
cd D:\codeStudy\code\cangshuo-detla
git status --short --branch
python scripts/ai_context.py brief --task "<当前任务>"
python scripts/ai_context.py validate --strict
python deploy/qa/check_collaboration_rules.py
```

正式发布、共享组发布、备份和回退只能按 [docs/OPERATIONS.md](docs/OPERATIONS.md) 执行，不得使用客户副本或旧更新入口。

