# 构建、发布与运维

所有生产变更先确认部署形态。当前 JAX、742、747 使用共享服务组，不得使用实例更新页或旧 `worker.py`。

## 1. 本地构建

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
gh release create vX.Y.Z <assets...> --verify-tag --title "Delta X.Y.Z" --notes-file <notes.md>
```

`gh` 使用本机 Git Credential Manager 中已有凭据或受控 `GH_TOKEN`。禁止把 Token 写入脚本、仓库、日志或对话。

### 520 验证与制品晋级

1. 从同一个干净提交构建一次三端并保留三个 ZIP、manifest。520 使用这批 ZIP，同时记录实际装入的制品清单；禁止拿热修容器或重新构建的 ZIP 代替正式候选。
2. 用等价的管理员、客服、打手等测试角色在 520 验证受影响的功能，记录该测试对应的提交和三份制品摘要。共享组仍使用签名计划；生成计划前，核对正式资产的可信 digest 与 520 实装清单。
3. 用 `python deploy/qa/release_parity.py promotion <520实装清单> <正式制品清单>` 检查版本、提交、三组件大小和 SHA-256。清单应来自可信部署记录及经摘要核验的正式资产；输出仅比较清单，不证明清单来源或正在运行的镜像真实。
4. 上线后每租户逐角色检查授权、开关、界面可见及后端实际权限。`python deploy/qa/release_parity.py features <该租户预期> <同角色实测>` 输入只包含 `role` 和 `features`；每项按 `{"entitled":true,"enabled":true,"visible":true,"allowed":true}` 记录。未测填 `null`，不能按通过；授权差异按该客户套餐判断，不以 520 的套餐覆盖生产客户。禁止传入完整 API 响应、Token 或生产业务数据。
5. 代码同一性单独核对，不把个性化开关或套餐差异算作代码差异。

```powershell
python deploy/qa/runtime_identity.py probe `
  --site '742=https://chuanapi742.idcgov.cn,https://chuan742.idcgov.cn' `
  --site '747=https://youciapi747.idcgov.cn,https://youci747.idcgov.cn' `
  --site 'jax=https://jaxapi738.idcgov.cn,https://jax738.idcgov.cn' `
  --output D:\CodexData\outputs\runtime-identity.json
