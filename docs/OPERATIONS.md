# 构建、发布与运维

新版本的微信模板和收件人功能依赖授权中心的对应接口。先验证授权中心版本，再备份目标实例，并独立执行 `20261123-wechat-notice-sender.sql` 和 `20261123-wechat-template-setup.sql`。数据库升级不由更新执行器代办。切换三端必须使用同一批已验摘要的制品。

## 1. 本地构建

数据库迁移登记：

```powershell
python -B scripts/migration_registry.py
python -B scripts/migration_registry.py --check
python -B scripts/migration_registry.py --check --strict
```

`--check` 校验 `deploy/migration/migration-registry.json` 中的文件、大小和 SHA-256；`--strict` 会把重复校验和作为失败。当前两对重复项是启动脚本与部署副本的已知镜像，审计时不能静默合并或删除。

### 后端

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-game
mvn -pl delta-admin -am clean verify
```

- 项目要求 Java 17；系统默认 Java 25 不可直接用于正式构建。
- 测试必须报告最终通过、失败、跳过数量；不能用旧测试结果代替本次。

### 管理后台

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-admin-ui
npm ci
npm test
npm run build
```

### 客户/打手 H5

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-mp
npm ci
npm test
npm run build:h5
```

小程序构建需另跑对应命令；H5 构建成功不能证明小程序可用。

### 契约与结构检查

```powershell
cd D:\codeStudy\code\cangshuo-detla
python scripts/check_version_alignment.py
python scripts/ai_context.py validate --strict
python scripts/check_module_boundaries.py
python scripts/check_endpoint_baseline.py
python scripts/check_openapi_baseline.py
python scripts/check_openapi_types.py
python deploy/qa/check_collaboration_rules.py
```

## 2. 分支与版本

- 功能分支：`codex/<功能简述>`。
- 紧急修复：`codex/hotfix-<问题简述>`。
- 正式版本号：`MAJOR.MINOR.PATCH`。
- 版本文件必须同时更新：后端 `catalog.json`、`delta-admin-ui/package.json`、`delta-mp/package.json`、锁文件和 `delta-mp/manifest.json`。
- 正式标签不可移动、复用或重打；修复必须发布新 PATCH。
- 发布说明在 `docs/CHANGELOG.md` 的当前版本区域维护，完整历史在外部归档。

## 3. 打包与 GitHub Release

从干净工作区构建并打包：

```powershell
python deploy/updater/package_release.py D:\CodexData\outputs\<version>-release
```

需要产出：

- `cangshuo-backend.zip`
- `cangshuo-admin.zip`
- `cangshuo-h5.zip`
- `release-manifest.json`

创建标签和 Release：

```powershell
git tag vX.Y.Z <commit>
git push origin vX.Y.Z
gh release create vX.Y.Z <assets...> --verify-tag --title "Delta X.Y.Z" --latest=false --notes-file <notes.md>
```

`gh` 使用本机 Git Credential Manager 中已有凭据或受控 `GH_TOKEN`。禁止把 Token 写入脚本、仓库、日志或对话。

### 制品晋级

先在获授权的测试环境验证版本和三端 buildId，再用同一批制品晋级，禁止在生产现场重新编译。使用 `deploy/qa/release_parity.py promotion` 比较提交、ZIP 大小和摘要；公开 Release 不等于部署授权。实例权限、套餐和功能验收应逐租户进行，不得跨租户复制配置和数据。

## 4. 发布与回退检查

- 备份数据库、上传文件和平台状态，核实磁盘空间、目标成员、维护窗口、签名和制品摘要。保留上一版不可变制品和经过演练的回退路径。
- 共享服务组只接受授权中心下发的签名计划；不得改用单实例更新或允许任意迁移命令。
- 逐端检查版本、buildId、健康检查、数据库结构和静态资源引用。健康检查不能代替登录、权限和业务验证。
- 微信授权验收须用真实浏览器验证打开、刷新、返回、重试和超时。真实模板通知仅向已同意的受控接收人发送，不能用群发替代。
- 回退代码不删除新增表；不重投结果未知的消息，不自动删除已加入公众号模板库的模板。涉及数据恢复时先停止新写入并评估增量。
- 私有部署清单、备份位置、维保记录、实例配置与密钥应保存在授权环境，不纳入公开发布资产。
