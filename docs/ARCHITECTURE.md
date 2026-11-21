# 系统架构

## 1. 仓库与运行单元

| 仓库/目录 | 角色 |
| --- | --- |
| `cangshuo-detla` | Delta 电竞系统产品主线：后端、管理后台、客户/打手 H5、部署工具 |
| `cangshuo-detla-authorize` | 授权运营控制面：客户、项目、实例、维护期、功能权益、订单、Release 和服务组计划 |
| `742`、`747`、`detla738` | 客户副本或旧回退目录，不是最新源码 |

授权中心不承载客户订单、用户、钱包、聊天和上传文件，也不替客户执行业务请求。它只签发授权、功能权益、维护凭据、版本目录和共享发布计划。

## 2. 生产拓扑

```text
浏览器 / 微信 / 小程序
  -> 客户域名 Nginx
     -> delta-shared-backend (Spring Boot :8080)
     -> delta-admin-ui     (后台静态制品)
     -> delta-h5           (客户/打手 H5 静态制品)
  -> delta-shared-mysql  (三租户独立数据库)
  -> delta-shared-redis  (租户命名空间隔离)

delta-shared-backend <-> auth.kk520yy.com
  上报版本、读取授权和功能目录、购买功能

GitHub Release -> 授权中心 -> cangshuo-shared-worker.service
  -> 校验签名 -> 逐租户备份 -> 切换静态/后端 -> 健康检查 -> 审计或回退
```

共享组当前包含 742、747、jax 三个租户。客户后台里的“此实例尚未接入更新执行器”是共享实例预期提示，不能据此启动旧 `worker.py`。

## 3. 后端

### 3.1 技术栈与入口

- Java 17、Spring Boot 3.5.x、MyBatis/JdbcTemplate、MySQL 8、Redis 7。
- 唯一可启动模块：`delta-game/delta-admin`，包根 `com.delta.admin`。
- 根构建：`delta-game/pom.xml`。
- API 契约：`contracts/endpoint-baseline.json`、`contracts/openapi.baseline.json`、`contracts/api-types.d.ts`。

### 3.2 Maven 模块

| 模块 | 包根 | 职责 |
| --- | --- | --- |
| `delta-common` | `com.delta.common` | 安全、JWT、Redis、文件、聊天、短信客户端、维护模式、公共响应 |
| `delta-system` | `com.delta.system` | 管理员、角色、系统配置、公告、内容、操作日志、日统计 |
| `delta-user` | `com.delta.user` | 用户登录、资料、钱包、游客下单、收藏关注 |
| `delta-player` | `com.delta.player` | 打手入驻、主页、接单大厅、履约、收益、提现、押金 |
| `delta-product` | `com.delta.product` | 商品、分类、规格、动态下单字段 |
| `delta-order` | `com.delta.order` | 订单生命周期、进度、评价、投诉、打手履约接口 |
| `delta-pay` | `com.delta.pay` | 微信支付、余额支付、充值、押金、退款和支付补偿 |
| `delta-message` | `com.delta.message` | 站内消息 |
| `delta-cs` | `com.delta.cs` | 客服/管理员登录、客服订单、聊天、投诉、退款、提现、换人 |
| `delta-platform` | `com.delta.platform` | 平台授权、版本目录、主题、维护过滤、收益分析 |
| `delta-workbench` | `com.delta.workbench` | 后台和客服移动工作台聚合 |
| `delta-reporting` | `com.delta.reporting` | 访问分析、仪表盘统计 |
| `delta-identity` | `com.delta.identity` | 短信网关、实名认证、实名计费 |
| `delta-notification` | `com.delta.notification` | 站内通知、公众号、邮件、短信通知和队列 |
| `delta-marketing` | `com.delta.marketing` | 优惠券、会员、邀请、打赏和抢单规则 |
| `delta-workforce` | `com.delta.workforce` | 员工角色、排班、客服分配、订单协同指派 |
| `delta-governance` | `com.delta.governance` | 公开展示、隐私设置、打手主页审核和布局 |
| `delta-finance` | `com.delta.finance` | 退款、提现审核、结算台账 |
| `delta-admin` | `com.delta.admin` | 唯一启动入口、后台订单/用户/打手聚合、待办 |

模块规则：

- 业务模块只能依赖 `delta-common` 和各自 pom 声明的下层模块，不得依赖 `delta-admin`。
- `delta-admin` 是聚合和启动层，可以依赖业务模块；新业务优先落到对应模块。
- Controller 不直接依赖 Mapper 或 `JdbcTemplate`。
- 跨模块调用通过 Service 或明确的只读查询接口，不制造循环依赖。
- 结构拆分必须保持 endpoint、OpenAPI operation、schema 和数据库行为不变。

