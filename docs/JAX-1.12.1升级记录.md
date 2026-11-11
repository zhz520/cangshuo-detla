# JAX（delta）1.12.1 补丁升级记录

- 日期：2026-11-11（Asia/Shanghai）
- 发布版本：`1.12.1`
- Git 提交：`343b81697244bcea0944fc1c9b9422c34831e079`
- Release：`https://github.com/zhz520/cangshuo-detla/releases/tag/v1.12.1`
- 数据库：`delta_game`，65 张表，`schemaVersion=1`，本补丁无迁移
- 生产形态：JAX 单租户共享服务组

## 发布资产

| 组件 | 大小 | SHA-256 |
| --- | ---: | --- |
| backend | 106,376,137 | `d2e2d4814a0b993f18f69117b94f91eba01b481b94967f9234e7e641011bd0e2` |
| admin | 662,839 | `e990d4552bc88d00fa1b27c3a087a40c537cc5f46200834458c33bb0fc48f8ec` |
| h5 | 617,390 | `4dc5d8d879bcbc5aa0649e527c104f013c1bcffacf17bb3f2337849f24c36596` |

GitHub Release 的自动 digest 与本地 `release-manifest.json` 一致。授权中心已同步 `1.12.1`，三个组件资产齐全，更新项为空。

## 切换前备份

路径：`/www/delta/backups/pre-1.12.1-20261111-135538`。

- 数据库 dump 392 KB，SHA-256 `403c15133b8ef711343240ee97651c7eee9c0d0b05d691e687a02d56e26d6a2f`。
- 保存 `component-versions.json`、租户组件版本、`tenant-registry.json`、`shared.env`、`mysql.env`、`redis.env`、Compose 覆盖和三个容器 inspect。
- 备份目录权限 700，包含敏感配置的文件权限 600；没有在报告或对话中输出密钥。

## 生产切换

- 新发布目录：`/www/delta/self-update-releases/jax-1.12.1-20261111-135538`。
- 后端 JAR 挂载到 `delta-shared-backend`，保留原镜像、环境变量、网络 `delta_delta-game`、别名 `backend`、原数据挂载、`unless-stopped` 和 768 MB 内存限制。
- 后台/H5 通过 `/www/delta/compose.self-update.json` 和 `compose.jax-upgrade-1.12.1.json` 切换到新静态目录。
- 两个组件版本文件均更新为 `backend/admin/h5/release=1.12.1`。
- 旧 `delta-backend`、`delta-mysql`、`delta-redis` 继续停止并保持 `restart=no`；旧更新器继续禁用。

## 验收结果

| 检查 | 结果 |
| --- | --- |
| 后台根路径 | 200 |
| H5 根路径 | 200 |
| 后台/H5 版本文件 | 200，版本 `1.12.1` |
| 站点配置 API | 200 |
| 缺失静态资源 | 404 |
| 允许的 WebSocket Origin | HTTP 101 |
| 跨租户 WebSocket Origin | HTTP 403 |
| 数据库表数 | 65 |
| 财务检查 | 12 项，0 违规 |
| 后端近期错误 | 0 |
| 授权中心版本 | `1.12.1` / `1.12.1` |

## 未验证和后续

- 未使用客户账号执行登录后的下单、派单、聊天、退款、提现和上传业务流程。
- 未执行微信、QQ、iOS、Android 真机矩阵和高并发压力测试。
- 客户 H5 返回键、支付入口隐藏和工作人员头像仍需在客户设备上抽查。
- 742 和 747 仍为独立部署，未修改其容器、数据库、域名、支付或更新器。

## 扩展验收（2026-11-11）

- 通过临时后台会话逐页加载 45 个 PC 路由和 45 个 H5 路由；业务页面、移动底部导航、403/404 外壳和未授权功能跳转均完成核对。临时会话和令牌已撤销。
- 生产匿名链路创建了 2 笔临时游客订单并验证游客查单、客服聊天、聊天记录、PNG 上传和缩略图；验收后订单、游客、钱包、会话、消息、进度和文件已删除，游客下单开关恢复为 `false`。
- 在 `jax_accept_20261111` 隔离副本完成余额支付、管理员指派、打手接单/开始/进度/完成、用户确认、两阶段退款、提现申请/拒绝和投诉；财务 12 项检查 0 违规。隔离容器、数据库、账号、注册文件和 Redis 命名空间已清理。
- 发现并修复系统配置中 5 条双重编码的组名及 5 条配置名称/说明；页面复核无乱码。原始编码记录保存在切换前备份目录的 `config-text-original.json`。
- 发现 `/admin/settlements` 缺少 `from`/`to` 参数时返回 500，属于低优先级 API 参数错误，建议下一补丁返回 400；页面正常调用已带日期参数并通过。

完整记录：`D:\CodexData\outputs\delta-multitenant\JAX-1.12.1-acceptance-20261111.md`。

## H5 静态资源编码修复（2026-11-11）

客户 H5 的“我的”页曾显示“连接服务器超时”，浏览器控制台报 `pages-mine-index.*.js` 等动态 chunk 的 MIME 错误。根因不是 H5 代码，而是生产手工部署使用 Linux `unzip` 解压包含中文文件名的 H5 包时，54 个文件名被错误转码；主 bundle 的 4 个动态 JS chunk 因此缺失，Nginx 的 SPA 回退又把 `index.html` 以 HTTP 200 返回给 `.js` 请求。

已用 Python `zipfile` 将同一个 `cangshuo-h5.zip` 重新解压到新目录 `/www/delta/self-update-releases/jax-1.12.1-h5fix-20261111-1455/h5`，共 280 个文件，动态引用缺失数为 0；H5 Compose 覆盖切换到新目录。Nginx 增加 `/assets/`、`/static/` 的缺失文件 404 规则，并让 `index.html`、`instance-config.js` 禁止缓存。

复核：`/assets/pages-mine-index.C5IBNkgg.js` 返回 `application/javascript` 200，错误的旧 chunk 返回 404，`/static/icons/订单.svg` 返回 `image/svg+xml` 200；全新浏览器标签打开“我的”页无控制台错误。管理后台 70 项、H5 87 项测试通过，包括新增的静态资源 404 回归测试。`v1.12.1` 标签和 Release 资产未改动；客户自更新执行器本身使用 Python `zipfile`，不受该手工解压问题影响。

后续又对 JAX、742、747 当前使用的静态目录做只读资源审计：JAX admin 133、H5 279，742 admin 134、H5 279，747 admin 114、H5 316 个文件；所有引用缺失 0，所有可检查文件通过 HTTP 状态和 MIME 检查。新增 `deploy/qa/check_static_assets.py` 并接入 CI 和打包脚本，静态引用缺失时发布会直接失败。

## 回滚

把后台/H5 Compose 覆盖切回 `compose.jax-upgrade-1.12.0.json`，重建 `delta-shared-backend` 并挂回 `jax-1.12.0-20261111-112407/backend/delta-admin.jar`。数据库没有结构变更，不需要恢复数据库；若发生业务数据异常，按切换前备份恢复并逐笔核对订单、退款、提现和钱包。
