# 项目上下文

## 1. 系统组成

Delta 是电竞代练/陪玩业务系统，主要组件：

- 后端：Spring Boot 模块化单体，入口 delta-game/delta-admin
- 管理端：Vue 3 + Vite + Element Plus，目录 delta-admin-ui
- 客户 H5 / 打手 H5：uni-app，目录 delta-mp
- 数据库：MySQL 8，Redis 7
- 授权中心：独立项目 cangshuo-detla-authorize，负责客户、实例、授权、版本和支付计划
- 共享服务组：JAX、742、747 共用一个后端/MySQL/Redis/静态服务组，但各自独立数据库、租户文件、账号和授权

## 2. 代码目录

- delta-game/delta-admin：启动模块、后台聚合、平台/工作台/统计、部分治理
- delta-game/delta-common：安全、租户、文件、公共能力和基础设施
- delta-game/delta-user：用户、登录、邀请
- delta-game/delta-player：打手、钱包、提现、押金
- delta-game/delta-product：商品、分类
- delta-game/delta-order：订单、协同、履约
- delta-game/delta-pay：微信支付、退款、交易流水
- delta-game/delta-message：站内消息
- delta-game/delta-cs：客服、客服分配、客服提现审核
- delta-game/delta-system：管理员、角色、配置、内容、公告
- delta-admin-ui/src/views：管理端页面
- delta-admin-ui/src/api：管理端 API 封装；新页面不得绕过
- delta-admin-ui/src/components：设计系统组件
- delta-mp/pages、pages-player：客户 H5 和打手 H5
- delta-mp/api：H5 API 封装
- deploy/：Compose、Nginx、更新器、QA 和迁移工具
- docs/：项目规则、发布记录、架构和业务说明

## 3. 请求链路

后端典型链路：

    Controller -> Service -> Mapper -> MySQL/Redis

- Controller 只做参数、权限和响应，不直接依赖 Mapper。
- 业务状态和金额校验放在 Service/领域逻辑。
- 管理端页面只调用 src/api 中的函数，不直接写 request.get/post。
- H5 页面只调用 delta-mp/api 中的函数。
- 所有租户请求通过 Host/TenantContext 选择租户；文件路径、Redis key、数据库连接都带租户边界。

## 4. 多租户边界

- 共享组租户：742、747、jax，各自数据库：delta_742_game、delta_747_game、delta_game
- 不能把 520 的数据库、上传文件、账号、授权或配置复制到共享组。
- 不能假设“相同 ID 就是同一条数据”，订单、用户、支付、文件都可能跨租户同 ID。
- 跨租户文件请求应返回 404/拒绝，不能回退到共享根目录。
- 定时任务和异步线程必须传递租户上下文。

## 5. 版本与部署

- 版本规则见 docs/development-conventions.md。
- 共享组更新只走 cangshuo-shared-release-v1 签名计划、shared_worker 和 cangshuo-shared-worker.service。
- 共享组不能使用实例更新页、/api/client/update-plan 或旧 worker.py。
- 520 演示站是独立 Compose 项目，有自己的镜像和发布记录。
- 客户副本目录不是最新源码，不能从客户副本反向覆盖生产。

## 6. 当前状态

- 共享组：1.18.2，已通过计划 885c15ba-4648-4c0d-a715-cca71374d39b 发布
- 520 演示站：1.16.5 演示基线 + 架构/UI 补丁，管理端镜像 arch-20261120g，H5 未改
- 正式标签：v1.18.2，发布来源分支 codex/release-1.18.2
- 当前默认主线：main；发布标签之后的文档提交不代表生产重新部署

详细发布事实见 CURRENT_RELEASE.md。
