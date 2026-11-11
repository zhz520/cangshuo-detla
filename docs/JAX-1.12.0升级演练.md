# JAX（delta）1.6.1 → 1.12.0 升级演练

- 日期：2026-11-11（Asia/Shanghai）
- 对象：`delta` / JAX，生产域名 `jaxapi.kkimg.top` / `jax.kkimg.top`
- 生产版本：`1.6.1`，`channel=stable`，`schemaVersion=1`
- 状态：**生产已更新到 1.12.0；JAX 不使用微信支付，支付开关保持关闭**

## 1. 生产备份

- 服务器备份目录：`/www/delta/backups/pre-upgrade-20261111-110636`，目录 700、文件 600。
- 数据库 `delta_game` 备份 288,797 字节，SHA-256 `0e31e25280b77b061e5ceb79fe648f782881076fbba09432b47c4f181632b269`。
- 上传/私密/平台文件归档 78,442,586 字节，SHA-256 `83908687f71f7ee47041b7e05df8bfba0f30be05de8229877a99ebc6f041f0fa`。
- D 盘副本：`D:\CodexData\backups\jax-preupdate-20261111-110636`，ACL 仅当前用户；校验一致。
- 未在文档或日志中输出数据库密码、支付密钥、JWT 等配置值。

## 2. 隔离恢复与迁移

- 使用独立 `delta-shared-staging-mysql` 容器恢复 `jax_upgrade_rehearsal`，未连接 JAX 生产库写入。
- 恢复后 57 张表；按最新迁移文件顺序执行后 65 张表。
- 迁移过程中只有 `player.level`、`player.introduction`、`cover_image`、`album_images`、`story`、`voice_url`、`voice_duration` 七个“列已存在”的重复列错误，没有其他 SQL 错误；这些列在 1.6.1 中已经存在。
- clone 关键行数与源库一致：wallet 6、payment 0、order 1、transaction 0、user 6。
- clone 财务对账 12 项全部 0 违规。

## 3. 1.12.0 候选验收

- 使用候选 `1.12.0` 后端 JAR、后台和 H5 包；候选包 commit `e875cc7`。
- 后端以 JAX 域名注册启动：`jaxapi.kkimg.top` 站点 200，未登记 Host 404，日志无启动异常。
- 后台和 H5 根路径、`cangshuo-version.json`、`/api/system/config/site` 均 200，版本文件为 `1.12.0`。
- 历史 JAX 上传文件 `/file/...jpg` 返回 200（261,816 字节），缩略图返回 200（36,963 字节）。
- clone 中短信、公众号、微信支付开关均为 false；启动前已清空外发密钥和通知地址，未触发外部发送。
- 后端实际内存约 420 MB / 768 MB。

## 4. 生产更新前仍需完成

- [ ] 正式发布 1.12.0 或明确的 JAX 专用更新版本，生成正式 Release 和授权中心更新项。
- [ ] 与 JAX 客户确认维护窗口、回滚负责人和验收人。
- [ ] 在生产窗口重新备份数据库、上传和配置，记录 SHA-256。
- [ ] 在维护窗口停写、应用迁移、核对行数/金额/退款/提现，再切后台/H5。
- [ ] 核对 JAX 域名证书、CORS、公众号支付回调和短信开关。
- [ ] 完成登录、下单、派单、聊天、支付、退款、提现和文件验收。
- [ ] 失败时恢复旧 JAR、旧静态产物和数据库备份；不得删除旧数据。

## 5. 结论

JAX 的 1.12.0 升级技术路径已经验证，备份和隔离恢复可用。

## 6. 2026-11-11 生产更新执行记录

- 进入维护窗口后停止 `delta-backend`、`delta-admin-ui`、`delta-h5`，再对停写后的 `delta_game` 和 `data/upload`、`data/private`、`data/platform` 做最终备份。
- 最终备份：`/www/delta/backups/pre-update-20261111-112407`，含数据库、文件、Compose 配置、`.env` 和 SHA-256；目录/文件权限受限。
- 生产库执行 16 个迁移文件：57 表 → 65 表；只有 7 个已存在列的重复错误，无其他 SQL 错误；wallet 6、payment 0、order 1、transaction 0、user 6 行数不变。
- 新发布目录：`/www/delta/self-update-releases/jax-1.12.0-20261111-112407`；后端 catalog `1.12.0`/schema 1，后台/H5 版本文件 `1.12.0`。
- 持久化 Compose 覆盖已更新到新发布目录；旧 Compose 覆盖在最终备份目录中。
- 验收：后台/H5 根路径、版本文件、`/api` 均 200；历史 JAX 图片 200（261,816 字节）；生产库财务 12 项检查 0 违规；后端启动后无未处理异常。

### 微信支付状态

