# 当前状态（2026-11-23）

本文件只记录“现在是什么”，不承载历史过程。当前版本、生产状态和最近发布证据以本文件为准；历史变化见 [CHANGELOG.md](CHANGELOG.md)。

## 1. 源码与版本

| 项目 | 当前值 |
| --- | --- |
| 源码工作区 | `D:\codeStudy\code\cangshuo-detla`（源码主仓；线上制品看正式标签） |
| 当前开发候选 | `1.19.0`（520 已部署候选，尚未发布 GitHub Release） |
| 正式标签与提交 | `v1.18.5` → `47a874f7abcd044514effb9af7088685f0171e96` |
| GitHub Release | `https://github.com/zhz520/cangshuo-detla/releases/tag/v1.18.5` |
| 授权中心 | `0.7.11`，容器 `cangshuo-detla-authority:0.7.11-20261123`；独立版本线 |
| 520 演示线 | 后端/后台/H5 均为 `1.19.0` 候选；不是共享组成员 |

`main` 上发布标签之后的文档提交不表示生产被重新部署。不要从 `742`、`747`、`detla738` 等客户副本反向推断最新源码。

### 2026-11-23 · 520 1.19.0 候选部署

- 提交：`4d349268a3463b99b02f03f6898e70ea405f3029`，三件制品与 `release-manifest.json` 已上传 `/opt/apps/520/releases/1.19.0`，SHA-256 与 manifest 一致。
- 镜像：后端 `kk-520-backend:1.19.0-20261123`，后台 `kk-520-admin:1.19.0-20261123`，H5 `kk-520-h5:1.19.0-20261123`；mysql/redis 未更换。
- 版本文件：后台/H5 均为 `1.19.0`；后台 buildId `1aeefb705b4ef89f56d2f9534f53b409b1f6fa3ba15e4034edbca6d507b4f58d`，H5 buildId `0cb926d2abb6a519ae2aaa8a1a5ae8766e0ff199c288078bc51c427b06107508`，后端 buildId `113adc32f38c874e1c489165affe8d0aa56a9555e0738c11fc02d1391eb2d4af`。
- 健康：backend/admin/h5/mysql/redis 均 running，backend healthy；`/health` 与公开站点配置返回 200；后端日志未发现 ERROR/Exception。
- 数据库：`client520_game` 91 张表；新增 `schema_migrations`、公众号账号/模板/内容/群发/标签/素材和秒杀表已创建；启动脚本校验和已登记。
- 备份：`/opt/apps/520/backups/pre-1.19.0-20261123-1115`（MySQL、uploads/platform、Compose）。`uploads.tar.gz` 5.3MB，DB 备份 gzip 校验通过。
- 授权中心：`0.7.11` 镜像 `cangshuo-detla-authority:0.7.11-20261123`，healthy；备份 `/opt/apps/authority/backups/pre-0.7.11-20261123-1135/runtime.tar.gz`。
- 未验证：真实公众号回调/发送、真实微信支付、真实素材/草稿发布、720/750/747/JAX 共享组、三套主题逐页人工验收。520 仅为候选验收，不是正式发布。

## 2. 生产共享组

| 项目 | 当前值 |
| --- | --- |
| 服务组 | `delta-shared-prod` |
| 租户 | `742`、`747`、`jax` |
| 生产版本 | `1.18.5` |
| 最近计划 | `6dc7648b-e6ef-481b-b99f-6cb7188bbd9f`（网关配置修复，复用现有正式制品） |
| 预检与审计 | `VALID` → `SUCCEEDED` |
| 发布目录 | `/www/delta/shared/releases/6dc7648b-e6ef-481b-b99f-6cb7188bbd9f` |
| 备份目录 | `/var/lib/delta-shared/updater/backups/6dc7648b-e6ef-481b-b99f-6cb7188bbd9f` |

### 租户与数据库

| 租户 | 后台域名 | H5 域名 | 数据库 |
| --- | --- | --- | --- |
| 742 | `chuanapi742.idcgov.cn` | `chuan742.idcgov.cn` | `delta_742_game` |
| 747 | `youciapi747.idcgov.cn` | `youci747.idcgov.cn` | `delta_747_game` |
| jax / delta | `jaxapi738.idcgov.cn` | `jax738.idcgov.cn` | `delta_game` |

三套业务数据、上传文件、Redis 命名空间、管理员账号和授权实例相互隔离。共享的是 Java、MySQL、Redis 和静态制品运行时，不是业务数据。

### 1.18.5 生产验收

