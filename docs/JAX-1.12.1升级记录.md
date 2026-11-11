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

## 回滚

把后台/H5 Compose 覆盖切回 `compose.jax-upgrade-1.12.0.json`，重建 `delta-shared-backend` 并挂回 `jax-1.12.0-20261111-112407/backend/delta-admin.jar`。数据库没有结构变更，不需要恢复数据库；若发生业务数据异常，按切换前备份恢复并逐笔核对订单、退款、提现和钱包。