python deploy/qa/runtime_identity.py compare D:\CodexData\outputs\runtime-identity.json
```

后端 `/api/system/config/runtime-identity` 返回 `version` 和对自身 JAR 计算的 `buildId`（SHA-256）；工具兼容旧接口字段名。`cangshuo-admin.zip` / `cangshuo-h5.zip` 内的 `cangshuo-version.json` 还会写入 `buildId`，由打包器对除该文件外的完整前端构建树做稳定 SHA-256；它覆盖入口 JS 之外的异步 chunk 和样式。旧版本没有这些字段时显示未知，不能用“版本号相同”替代。`sameKnownCode=true, complete=false` 表示已知制品一致、仍有构建标识未证实；出现 `differences` 才是已证实代码不一致。对比结果只读取公开版本、入口文件和构建标识，不包含客户配置或业务数据。

## 4. 授权中心登记

1. 授权中心从 GitHub Release 同步正式版本，忽略草稿和预览版。
2. 确认三个组件资产都有 GitHub 提供的 SHA-256 digest。
3. 为共享组版本登记必要的 backend/admin/h5 更新项。
4. 确认公开说明页 `/public/release-notes/detla/X.Y.Z` 可访问。
5. 客户实例上报版本后，确认授权中心显示新版本。

## 5. 共享服务组发布

唯一入口：授权中心 `delta-shared-prod` 服务组计划 + `cangshuo-shared-release-v1` + `cangshuo-shared-worker.service`。

固定步骤：

1. 资产 immutable：三个 ZIP 大小、SHA-256 与 manifest 一致。
2. 只读核对服务组成员恰好是 `742`、`747`、`jax`，且项目有效、维护期未到期。
3. 生成维护窗口内、最多 15 分钟有效签名的计划。
4. 上传计划到私有状态目录，执行 `shared_worker.py validate`。
5. 确认 `allow_execute=true`、worker active、inbox/processing 为空、无 `journal.json` 和维护门禁。
6. 执行签名计划；执行器逐租户备份 MySQL、uploads 和 platform 状态。
7. 五个共享容器健康后，检查六个域名、版本文件、API 和静态资源 MIME。
8. 三库表数、财务 12 项、三实例上报版本和最近错误日志。
9. 记录计划 ID、发布目录、备份目录、审计结果和未验证项。

发版耗时分开记录构建、上传、下载解包、备份、切换与健康检查，区别总耗时和业务不可用时间。先准备并校验证物，再拍逐租户备份并切换；制品准备失败不得改变运行版本。不要为提速跳过备份、签名、三组件检查或回退。

### 1.18.5 实测流程基线（2026-11-22）

本次从已验证的 `1.18.5` 候选到生产验收的实际参考：

- 520 独立演示线：上传三件 ZIP 约 70 秒，复用服务器已有基础镜像后重建 backend/admin/h5 镜像约 10 秒；后端健康约 15 秒；三端切换和版本/buildId 检查约 2 分钟。
- 共享组：授权中心创建计划后，`shared_worker.py validate` 约 8 秒；从提交 inbox 到 `current.json` 写入 `1.18.5` 约 2 分 26 秒，其中包含逐租户 MySQL 备份、约 600MB 客户文件归档、三件资产下载解包、按 backend → admin → h5 切换和健康检查。审计结果 `SUCCEEDED`。
- 后续发版应优先复用已缓存的 Docker 基础镜像、一次构建一套三端制品、一次上传 Release，避免重复 `npm ci`、重复全量测试和逐组件手工重启。

稳态流程：

1. 先从干净提交构建一次，锁定 manifest、三件摘要和 buildId；不要在 520 或共享组重新构建源码。
2. 先验证 GitHub Release 的 digest，再让授权中心同步；共享计划必须显式通过 `shared_worker.py validate`，维护窗口至少留 30 分钟。
3. 上线前确认磁盘可用空间大于客户文件备份大小的 2 倍；执行器保留最近 3 个备份，不能等到磁盘满才清理。
4. 520 只切换候选的三端镜像，验证版本、buildId、登录页、站点配置、短信服务页、H5 首页和财务对账；通过后再提交共享组计划。
5. 共享组的 `docker ps` 会继续显示基础镜像名，因为发布通过只读 volume 挂载 release 资产；不能据此判断旧版本。必须检查 `current.json`、`cangshuo-version.json` 和 `/api/system/config/runtime-identity` 的 buildId。
6. 三个租户上报目标版本后，再打开故障处理；任何一步失败都保留计划、备份和审计证据，不用手工覆盖单个容器。

禁止：

- 使用 `/api/client/update-plan` 或旧 `worker.py` 处理共享组。
- 只替换一个租户的后端或静态文件。
- 在脏工作区、无备份、无维护窗口或未验签时部署。
- 让执行器执行任意迁移、任意命令、任意路径。

## 6. 回退

- 执行器失败时恢复原 Compose 覆盖、current 指针和上一个发布目录；回退健康检查失败会保留维护门禁。
- 回退前先确认数据库是否发生写入；程序回退不等于数据库回退。
- 需要恢复业务数据库时，先停止新写入，核对备份时间点和期间资金/订单增量。
- 恢复后重新验证容器、域名、版本文件、授权和财务。

## 7. 备份

共享组每个计划必须同时备份：

- 三租户 MySQL；
- 三租户 upload；
- platform 状态；
- 必要时 `.env`、Compose 和 current 指针。

服务端备份位于 `/var/lib/delta-shared/updater/backups/<plan-id>`。需要下载时保存到 `D:\CodexData\backups\`，并记录文件大小和 SHA-256；本地备份不能替代服务端原备份。

### 磁盘与备份保留规则

每次发版前和清理后都要用 `df -h /`、`docker system df`、`du -sh` 核对。低于 15% 可用空间先清理再发版，低于 10% 禁止继续执行生产发布。

- 共享组发布备份保留最近 3 个计划（由 `shared-worker.json` 的 `backup_retention=3` 控制）；当前保留 `edad2f69`、`94a97608`、`9c5ba71c`。
- 520 保留当前 1.18.5 备份、上一版 `arch-20261120`、最近两次押金备份，以及被当前 Compose `env_file` 引用的 `pre-usage-afe8aa9`。引用中的配置文件不得作为旧备份删除。
- 授权中心保留最近成功版本备份和上一版本备份；只保留最近 3 份，不保留同版本的多个半成品目录。
- `/www/delta/shared/backups` 和 `/www/delta/backups` 是历史备份根，各保留最近 3 个目录；新发布只写 `/var/lib/delta-shared/updater/backups`。
- Docker 只清理超过 7 天且未被容器使用的镜像、停止容器和旧构建缓存；保留当前镜像、最近回滚镜像和最近构建缓存，清理后必须重新验证当前 5 个共享容器和 520 三容器 healthy。
- `/tmp` 只清理可重建的构建包、临时 JAR 和旧 staging；不删除当前 Compose 引用的文件或正在被进程打开的文件。
- 每次清理记录删除清单、释放字节数、清理前后 `df -h /` 和健康检查结果；不删除数据库、当前 release、worker journal、审计或授权私钥。

## 8. 生产验收

至少检查：

- 五个共享容器 healthy；
- 六个域名根路径、版本文件和入口 JS MIME；
- 后台/H5 登录页、站点配置 API；
- 三库表数；
- 财务 12 项 0 违规；
- 三个实例上报目标版本；
- 发布后错误日志；
- worker、metrics timer、inbox、journal、maintenance。

业务验收必须单独执行：登录、商品、下单、支付入口、订单详情、退款、提现、协同、消息、系统配置。真实微信支付/退款、短信、实名和真机验证不能由模拟或单测替代。

## 9. 故障处理

- HTTP 超时先区分 DNS、代理、IPv4/IPv6、CDN、源站和本地工具；源站 `--resolve` 成功不等于公网正常。
- 长任务使用可轮询会话，不重复执行变更命令。
- 发现跨租户文件、资金或权限异常时，先停止相关写入口并保留日志、备份和审计证据。
- 任何生产凭据、Token、证书和完整客户数据不得复制到本地仓库、报告或对话。

## 10. 当前发布工具位置

- 打包：`deploy/updater/package_release.py`
- 签名计划校验：`deploy/updater/shared_release.py`
- 共享执行器：`deploy/updater/shared_worker.py`
- 财务对账：`deploy/migration/financial_reconciliation.py`
- 更新器说明：`deploy/updater/README.md`
- 迁移说明：`deploy/migration/README.md`