### 3.3 请求与运行时链路

```text
Controller -> Service -> Mapper/JdbcTemplate -> MySQL
                         -> Redis
                         -> 外部服务（微信、短信、实名、对象/文件）
```

- 租户由域名/Host 解析进入 `TenantContext`，数据库、Redis key 和文件路径都必须带租户边界。
- 异步任务和定时任务必须显式传播租户上下文；共享组不能使用全局默认数据库回退。
- 定时任务分布在多个模块，排查数据自动变化时不能只看 HTTP 请求。
- 复杂资金操作必须在数据库事务和行锁内完成，不能只依赖 Redis 锁。
- 现有代码中仍有历史 Controller 事务、复杂 `JdbcTemplate` SQL；修改时要看 Controller、Service 和 SQL 全链路。

## 4. 管理后台

- Vue 3 + Vite + Element Plus，目录 `delta-admin-ui`。
- 桌面入口固定 `/`，工作人员移动入口固定 `/h5`，不按窗口宽度自动跳转。
- 页面只通过 `src/api/*` 和 `src/utils/request.js` 请求后端。
- 权限有三层：路由 `meta.roles`、`meta.permission`、`meta.feature`；后端仍是最终安全边界。
- 新增页面必须同时接入路由、菜单过滤、权限守卫和 `/h5` 路由别名。
- 设计系统和主题变量在 `src/components`、`src/styles`。主题必须覆盖默认、暗黑和拟态三套变量。

## 5. 客户 H5 / 打手 H5 / 小程序

- uni-app，目录 `delta-mp`。
- `pages.json` 是页面注册和分包的唯一入口；未注册页面不会进入构建产物。
- `pages/` 是客户主包，`pages-player/` 是打手分包，`pages-cs/` 仅保留登录跳转页；后台工作人员功能在管理后台 `/h5`。
- 客户、打手、客服、管理员角色 Token 分开；`api/request.js` 按角色选择凭据。
- H5、小程序、微信内置浏览器必须分别验证，不能用一个环境的结果替代。

## 6. 数据与文件

- 基础建表脚本为 `delta_game.sql`；启动兼容由 `DatabaseCompatibility` 补齐字段、索引和兼容表；复杂变更另放迁移目录。
- 生产三库当前各 76 张表，基础 SQL 不是生产表数的完整来源。
- 基础表没有物理外键，应用层必须保证关联删除和状态一致。
- 上传文件按租户隔离；旧路径需要按当前 Host 解析，跨租户路径必须拒绝。
- 数据库、Redis、上传文件、支付证书和密钥不能跨客户复制。

## 7. 授权中心

对象链：`客户 -> 项目 -> 实例 -> 维护期/功能权益/授权`。

- 项目绑定软件、域名和维护期；实例绑定 UUID、域名和运行时租户编号。
- 客户端定期上报版本，读取签名授权、功能目录和正式更新目录。
- 功能商城的价格、订单和权益在授权中心；客户端只根据已购买且已启用的权益显示入口。
- 正式版本只接受 GitHub Release 的带 SHA-256 digest 资产；草稿、预览版、无摘要资产不能安装。
- 共享服务组使用 `cangshuo-shared-release-v1` 签名计划；独立实例才使用实例更新计划。

## 8. 部署与发布

- 三组件：后端 JAR、管理后台静态包、H5 静态包。
- 正式发布使用 `deploy/updater/package_release.py` 生成三个 ZIP 和 `release-manifest.json`。
- 共享组由授权中心生成计划，`shared_worker.py` 执行；执行器不接受任意命令、路径、容器名或数据库迁移。
- 完整命令、备份、回退和验收见 [OPERATIONS.md](OPERATIONS.md)。

## 9. 结构性风险

| 风险 | 影响 |
| --- | --- |
| `delta-admin` 仍集中大量聚合与集成测试 | 改动跨模块时容易漏测；新功能应优先进入业务模块 |
| 基础 SQL、启动兼容和多套 migration 并存 | 新环境、旧环境和生产表结构可能不同，必须看启动兼容和真实 MySQL |
| 大量接口聚合在 `business.js` | 页面变更可能跨多个业务域，不能按文件名猜功能 |
| H2 测试不能完全模拟 MySQL 锁和方言 | 资金、状态抢占和复杂查询必须补真实 MySQL 验证 |
| 定时任务和异步线程多 | 数据自动变化时必须检查租户上下文和调度任务 |
| `delta-mp` 有未注册遗留页面 | 看到 Vue 文件不等于页面可访问，必须查 `pages.json` |

