# 验证命令

以下命令在项目根目录或对应子目录执行。实际执行时记录 commit、时间、退出码和输出摘要。

## 先看变更影响

    python scripts/ai_impact.py

它会根据 FEATURE_MAP.md 的路径列出受影响功能和测试建议。

## 后端全量

    cd delta-game
    mvn -pl delta-admin -am test

包含 ArchUnit、OpenAPI baseline、提现并发和业务回归。需要 JDK 17。

## 管理端

    cd delta-admin-ui
    npm ci
    npm test
    npm run build

测试包含 API 边界、设计系统、反馈、路由和页面静态检查。

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
