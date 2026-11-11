# JAX（delta）1.12.2 补丁升级记录

- 日期：2026-11-11（Asia/Shanghai）
- 版本：`1.12.2`，Git 提交 `bed3d9222aa2613e8e0626a70486dd8d83ba1d2d`
- Release：`https://github.com/zhz520/cangshuo-detla/releases/tag/v1.12.2`
- 数据库：`delta_game` 65 表，`schemaVersion=1`，无迁移
- 发布目录：`/www/delta/self-update-releases/jax-1.12.2-20261111-153846`
- 切换前备份：`/www/delta/backups/pre-1.12.2-20261111-153846`

## 变更

- 修复缺少必填请求参数时返回 500，管理接口现在返回 HTTP 400 和 `code=400`。
- 保持缺失静态资源 404、WebSocket 跨租户 Origin 403、限流 429。
- 使用 Python `zipfile` 解压 H5/后台，静态资源检查通过；未再使用会转码中文文件名的 `unzip`。

## 验收

- 后台、H5、站点 API 和历史静态资源正常；版本文件均为 1.12.2。
- 缺失参数实测 HTTP 400；正确 JS chunk 为 `application/javascript`，错误旧 chunk 为 404，中文图标为 `image/svg+xml`。
- 后端全量 316 项、后台 70 项、H5 87 项、更新器 28 项、迁移工具 21 项测试通过。
- 财务 12 项 0 违规，后端近期错误 0，授权中心当前/最新版本为 1.12.2。
- 客户 H5 登录态页面（我的、订单、钱包、消息、资料、实名、打手入驻）通过浏览器验收；51 个路由在 320/390/430 宽度无横向溢出、坏图片和资源错误。

## 回滚

后台/H5 覆盖切回 `compose.jax-upgrade-1.12.1.json`，共享后端挂回 `jax-1.12.1-20261111-135538/backend/delta-admin.jar`。数据库未变更，无需恢复数据库。
