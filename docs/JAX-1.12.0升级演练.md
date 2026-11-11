# JAX（delta）1.6.1 → 1.12.0 升级演练

- 日期：2026-11-11（Asia/Shanghai）
- 对象：`delta` / JAX，生产域名 `jaxapi.kkimg.top` / `jax.kkimg.top`
- 生产版本：`1.6.1`，`channel=stable`，`schemaVersion=1`
- 状态：**演练通过，生产未更新**

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

JAX 的 1.12.0 升级技术路径已经验证，备份和隔离恢复可用；但尚未获得正式发布和客户维护窗口，生产仍是 1.6.1。演练容器已停止，clone 库、JAX 上传副本和候选文件保留在暂存目录，生产容器未改。
