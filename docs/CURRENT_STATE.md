# 当前状态（2026-11-22）

本文件只记录“现在是什么”，不承载历史过程。当前版本、生产状态和最近发布证据以本文件为准；历史变化见 [CHANGELOG.md](CHANGELOG.md)。

## 1. 源码与版本

| 项目 | 当前值 |
| --- | --- |
| 源码工作区 | `D:\codeStudy\code\cangshuo-detla`，`codex/engineering-hardening`（已发布到共享组） |
| 当前正式版本 | `1.18.5` |
| 正式标签与提交 | `v1.18.5` → `47a874f7abcd044514effb9af7088685f0171e96` |
| GitHub Release | `https://github.com/zhz520/cangshuo-detla/releases/tag/v1.18.5` |
| 授权中心 | `0.7.9`，生产容器 `cangshuo-detla-authority:0.7.9-20261122`；独立版本线 |
| 520 演示线 | 后端/后台/H5 均为 `1.18.5`；不是共享组成员 |

`main` 上发布标签之后的文档提交不表示生产被重新部署。不要从 `742`、`747`、`detla738` 等客户副本反向推断最新源码。

## 2. 生产共享组

| 项目 | 当前值 |
| --- | --- |
| 服务组 | `delta-shared-prod` |
| 租户 | `742`、`747`、`jax` |
| 生产版本 | `1.18.5` |
| 最近计划 | `edad2f69-2bde-43dd-950b-a9cfc2b16f1b` |
| 预检与审计 | `VALID` → `SUCCEEDED` |
| 发布目录 | `/www/delta/shared/releases/edad2f69-2bde-43dd-950b-a9cfc2b16f1b` |
| 备份目录 | `/var/lib/delta-shared/updater/backups/edad2f69-2bde-43dd-950b-a9cfc2b16f1b` |

### 租户与数据库

| 租户 | 后台域名 | H5 域名 | 数据库 |
| --- | --- | --- | --- |
| 742 | `chuanapi742.idcgov.cn` | `chuan742.idcgov.cn` | `delta_742_game` |
| 747 | `youciapi747.idcgov.cn` | `youci747.idcgov.cn` | `delta_747_game` |
| jax / delta | `jaxapi738.idcgov.cn` | `jax738.idcgov.cn` | `delta_game` |

三套业务数据、上传文件、Redis 命名空间、管理员账号和授权实例相互隔离。共享的是 Java、MySQL、Redis 和静态制品运行时，不是业务数据。

### 1.18.5 生产验收

- 三组件均为 `1.18.5`；共享计划 `edad2f69-2bde-43dd-950b-a9cfc2b16f1b` 审计为 `SUCCEEDED`，三个实例均在 2026-11-22 12:51（Asia/Shanghai）上报 `1.18.5`。
- 五个共享容器 `delta-shared-backend`、`delta-shared-mysql`、`delta-shared-redis`、`delta-admin-ui`、`delta-h5` 均 running / healthy。
- 742、747、JAX 的六个公众号/后台/H5 入口均已验证：后台 buildId `8e11d16a...`，H5 buildId `bbdf9de3...`，后端 buildId `b7c26a2a...` 三租户一致；六个域名返回 `200`。
- 520 独立演示线已更新到 `1.18.5`，后台/H5/后端 buildId 分别为 `8e11d16a...`、`bbdf9de3...`、`b7c26a2a...`；520 与共享组前端代码同源。
- `runtime_identity.py compare` 对 520/742/747/jax 的最终公开快照返回 `sameKnownCode=true`、`complete=true`、`differences=[]`、`unknown=[]`；客户配置差异未参与比较。
- 三库各 76 张表；财务对账 12 项在三库和 520 均为 0 违规。
- `cangshuo-shared-worker.service` active，`allow_execute=true`，无 journal、维护门禁和待处理计划。
- 授权中心已运行 `0.7.9`，`/health` 返回 200；从 GitHub 同步到 `96` 个正式版本（最新 `1.18.5`），并生成上述共享计划。

正式资产：

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `cangshuo-backend.zip` | 97,857,264 | `055f71525601af9d47a1ec1621a25abe2b7239c9cef9f9c8c39a7deb463b4b5f` |
| `cangshuo-admin.zip` | 999,395 | `72b8ad89d905b3519aa4f42916d6745e8280b39ef020753f3b43f75d42b93955` |
| `cangshuo-h5.zip` | 869,615 | `726641bbf75261dda63902dc94d3f45ef91c21d68f4bb8ff168fe5bf764b8365` |
| `release-manifest.json` | 762 | `09c6fcaf0f32c1ef5edc3ef67601f417493d2f4b35efddae4f2fefb8fbcddec3` |

## 3. 尚未完成的生产验证

- 真实微信支付、退款回调和重放未做；需要真实商户环境和付款人。
- 三租户后台 PC/H5、客户 H5、打手 H5 的完整逐页业务回归尚未重新执行；已完成关键入口、版本、构建标识、API 和财务对账检查。
- iOS/Android 微信内置浏览器和真机支付仍需验证。
- 发布后 24–48 小时观察仍在持续；不能把容器 healthy 当作业务验收。
- GitHub Actions `System validation` 在 `v1.18.5` 提交上未启动成功；GitHub 返回的注解是账户付款/消费额度限制，不是工作流代码或测试失败。当前发布证据来自本地全量验证、520 验收和共享 worker 审计，不能宣称 CI 已通过。

### 磁盘清理（2026-11-22）

- 清理原因：Docker 镜像/旧构建缓存、520 旧 JAR 与旧 release、多个历史备份根占用。
- 清理后：根分区由 `91%` 使用/约 `6.0G` 可用提升到 `77%` 使用/约 `16G` 可用；释放约 `4.76GB`。
- 保留：共享组最近 3 个计划备份、520 当前/上一版及押金审计备份、当前 Compose 引用的 `pre-usage-afe8aa9` 配置、授权中心最近成功版本备份；旧备份删除清单见 `/opt/apps/520/backups/cleanup-20261122-134739.txt`。
- 已启用 `cangshuo-disk-guard.timer`，每天检查一次；只在根分区达到 85% 时清理超过 7 天且未被容器使用的旧容器/镜像/构建缓存。

## 4. 环境事实

- 后端要求 Java 17；本机默认 Java 25 不可直接用于正式构建。
- 生产共享后端保留 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`。
- H5 的微信支付回调页保留专用 CSP location，不能为了修复调起而放宽全站 CSP。
- 本地授权中心 `.local/authority.sqlite` 只是开发数据，不能据此判断生产客户、实例或授权。
- 生产服务器 SSH 固定指纹已在 D 盘受控运维资料中核对；不得使用跳过主机校验的方式。

