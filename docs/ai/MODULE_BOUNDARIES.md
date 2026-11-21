# 后端模块边界（2026-11-21）

这份文件说明 17 个 Maven 模块的职责、包根和依赖方向。改后端代码前先按这里定位模块；不要把新代码继续堆进 `delta-admin`。

## 模块与包根

| 模块 | Java 包根 | 主要职责 |
| --- | --- | --- |
| `delta-common` | `com.delta.common` | 安全、JWT、Redis、文件、聊天、短信客户端、维护模式、公共响应 |
| `delta-system` | `com.delta.system` | 管理员、角色、系统配置、公告、内容、操作日志、日统计 |
| `delta-user` | `com.delta.user` | 用户登录、资料、钱包、游客下单、收藏关注 |
| `delta-player` | `com.delta.player` | 打手入驻、主页、接单大厅、履约、收益、提现、押金 |
| `delta-product` | `com.delta.product` | 商品、分类、规格、动态下单字段 |
| `delta-order` | `com.delta.order` | 订单生命周期、进度、评价、投诉、打手履约接口 |
| `delta-pay` | `com.delta.pay` | 微信支付、余额支付、充值、押金、退款、支付补偿 |
| `delta-message` | `com.delta.message` | 站内消息和消息服务 |
| `delta-cs` | `com.delta.cs` | 客服/管理员登录、客服订单、聊天、投诉、退款、提现、换人 |
| `delta-platform` | `com.delta.platform` | 平台授权、版本目录、主题、维护过滤、收益分析 |
| `delta-workbench` | `com.delta.workbench` | 后台/客服移动工作台聚合 |
| `delta-reporting` | `com.delta.reporting` | 访问分析、仪表盘统计、后台统计服务和接口 |
| `delta-identity` | `com.delta.identity` | 短信网关、实名认证、实名计费 |
| `delta-notification` | `com.delta.notification` | 站内通知、公众号、邮件、短信通知和队列 |
| `delta-marketing` | `com.delta.marketing` | 优惠券、会员、邀请、打赏和抢单规则 |
| `delta-workforce` | `com.delta.workforce` | 员工角色、排班、客服分配和订单协同指派 |
| `delta-admin` | `com.delta.admin` | 唯一启动模块、后台订单/用户/打手聚合、待办 |

## 依赖规则

1. 业务模块只能依赖 `delta-common` 和其 pom 中声明的下层模块；不得依赖 `delta-admin`。
2. `delta-admin` 是启动和聚合层，可以依赖所有业务模块；新业务优先进入对应模块，不要继续把 Controller/Service 堆进启动模块。
3. `delta-common` 不得依赖任何业务模块。
4. Controller 不直接依赖 Mapper；统计等查询放到 Service。ArchUnit 的 `controllersMustNotDependOnMappers` 规则会检查新增违规。
5. 跨模块只通过 Service 接口或 `CrossModuleMapper` 的只读查询；不要制造循环依赖。
6. `platform/catalog.json` 保留在 `delta-admin/src/main/resources/platform/`，因为发布脚本和更新器按 `BOOT-INF/classes/platform/catalog.json` 校验版本；不要只移动文件而不同步打包链路。

## 测试位置

- 模块内部的包级私有方法，其单元测试应放在同包的新模块测试目录，例如 `delta-notification/src/test/java/com/delta/notification/service/`。
- 需要启动完整后台上下文、依赖 `DeltaApplication` 或 `MySqlTestSupport` 的集成测试，继续放在 `delta-admin/src/test/java/`。
- 纯结构拆分必须保持 endpoint、OpenAPI operation 和 schema 不变；结构变更后运行 clean verify 和契约检查。

## 验证命令

```powershell
cd D:\codeStudy\code\cangshuo-detla\delta-game
mvn -pl delta-admin -am clean verify

cd D:\codeStudy\code\cangshuo-detla
python scripts/check_ai_context.py
python scripts/check_module_boundaries.py
python scripts/check_endpoint_baseline.py
python scripts/check_openapi_baseline.py
python scripts/check_openapi_types.py
python deploy/qa/check_collaboration_rules.py
```