- JAX 客户确认未配置微信支付；生产 `payment` 表此前为 0 条记录，数据库里的旧证书路径对应文件不存在。
- `wx.pay.enabled`、`wx.pay.h5-enabled`、`wx.pay.miniapp-enabled`、`wx.pay.official-enabled` 保持 false，并已清理对应 Redis 配置缓存；最近日志错误数为 0。
- 这不是升级阻塞项。若以后启用微信支付，再补商户私钥/微信支付公钥并做真实小额回调验收。

## 7. 更新后仍需完成

- [x] 确认 JAX 不启用微信支付，支付开关保持关闭。
- [ ] 业务验收：登录、下单、派单、聊天、退款、提现、文件上传和 H5 返回键。
- [ ] 授权中心登记 JAX 1.12.0 版本/更新项，确认平台版本上报。
- [ ] 观察期内检查 5xx、支付、退款和定时任务。
- [ ] 如需回滚：恢复旧 JAR/旧静态产物和旧 Compose 覆盖；数据库迁移为加法，旧程序可先运行，必要时再恢复停写备份。

## 8. JAX 共享租户暂存接入

- 已把 JAX clone `jax_upgrade_rehearsal` 作为第二个租户加入暂存共享服务组，和 742 同时注册 `jaxapi.kkimg.top` / `jax.kkimg.top` 与 `jgsapi.kk520yy.com`。
- 1.12.0 暂存后端同时初始化 `tenant-742` 和 `tenant-jax` 两个数据源；两个数据库各有 1 个应用连接。
- 双域名站点均 200，未登记 Host 404；后台/H5 用两个域名访问 `/api` 均 200。
- Redis 同时存在 `delta:742:` 和 `delta:jax:` 键；JAX/742 的租户文件路径和缩略图均 200。
- 旧无租户前缀 `/file/` 已不被服务，但静态资源缺失时当前后端返回 HTTP 200 的 JSON 错误体，这是已知状态码问题，不是文件泄漏；后续补丁应改为 404。
- 接入演练容器已停止；注册文件、JAX 上传副本、JAX clone 库保留在暂存目录，未改生产路由。

## 9. 1.12.0 正式登记与页面状态

- 已创建 GitHub 正式 Release：`v1.12.0`，目标提交 `e875cc72bac9ee605252d99e5c9da58ff331579f`，地址 `https://github.com/zhz520/cangshuo-detla/releases/tag/v1.12.0`。
- 三个资产已上传并校验：backend `90d21a2e...82d4`、admin `bee6bfd3...e5ca`、h5 `aefe70e8...273a`。
- 授权中心已手动同步 `detla` 正式版本目录；JAX `authority.json` 显示客户端版本 `1.12.0`、最新正式版 `1.12.0`，检查时间更新到 2026-11-11 12:55（Asia/Shanghai）。
- JAX 后台“系统更新”刷新后应显示：当前版本 1.12.0、后端/后台/H5 1.12.0、最新正式版 1.12.0、更新状态“暂无更高版本”。
- 该正式版当前没有登记可选更新项，因此其他客户不会出现可安装的 1.12.0 更新按钮；JAX 已经手工安装到 1.12.0。
- 至此 JAX 独立生产更新和版本登记完成。共享生产切换仍未执行，保持原独立部署；后续需单独的生产共享服务组、维护窗口和客户验收。

## 10. 2026-11-11 JAX 单租户共享后端试点切换

- 在现有 JAX 数据库、Redis、上传和授权状态不变的前提下，创建 `delta-shared-backend`，加入 `delta_delta-game` 网络并设置别名 `backend`，Nginx 仍通过 `backend:8080` 转发。
- 共享后端使用 `TENANT_MODE_ENABLED=true`、生产 JAX 租户注册文件、JAX 目标库和 `upload/jax` 兼容路径；平台状态复制到 `platform/tenants/jax/`。
- 停止旧 `delta-backend` 后启动共享后端；站点、后台、H5 API、历史文件和后台/H5 页面均 200，财务 12 项 0 违规，日志无异常。
- Redis 使用 `delta:jax:` 命名空间，数据库保持 `delta_game`，不复制、不导入生产数据；支付继续关闭。
- 旧 `delta-backend` 容器保留但停止，重启策略改为 `no`；`cangshuo-updater-delta` 已停止并禁用，避免旧单实例更新器误覆盖共享后端。
- 这是单租户共享运行时试点，不是完整共享 MySQL 服务组；后续增加 JAX 之外的租户前，必须先完成共享 MySQL/Redis 服务组和监控。

### 回滚

停止 `delta-shared-backend`，重新启动 `delta-backend`，恢复旧更新器（仅在回滚到独立部署时），验证站点/API/文件；数据库未迁移，无需恢复数据。
