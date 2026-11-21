# Delta 研发与发布规则

文档版本：2026-11-21.2

已读取 VIBE_CODING.md v2026-11-21.2。

## 1. 适用范围

本规则适用于 `D:\codeStudy\code\cangshuo-detla` 及其授权运营中心 `cangshuo-detla-authorize`。项目是 Delta 电竞/陪玩/代练产品主线；JAX、742、747 是共享服务组中的三个独立租户。

规则只回答“应该怎么做”。当前版本和生产状态看 `docs/CURRENT_STATE.md`，业务规则看 `docs/BUSINESS_RULES.md`，发布操作看 `docs/OPERATIONS.md`。

## 2. 开工顺序

1. 确认工作目录、分支、HEAD、`git status` 和 `git worktree list`。
2. 读 `docs/ai/CONTEXT.md` 和本文件；不要通读全部文档。
3. 运行 `python scripts/ai_context.py brief --task "<任务>"`，根据命中的风险读对应模块、测试和文档。
4. 如果路由没有命中，先补 `docs/ai/routing.json`，再改代码。
5. 用户最新明确指令优先；规则冲突看本文件，事实冲突看当前代码、测试或生产证据。

## 3. 事实优先级

| 问题 | 权威来源 |
| --- | --- |
| 本次目标和范围 | 用户当前指令 |
| 安全、租户、资金、发布红线 | 本文件 + `docs/ai/CONTEXT.md` |
| 当前代码行为 | 当前源码、配置和测试 |
| 当前版本/生产状态 | `python scripts/ai_context.py state` + `docs/CURRENT_STATE.md` |
| 业务状态流 | `docs/BUSINESS_RULES.md` |
| 历史变化 | `docs/CHANGELOG.md` 与 D 盘归档 |

不得用旧文档覆盖当前代码或生产观测，也不得用当前实现反推规则允许。摘要和旧报告只作为线索，不能代替现场证据。

## 4. 分支、提交和版本

- 功能分支：`codex/<功能简述>`；紧急修复：`codex/hotfix-<问题简述>`。
- `main` 始终可构建；不要在 `main` 直接写未验证功能。
- 提交信息：`feat:`、`fix:`、`security:`、`refactor:`、`test:`、`docs:`、`release:`。
- 正式版本：`MAJOR.MINOR.PATCH`。常规缺陷固定走 PATCH；新功能批次走 MINOR；协议不兼容才走 MAJOR。
- 后端 `catalog.json`、管理端 `package.json`、H5 `package.json`、锁文件和 `manifest.json` 必须对齐。
- 正式标签不可改写、移动、复用；修补必须发布新 PATCH。
- 仅改说明文字不升业务版本。

## 5. 发布规则

1. 从干净工作区、固定提交构建：后端、管理端、H5。
2. `deploy/updater/package_release.py` 生成三个 ZIP 和 `release-manifest.json`。
3. 创建不可变 GitHub 标签和 Release，上传全部资产；只接受有 SHA-256 digest 的正式资产。
4. 授权中心同步正式版本，登记必要/可选更新项。
5. 共享组生成 `cangshuo-shared-release-v1` 计划，在维护窗口内交给 `cangshuo-shared-worker.service`。
6. 执行器逐租户备份 MySQL、upload、platform 状态；验证签名、租户集合、schemaVersion、维护窗口和三个制品摘要。
7. 健康检查通过后记录计划、发布目录、备份、审计和未验证项。

禁止：

- 用预览版或脏工作区产物发布正式版。
- 用实例更新页、`/api/client/update-plan` 或旧 `worker.py` 处理共享组。
- 未备份、未验签、无维护窗口时执行切换。
- 让执行器执行任意迁移、命令、路径或容器名。
- 仅凭容器 healthy 或 CI 通过宣称业务验收完成。

## 6. 安全与数据边界

