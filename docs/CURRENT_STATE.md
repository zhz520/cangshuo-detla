# 当前状态（2026-11-21）

本文件只记录“现在是什么”，不承载历史过程。当前版本、生产状态和最近发布证据以本文件为准；历史变化见 [CHANGELOG.md](CHANGELOG.md)。

## 1. 源码与版本

| 项目 | 当前值 |
| --- | --- |
| 源码工作区 | `D:\codeStudy\code\cangshuo-detla`，`codex/engineering-hardening`（候选，未发布） |
| 候选版本 | `1.18.5` |
| 生产正式版本 | `1.18.4` |
| 生产标签与提交 | `v1.18.4` → `d0c03e4e393439df3aa7f7c120a164788dd0d090` |
| 生产 GitHub Release | `453576664` |
| 授权中心 | `D:\codeStudy\code\cangshuo-detla-authorize`，独立版本线 |
| 520 演示线 | 后端/后台为 1.16.5 基线加架构与 UI 补丁；H5 为 1.16.5；不是共享组成员 |

`main` 上发布标签之后的文档提交不表示生产被重新部署。不要从 `742`、`747`、`detla738` 等客户副本反向推断最新源码。

## 2. 生产共享组

| 项目 | 当前值 |
| --- | --- |
| 服务组 | `delta-shared-prod` |
| 租户 | `742`、`747`、`jax` |
| 生产版本 | `1.18.4` |
| 最近计划 | `94a97608-ddcc-4ac7-8095-efa78de169a4` |
| 预检与审计 | `VALID` → `SUCCEEDED` |
| 发布目录 | `/www/delta/shared/releases/94a97608-ddcc-4ac7-8095-efa78de169a4` |
| 备份目录 | `/var/lib/delta-shared/updater/backups/94a97608-ddcc-4ac7-8095-efa78de169a4` |

### 租户与数据库

| 租户 | 后台域名 | H5 域名 | 数据库 |
| --- | --- | --- | --- |
| 742 | `chuanapi742.idcgov.cn` | `chuan742.idcgov.cn` | `delta_742_game` |
| 747 | `youciapi747.idcgov.cn` | `youci747.idcgov.cn` | `delta_747_game` |
| jax / delta | `jaxapi738.idcgov.cn` | `jax738.idcgov.cn` | `delta_game` |

三套业务数据、上传文件、Redis 命名空间、管理员账号和授权实例相互隔离。共享的是 Java、MySQL、Redis 和静态制品运行时，不是业务数据。

### 1.18.4 生产验收

- 三组件均为 `1.18.4`；三个实例在 2026-11-21 17:49（Asia/Shanghai）完成新版上报。
- 五个共享容器 `delta-shared-backend`、`delta-shared-mysql`、`delta-shared-redis`、`delta-admin-ui`、`delta-h5` 均 running / healthy。
- 六个生产域名返回 `200`；后台和 H5 版本文件均为 `1.18.4`；入口 JS 返回 `application/javascript`。
- 三库各 76 张表；财务对账 12 项在三库均为 0 违规。
- `cangshuo-shared-worker.service` active，`allow_execute=true`，无 journal、维护门禁和待处理计划。

正式资产：

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `cangshuo-backend.zip` | 97,828,447 | `990987e55355029c2762b835653f5560f491a7ac7fd72a2ef7a38b3d4e56556f` |
| `cangshuo-admin.zip` | 997,243 | `3d89834f6235dc3ced83fc6e88c85a47161bd08921951db9fc4b1582b4ca518c` |
| `cangshuo-h5.zip` | 864,236 | `b18b56798eb0980f984e2b299e32e115654282b876756214fd826dcdc38f751b` |
| `release-manifest.json` | 762 | `83b7e40aa28ec48524d51d35f122b262de1cc64ef0cadc9fe3f4fd3a4c3dd163` |

## 3. 尚未完成的生产验证

- 真实微信支付、退款回调和重放未做；需要真实商户环境和付款人。
- 三租户后台 PC/H5、客户 H5、打手 H5 的完整逐页业务回归尚未重新执行。
- iOS/Android 微信内置浏览器和真机支付仍需验证。
- 发布后 24–48 小时观察仍在持续；不能把容器 healthy 当作业务验收。

## 4. 环境事实

- 后端要求 Java 17；本机默认 Java 25 不可直接用于正式构建。
- 生产共享后端保留 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`。
- H5 的微信支付回调页保留专用 CSP location，不能为了修复调起而放宽全站 CSP。
- 本地授权中心 `.local/authority.sqlite` 只是开发数据，不能据此判断生产客户、实例或授权。
- 生产服务器 SSH 固定指纹已在 D 盘受控运维资料中核对；不得使用跳过主机校验的方式。

