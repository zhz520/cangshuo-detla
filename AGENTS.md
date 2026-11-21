# Delta 项目协作入口

本仓库 `D:\codeStudy\code\cangshuo-detla` 是 Delta 电竞系统的最新源码和产品主线，不是客户部署实例。

每次新会话第一次实质性回复必须包含一行：已读取 VIBE_CODING.md v2026-11-21.2。

## 启动方式

1. 读 `docs/ai/CONTEXT.md` 和 `docs/VIBE_CODING.md`。这是 AI 的唯一常读上下文，不要通读全部 `docs/`。
2. 运行：

```powershell
git status --short --branch
python scripts/ai_context.py brief --task "<当前任务>"
```

3. 按 `brief` 命中的风险、文档、测试和命令读取目标代码；不要从历史发布记录猜当前版本。
4. 当前版本、最近计划和生产事实看 `docs/CURRENT_STATE.md`；业务规则看 `docs/BUSINESS_RULES.md`；发布/回退看 `docs/OPERATIONS.md`。
5. 完工前运行受影响验证和 `python scripts/ai_context.py validate --strict`。

如果 `brief` 报未映射路径，先更新 `docs/ai/routing.json`；不要绕过路由凭记忆改代码。

## 其他工作区

涉及 `cangshuo-detla-authorize`、`742`、`747`、`detla738` 时，先核对仓库、分支、HEAD 和未提交改动。客户副本不是最新源码，不得反向覆盖生产。共享组发布只走授权中心 `delta-shared-prod` 签名计划和 `cangshuo-shared-worker.service`。

## 硬边界

- 功能分支使用 `codex/<功能简述>`；不要在 `main` 写未验证功能。
- 正式版本 `MAJOR.MINOR.PATCH`，三端版本对齐；标签不可移动、复用或重打。
- 共享组不得使用实例更新页、`/api/client/update-plan` 或旧 `worker.py`；后台“此实例尚未接入更新执行器”是共享实例预期提示。
- 不跨客户复制 MySQL、Redis、uploads、`.env`、支付证书、短信/公众号密钥、管理员账号和业务数据。
- 客户业务数据、授权私钥、支付密钥、JWT、数据库密码、GitHub Token 不得进入代码、日志、文档、截图或对话。
- 权限、资源归属和资金状态必须后端校验；前端隐藏不是安全边界。
- 支付回调必须验签和幂等；金额、退款、提现、结算必须使用数据库事务和行锁。
- 数据库变更同时维护启动兼容和独立迁移；更新执行器不执行任意迁移。
- 不伪造测试、部署或生产验收结果。只报告实际执行过的命令和结果，未验证项明确写出。

## 完成标准

行为已实现，权限和异常路径已验证，测试/构建结果可复现，数据库和版本说明同步，`CURRENT_STATE.md`/`CHANGELOG.md` 与授权中心记录一致，并明确说明未验证部分。

## 文档维护

当前文档集固定在 `docs/README.md`。不要新增逐版本发布文档；历史全文归档到 D 盘，当前发布写 `docs/CHANGELOG.md`，当前状态写 `docs/CURRENT_STATE.md`，新坑写 `docs/ai/CONTEXT.md` 或对应测试。

