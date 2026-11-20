# 验证命令

以下命令在项目根目录或对应子目录执行。实际执行时记录 commit、时间、退出码和输出摘要。

## 先看变更影响

    python scripts/ai_impact.py

它会根据 FEATURE_MAP.md 的路径列出受影响功能和测试建议。

## 后端全量

    cd delta-game
    mvn -pl delta-admin -am test

包含 ArchUnit、OpenAPI baseline、提现并发和业务回归。需要 JDK 17。

## 真实 MySQL 金路径

Docker 不可用时可使用独立的本地 MySQL 实例，默认端口 `17427`。不要把测试指向生产库，也不要在客户数据库上执行测试 DDL。

先初始化一个专用数据目录并启动 `mysqld`，然后创建隔离测试库：

    mysqld --no-defaults --initialize-insecure --basedir=<mysql-home> --datadir=D:/CodexData/temp/mysql-golden-path/data-qa
    mysqld --no-defaults --basedir=<mysql-home> --datadir=D:/CodexData/temp/mysql-golden-path/data-qa --port=17427 --bind-address=127.0.0.1 --mysqlx=0 --skip-log-bin

加载 Workbench 与提现并发测试夹具：

    python deploy/qa/workbench-mysql.py > D:/CodexData/temp/workbench-qa.sql
    mysql --protocol=TCP --host=127.0.0.1 --port=17427 --user=root --default-character-set=utf8mb4 < D:/CodexData/temp/workbench-qa.sql
    python deploy/qa/withdraw-mysql.py > D:/CodexData/temp/withdraw-qa.sql
    mysql --protocol=TCP --host=127.0.0.1 --port=17427 --user=root --default-character-set=utf8mb4 < D:/CodexData/temp/withdraw-qa.sql
    python deploy/qa/refund-workflow-mysql.py > D:/CodexData/temp/refund-workflow-qa.sql
    mysql --protocol=TCP --host=127.0.0.1 --port=17427 --user=root --default-character-set=utf8mb4 < D:/CodexData/temp/refund-workflow-qa.sql

分别启用真实库开关执行测试：

    $env:WORKBENCH_TEST_MYSQL='1'
    $env:WITHDRAW_TEST_MYSQL='1'
    $env:ORDER_REFUND_TEST_MYSQL='1'
    cd delta-game
    mvn -pl delta-admin -am -Dtest=WorkbenchIntegrationTest,AdminWithdrawConcurrencyTest,OrderRefundWorkflowTest -Dsurefire.failIfNoSpecifiedTests=false test

真实 MySQL 至少验证 `FOR UPDATE`、提现并发审批/拒绝互斥、退款工作流幂等和真实表结构约束；H2 结果不能替代。

## 管理端

    cd delta-admin-ui
    npm ci
    npm test
    npm run build

测试包含 API 边界、设计系统、反馈、路由和页面静态检查。

Windows 本机若 esbuild 报“remove ... Access is denied”，把 `TEMP`/`TMP` 指向项目内可写目录后再构建，例如 `delta-admin-ui/.tmp-build`；这属于本机临时目录权限问题，不是前端编译错误。CI 使用 Linux 临时目录，不受影响。

## H5

    cd delta-mp
    npm ci
    npm test
    npm run build:h5

## 契约

    python scripts/check_endpoint_baseline.py
    python scripts/check_openapi_baseline.py
    python scripts/check_openapi_types.py

重新生成时先有意审查变化，再更新 baseline。

## 静态引用和发布归档

    python deploy/qa/check_static_assets.py delta-admin-ui/dist delta-mp/dist/build/h5

带归档检查：

    python deploy/qa/check_static_assets.py delta-admin-ui/dist --archive <admin.zip>
    python deploy/qa/check_static_assets.py delta-mp/dist/build/h5 --archive <h5.zip>

## 财务 12 项

    python deploy/migration/financial_reconciliation.py audit       --mysql-container <mysql-container>       --database <tenant-database>       --fail-on-violation

共享组示例：容器 delta-shared-mysql，数据库 delta_742_game / delta_747_game / delta_game。

## 后台浏览器审计

    $env:AUDIT_SURFACE='admin'
    $env:AUDIT_BASE='https://kkapi.idcgov.cn'
    $env:AUDIT_EXPECT_AUTH='1'
    node deploy/qa/browser-page-audit.mjs

可选：

- AUDIT_THEME=dark / neumorphism
- AUDIT_ROUTE=/player,/settlements
- AUDIT_OUTPUT=D:/CodexData/outputs/...

管理员 token 文件默认在 D:/CodexData/secrets/kk520-admin-audit-token.json。

## 共享组发布

只使用生产 worker 镜像和配置：

    shared_worker.py validate <plan.json> --config <shared-worker.json> --audit <validation.jsonl>
    shared_worker.py execute <plan.json> --config <shared-worker.json>       --confirm delta-shared-prod --audit <audit.jsonl>

执行前确认 allow_execute=true、inbox/processing 为空、无 journal.json、无维护门禁。

## 结论口径

- 构建成功只代表构建通过，不代表业务验收。
- 路由能打开只代表页面加载，不代表业务闭环。
- 单元测试、源码契约、构建、浏览器审计、生产验收要分别记录。
- 没有实际执行的内容写“未验证”，不要写成通过。