- 权限和资源归属必须在后端校验；前端隐藏只负责体验。
- 支付回调必须验签，金额/币种/交易号/本地订单一致，重复回调幂等。
- 余额、退款、提现、结算使用数据库事务和行锁，不信任客户端金额与状态。
- 上传校验真实文件签名、MIME、大小和解码；文件按租户隔离。
- 不跨客户复制 MySQL、Redis、uploads、`.env`、支付证书、公众号/短信密钥、管理员账号和业务数据。
- 私钥、Token、JWT、API v3 密钥、短信 AccessKey、数据库密码、完整证件号/手机号不得进入代码、日志、文档、截图或对话。
- 敏感操作写操作日志；客服不能超权退款、删除或修改资金。

## 7. 数据库

- 默认采用新增表、可空列或安全默认值；不删除字段、不改写历史数据。
- 数据库变更同时维护启动兼容和独立迁移文件，迁移必须可重复执行。
- H2 不能证明 MySQL 方言、锁、索引和并发安全；资金与复杂查询必须用真实 MySQL。
- 更新执行器只接受 `schemaVersion=1`，不执行任意迁移。
- 升级前必须备份数据库、文件、环境配置和当前版本指针。

## 8. 代码边界

### 后端

- 主链路：`Controller -> Service -> Mapper/JdbcTemplate -> MySQL/Redis`。
- Controller 不直接依赖 Mapper 或 `JdbcTemplate`；复杂事务放 Service。
- 业务模块不得依赖 `delta-admin`；`delta-common` 不得依赖业务模块。
- 结构拆分保持 endpoint、OpenAPI 和数据库行为不变。
- 新功能优先进入对应业务模块，不继续堆进 `delta-admin`。

### 管理后台

- PC 固定 `/`，工作人员移动端固定 `/h5`；不按窗口宽度自动跳转。
- 页面请求统一走 `src/api/*`，不散落 URL。
- 路由权限看 `meta.roles`、`meta.permission`、`meta.feature`；后端仍二次校验。
- 新增页面接入路由、菜单过滤、权限守卫和 `/h5` 别名。

### H5 / 小程序

- 页面必须写入 `pages.json`；未注册页面不会进入构建产物。
- 客户、打手、客服、管理员 Token 分开。
- H5、小程序、微信内置浏览器分别验证。

### 授权中心

- 授权中心只保存控制面元数据，不保存客户业务数据。
- 客户只持有验证公钥；签发私钥只存在授权中心受保护目录。
- 域名变更必须重新签发；删除平台状态文件会改变实例 UUID。

## 9. 测试与完成定义

功能完成必须同时满足：

- 行为已实现；正常、权限拒绝、未配置、并发/幂等和异常路径已检查。
- 测试和构建在当前改动上实际运行，记录通过/失败/跳过数量。
- 接口、数据库、版本说明和配置说明同步。
- 资金、租户、支付、权限变更已做横向同类调用检查。
- 没验证的内容明确写出，不用旧结果或 CI 绿灯代替。

常用检查：

```powershell
python scripts/ai_context.py validate --strict
python scripts/check_version_alignment.py
python scripts/check_module_boundaries.py
python scripts/check_endpoint_baseline.py
python scripts/check_openapi_baseline.py
python scripts/check_openapi_types.py
python deploy/qa/check_collaboration_rules.py
```

完整测试、打包、共享发布和回退见 `docs/OPERATIONS.md`。

## 10. 禁止复用历史结论

- 历史发布说明只代表当时状态；当前版本只从 `CURRENT_STATE.md` 和实时命令读取。
- 旧计划 ID、旧发布目录、旧测试数量不得写入“当前”段落。
- 旧客户迁移记录含敏感拓扑和证据，归档在 D 盘受控目录，不进入 AI 常读路径。
- 新的高代价失误要补到 `docs/ai/CONTEXT.md`、`routing.json` 或测试，而不是新增一堆历史文档。

## 11. 文档治理

- 当前文档集固定为：本文件、README、CURRENT_STATE、ARCHITECTURE、BUSINESS_RULES、OPERATIONS、CONFIGURATION、CHANGELOG 和 `docs/ai/CONTEXT.md`。
- 不新增逐版本 Markdown；发布信息写入 `CHANGELOG.md`，历史全文归档。
- 同一事实只允许一个手工维护位置；机器索引由脚本生成。
- 结构变更后运行 `python scripts/ai_context.py build`，并运行严格校验。
- 归档不等于删除；归档目录必须保留文件访问控制和 SHA-256 清单。
