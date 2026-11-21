# Delta 文档入口

已读取 VIBE_CODING.md v2026-11-21.2。

本目录只保留当前有效文档。历史发布说明、客户迁移记录和旧专项方案已归档到 `D:\CodexData\archive\cangshuo-detla-docs-before-consolidation-2026-11-21`，不得作为当前版本或生产状态依据。

归档完整性清单：`archive-manifest.sha256`；含客户域名、实例、数据库、备份和资金证据的材料另存于同目录 `restricted\`，已限制访问权限。

## 先看哪里

| 你的任务 | 先读 |
| --- | --- |
| 任意代码改动 | `docs/ai/CONTEXT.md`，再运行 `python scripts/ai_context.py brief --task "<任务>"` |
| 版本、分支、完成标准 | [VIBE_CODING.md](VIBE_CODING.md) |
| 当前生产版本和最近发布 | [CURRENT_STATE.md](CURRENT_STATE.md) |
| 系统拓扑、模块、数据边界 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 业务状态、资金、支付、权限 | [BUSINESS_RULES.md](BUSINESS_RULES.md) |
| 构建、发布、备份、回退 | [OPERATIONS.md](OPERATIONS.md) |
| 登录、支付、短信、实名、通知 | [CONFIGURATION.md](CONFIGURATION.md) |
| 发布历史 | [CHANGELOG.md](CHANGELOG.md) |

## 当前文档集

- [VIBE_CODING.md](VIBE_CODING.md)：规则、开工顺序、版本/发布/安全/数据库边界。
- [CURRENT_STATE.md](CURRENT_STATE.md)：当前版本、生产拓扑、最近计划和未验证项。
- [ARCHITECTURE.md](ARCHITECTURE.md)：仓库、19 个后端模块、前后端入口、授权中心和部署拓扑。
- [BUSINESS_RULES.md](BUSINESS_RULES.md)：账号、订单、协同、支付、资金、客服、实名、短信、营销、权限规则。
- [OPERATIONS.md](OPERATIONS.md)：本地构建、打包、Release、服务组发布、备份、回退和生产验收。
- [CONFIGURATION.md](CONFIGURATION.md)：系统配置、微信、支付、短信、实名、邮件、主题与功能商城。
- [CHANGELOG.md](CHANGELOG.md)：最近发布证据与历史里程碑摘要。
- [ai/CONTEXT.md](ai/CONTEXT.md)：AI 唯一常读操作上下文。
- [ai/routing.json](ai/routing.json)：路径、任务和风险路由。
- [ai/index.json](ai/index.json)：由 `scripts/ai_context.py build` 生成的代码索引，不要手改。

## AI 工作流

```powershell
cd D:\codeStudy\code\cangshuo-detla
git status --short --branch
python scripts/ai_context.py brief --task "当前任务描述"
```

`brief` 会输出命中的风险、相关文档、测试路径和验证命令。不要通读全部文档，也不要从历史发布记录推断当前版本。

## 文档维护

- 当前版本只写在 `CURRENT_STATE.md`；历史版本只写在 `CHANGELOG.md`。
- 路由和索引不要手抄业务事实：规则进 `VIBE_CODING.md`/`CONTEXT.md`，业务规则进 `BUSINESS_RULES.md`，机器路由进 `routing.json`。
- 结构变化后运行 `python scripts/ai_context.py build`，CI 会检查 `index.json` 是否过期。
- 只改说明文字不提升客户端版本号。正式发布必须更新版本文件、CHANGELOG 和 CURRENT_STATE。
- 旧材料归档到 D 盘后，不在 Git 工作树保留副本；需要追溯时从归档目录读取。

## 验收边界

文档检查、索引检查、单测和容器 healthy 都不是客户业务验收。真实微信支付、退款、短信、实名、真机和三租户逐页人工验证必须单独记录。