- 三组件仍为 `1.18.5`。2026-11-23 共享组按同版签名计划应用网关配置，逐租户备份已生成，审计为 `SUCCEEDED`；四站重新比对的三端制品与入口摘要全部一致，未部署新版 UI。
- 五个共享容器 `delta-shared-backend`、`delta-shared-mysql`、`delta-shared-redis`、`delta-admin-ui`、`delta-h5` 均 running / healthy。
- 742、747、JAX 的六个公众号/后台/H5 入口均已验证：后台 buildId `8e11d16a...`，H5 buildId `bbdf9de3...`，后端 buildId `b7c26a2a...` 三租户一致；六个域名返回 `200`。
- 520 独立演示线已更新到 `1.18.5`，后台/H5/后端 buildId 分别为 `8e11d16a...`、`bbdf9de3...`、`b7c26a2a...`；520 与共享组前端代码同源。
- `runtime_identity.py compare` 对 520/742/747/jax 的最终公开快照返回 `sameKnownCode=true`、`complete=true`、`differences=[]`、`unknown=[]`；客户配置差异未参与比较。
- 三库各 76 张表；财务对账 12 项在三库和 520 均为 0 违规。
- 520 和共享三库的 `complaint` 表当前均为 0 条带图片/申诉图片记录；没有待迁移的历史投诉附件引用，旧公开文件不做自动删除。
- `cangshuo-shared-worker.service` active，`allow_execute=true`，无 journal、维护门禁和待处理计划。
- 授权中心运行 `0.7.10`；登录与签名计划 API 可用。三租户注册表 ID 已与授权中心及本地状态对齐，独立凭据从持久私有配置注入，既有短信通道和实名开关未改变。
- 网关生产实测：三租户签名请求均可读取 4 个短信套餐；742/JAX 可读到 3 个实名套餐、供应商就绪，747 因无实名权益正常返回 403。目录读取不代表发送、核验、支付或登录后的逐页 UI 已验收。
- 生产 worker `1.13.0-r7-gateway` 对 742/747/JAX 执行网关门禁；已实际重启 worker 验证旧容器名释放，业务容器未随 worker 重启。

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
- GitHub Actions `System validation` 已在公开仓库启用并通过：`main` 提交 `39455b9` 的工作流运行 `45004138265` 全流程成功。`docs/ai/index.json` 现按 POSIX 路径稳定排序，并从模块最短包名生成 `packageRoot`，Windows 与 Linux 生成结果一致。

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

## 5. 待办与验收门槛

| 任务 | 优先级 | 状态及验收 |
| --- | --- | --- |
| DELTA-12 · 共享组功能验收 | P1 | 三端制品同源，三租户网关接入、授权及目录的 API 检查已恢复并实测；还要验证受影响的后台 PC/H5 登录后行为、内容 CRUD、功能商城、真实发送/核验/支付及资金/订单关键路径。代码调整如需上线仍先验收 520，再晋级同制品。内容和凭据只能使用本租户的。 |
| DELTA-13 · 开发期旧分支清理 | P1 | 待做。先建立干净的开发数据库、种子和测试夹具，再逐域去掉不合理的旧兼容分支。不得用于删除或重置生产租户数据。 |
| DELTA-14 · 秒杀活动首版 | 进行中 | 本地首版已实现：后台 CRUD、公开进行中活动、订单报价/独立库存扣减、每账号 1 单、禁止叠加指定打手/等级/优惠券、未支付取消后定时返还名额。入口「营销活动 → 秒杀活动」。仍需 520 验收真实下单/支付/取消返还，迟到款与售后规则未实测；未部署。 |
| DELTA-15 · 公众号能力边界与契约 | P0 | 已完成契约与路由：wxapi 只读；新增 `wechat-operations` 路由、能力对照、事件/权限/配置边界；平台级组件配置归授权中心，客户凭据归客户实例。详细方案见 D 盘任务文档。 |
| DELTA-16 · 授权中心模板方案与客户继承 | P0 | 已完成本地实现与测试：授权中心模板方案 CRUD/版本/发布、签名项目快照下发；客户实例读取方案并预填字段；只下发方案不下发模板 ID。 |
| DELTA-17 · 公众号绑定、同步与就绪 | P0 | 进行中：客户直连账号、凭据加密、模板同步/映射、场景就绪和后台账号页已完成；开放平台模式的 authorizer token 交换仍需控制面联调。 |
| DELTA-18 · 模板通知发送闭环 | P0 | 进行中：发送器优先使用本租户模板，缺失不回退旧配置；旧配置兼容读取保留。真实发送、旧配置迁移和回调未验收。 |
| DELTA-19 · 账号与基础运营 | P1 | 进行中：菜单、自动回复、渠道二维码、粉丝同步、消息落库/自动回复、标签增删同步、素材列表同步和运营统计已实现；客户本地上传素材和真实粉丝批量打标验收仍缺。 |
| DELTA-20 · 图文与群发审批 | P1 | 本地首版进行中：图文草稿、群发任务、提交/批准/驳回、服务端开关和双人发送校验已实现；真实群发默认关闭，微信草稿推送、公开图文和 520 真实粉丝验收未做。 |
| DELTA-21 · 后台入口、PC/H5、权限与主题 | P1 | 进行中：新增公众号运营入口、账号页和运营工具页，接入权限和功能权益；PC/H5/三主题逐页验收仍待做。 |
| DELTA-22 · 多租户、回调、密钥与审计 | P0 | 进行中：凭据加密、租户隔离、输入校验、回调验签、AES 解密、消息去重和按账号 120 次/分钟限流已完成；完整审计与真实回调联调仍待做。 |
| DELTA-23 · 测试矩阵、520 验收与发布 | P0 | 待做。当前仅本地单测/构建；未部署 520，未做真实公众号、真实发送或共享组晋级。 |
| DELTA-24 · 数据库迁移与调度治理 | P1 | 进行中：`schema_migrations` 记录启动脚本 SHA-256；新增迁移文件清单与重复校验和审计；13 个定时任务改用命名作业锁并按租户隔离。仍需真实 MySQL 新旧库结构对比、锁过期策略和生产调度观测。秒杀实现必须复用该约束。 |

DELTA-15 至 DELTA-23 的详细方案、边界、能力映射和实施顺序见 `D:\CodexData\outputs\delta-wxapi-integration\公众号能力整合任务.md`。

DELTA-24 详情见 `D:\CodexData\outputs\project-optimization-plan-20261121\P1数据库与调度任务.md`；DELTA-14 仍待排期，不因记录进入实现。

