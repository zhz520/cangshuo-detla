# 黑白灰 UI 更新

本次统一用户端、接单员端、客服端和 PC 管理后台的配色、图标色、表单、按钮、卡片与阴影。页面结构、路由、文案、数据接口和业务逻辑保持原样。订单状态、错误提示、通知等保留辨识色。

## 样式入口

- 移动端主题变量：`delta-mp/uni.scss`。
- 移动端公共外观：`delta-mp/styles/monochrome.scss`，由 `App.vue` 引入。
- 管理端公共样式与 Element Plus 变量：`delta-admin-ui/src/style.css`。
- 页面与组件 scoped 样式同步改为中性色；原有商品图片、头像和运营数据继续从原接口加载。

## 验证

- 管理后台和 H5 生产构建通过。
- 49 个页面的浏览器检查无白屏、横向溢出或 JavaScript 运行错误；使用本地测试数据。
- 检查了登录方式切换、协议勾选、分类选择、后台筛选重置、菜单折叠和新增弹窗。
- 对全部改动的 Vue 文件核对：除样式及颜色字面值以外，模板和脚本保持一致；pages.json 的非颜色配置不变；后端和依赖锁文件未修改。
- 上线后检查了 H5 首页、分类、登录页和管理后台登录页，以及域名下 JS、CSS、公开站点配置接口。

## 本次服务器发布

- 版本：`20261023-184526`。
- 发布目录：`/www/delta/ui-releases/20261023-184526`，包含 `admin` 和 `h5`。
- 两个前端通过只读目录挂载使用新版静态文件，仍沿用原来的 Nginx 配置和镜像。
- 同步修改了宝塔实际使用的 `/www/server/panel/data/compose/delta/docker-compose.yaml` 和原始 `/www/delta/docker-compose.yml`，后续通过面板重建仍会使用新版 UI。
- 只重建 `delta-admin-ui`、`delta-h5`；后端、MySQL、Redis 容器 ID 未变化。
- 保留了旧版带哈希的资源文件，兼容更新前已经打开的浏览器页面。
- H5 发布构建将 `VITE_WS_BASE_URL` 设为 `wss://jax.kkimg.top/ws/chat`，替代作者镜像中写死的外部 IP；聊天逻辑未修改。

## 备份与回滚

- 服务器备份：`/www/delta/ui-backups/20261023-184526`。
- 包含原版前端文件、两个编排文件、Nginx 配置、发布清单及 `rollback.sh`。
- 本地备份：`D:\CodexData\backups\detla-ui-20261023-184526\server-frontends.tar.gz`。
- 如需回滚，可在宝塔文件管理器查看该目录的 `rollback.sh`，在宝塔终端运行该脚本。它会恢复原编排，只重建两个前端服务。
- 当前发布不修改数据库或上传文件。备份目录不包含数据库数据与 `.env`，数据库备份应继续按既有方案执行。

网站若仍显示旧颜色，先强制刷新或清除浏览器站点缓存。
