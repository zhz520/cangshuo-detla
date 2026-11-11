# 后台 PC 与 H5 入口调整方案

整理日期：2026-11-09

## 实施状态（2026-11-09）

> 最终决定（2026-11-09）：`/mobile` 不再注册为 SPA 页面；仅保留一个静态兼容脚本，把旧 H5/旧书签分流到 `/h5`。旧公众号回调配置会自动迁移到 `/h5/wechat-binding`。下文关于旧 `/mobile` 路由兼容的段落是早期方案，以本说明和当前代码为准。

已完成第一阶段核心改造：

- PC 固定在 `/`，H5 固定在 `/h5`，移除按窗口宽度自动跳转。
- 新增 H5 路由树和 H5 布局壳。
- `/mobile`、`/mobile/*` 路由已移除；保留静态兼容脚本分流旧链接，旧公众号回调配置自动迁移到 `/h5/wechat-binding`。
- PC 登录页增加手机端入口，H5 登录页增加电脑版入口。
- 客户 H5 的 `staff-entry` 默认入口改为 `/h5/login` 和 `/h5/dashboard`。
- 后台配置示例、公众号回调说明、开发约定和版本文件同步更新。
- 管理端测试、生产构建、移动端测试、H5/小程序构建和更新执行器测试通过。
- 后端 273 项测试通过，0 failures / 0 errors；后端 JAR 版本为 1.11.0。
- 已在 375px 宽度下冒烟 H5 登录、工作台、订单、聊天、投诉、提现、打手、用户、商品、系统配置和公众号绑定；无路由错误或横向溢出。退款页在 mock 缺少 `order.refund` 权限时正确进入 403。

尚未完成的验收：

- 前台客户 31 页和打手 19 页的逐页视觉/交互回归。
- 真实登录后的 H5 工作台、订单、聊天、退款、投诉、提现和公众号绑定浏览器验收。
- 试点客户实际更新和公众号回调实测。
- 旧 `/mobile` 页面已下线；静态分流脚本待三套客户 H5 更新后删除。

## 1. 结论摘要

每个客户保持两个域名：

- 前台域名：客户 H5、微信小程序和打手业务，基本不变。
- 后台域名：管理员/客服 PC 后台，同时在同一域名下提供明确的后台 H5 入口。

后台不再根据窗口宽度自动跳转。PC 固定在 `/`，H5 固定在 `/h5/`；手机用户需要自己打开 `https://后台域名/h5/`。PC 页面仍然保留移动端适配，避免用户在手机上误开 PC 页面时完全不可用。

第一阶段推荐“一个后台构建、两套路由入口”，不拆成两个独立前端构建。原因是现有更新执行器只会替换 admin 静态目录，不能自动修改宿主机的 Nginx 配置；单构建双路由可以做到不新增组件、不改 Nginx、不破坏用户更新。

## 2. 当前状态

| 项目 | 现状 |
| --- | --- |
| 前台域名 | 例如 742 的 `chuan742.idcgov.cn`，747 的 `ycdj.kk520yy.com`，delta 的 `jax.kkimg.top` |
| 后台域名 | 例如 742 的 `chuanapi742.idcgov.cn`，747 的 `ycdjapi.kk520yy.com`，delta 的 `jaxapi.kkimg.top` |
| 后台 PC | 后台域名根路径 `/` |
| 后台 H5 | 目前是同一个 SPA 的 `/mobile` 路由 |
| 自动切换 | `App.vue` 根据 `max-width: 1024px` 自动在 `/` 和 `/mobile` 之间切换 |
| 路由生成 | `buildMobileAdminRoutes()` 为桌面路由批量生成 `/mobile` 别名 |
| 构建产物 | `delta-admin-ui/dist` 一个目录、一个 `index.html` |
| 更新执行器 | 只替换 admin 静态目录，admin 的 Nginx 配置由宿主机挂载，更新器不会改宿主机 Nginx |

因此，后台 H5 的结构调整不能只看前端路由；必须同时保证 admin 制品格式、更新执行器和旧 `/mobile` 链接兼容。

## 3. 目标域名和入口

| 客户 | 前台域名 | 后台 PC | 后台 H5 |
| --- | --- | --- | --- |
| 742 | `https://chuan742.idcgov.cn/` | `https://chuanapi742.idcgov.cn/` | `https://chuanapi742.idcgov.cn/h5/` |
| 747 | `https://ycdj.kk520yy.com/` | `https://ycdjapi.kk520yy.com/` | `https://ycdjapi.kk520yy.com/h5/` |
| delta / JAX | `https://jax.kkimg.top/` | `https://jaxapi.kkimg.top/` | `https://jaxapi.kkimg.top/h5/` |

后台 H5 不新增第三个域名，继续复用后台域名，避免新增证书、CORS、Cookie、微信网页授权域名和 DNS 配置。

建议规范：

- 后台 PC 登录：`/login`
- 后台 PC 工作台：`/dashboard`
- 后台 H5 登录：`/h5/login`
- 后台 H5 工作台：`/h5/dashboard`
- 后台 H5 公众号绑定：`/h5/wechat-binding`
- `/h5` 访问时跳转到 `/h5/`，统一保留尾斜杠。

## 4. 方案对比

| 方案 | 结构 | 更新风险 | 建议 |
| --- | --- | --- | --- |
| A. 单构建双路由 | 一个 admin SPA，`/` 为 PC，`/h5` 为 H5；同一个 ZIP | 最低，不需要改 Nginx 和更新器 | 第一阶段采用 |
| B. 单仓库双构建 | PC 输出 `dist/`，H5 输出 `dist/h5/`；仍打一个 admin ZIP | 中等，需要 Nginx 为 `/h5/...` 做 H5 index 回退；宿主机 Nginx 不由更新器更新 | 更新器支持配置后再做 |
| C. 两个独立前端/容器 | PC、H5 分别部署 | 最高，新增组件、端口、Nginx、更新计划 | 不推荐 |

方案 B 的关键问题不是构建本身，而是部署：生产 admin 的 `nginx.conf` 由宿主机挂载，`worker.py` 只替换 `/usr/share/nginx/html`，不会替客户改宿主机配置。若直接拆成 `dist/h5`，旧客户更新后可能出现 `/h5/order/list` 被根 `index.html` 接走、H5 深链打不开。

因此第一阶段用方案 A：虽然代码仍是一个 SPA，但路由、布局和页面职责明确分成 PC/H5 两套，后续可以在不破坏更新的前提下继续抽离代码。

## 5. 推荐架构

### 5.1 路由结构

```text
/                          PC 后台
/login                     PC 登录
/dashboard                 PC 工作台
/order/list                PC 订单
/system/config             PC 系统配置

/h5/                       H5 入口
/h5/login                  H5 登录
/h5/dashboard              H5 工作台
/h5/order/list             H5 订单
/h5/chat/service           H5 聊天
/h5/wechat-binding         H5 公众号绑定

/mobile                    旧入口，兼容跳转
/mobile/...                旧深链，兼容保留 query/hash
```

PC 和 H5 共用同一套业务 API、权限、登录态和 API 客户端，但使用不同 Layout 和不同的路由树。

### 5.2 源码结构建议

第一阶段不把所有页面文件搬走，先做逻辑分层，减少回归范围：

```text
src/
  main.js
  App.vue
  router/
    index.js              # 合并 PC/H5 路由
    pc.js                 # PC 路由树
    h5.js                 # H5 路由树
  layout/
    index.vue             # PC 布局，保留移动端适配
    H5Layout.vue          # 明确的后台 H5 移动壳
  utils/
    mobile-route.js       # 改为 h5-route.js，保留旧 /mobile 兼容逻辑
    responsive.js         # PC 端继续用于断点和表格卡片
    mobile-table-cards.js # 继续复用于 PC 窄屏和 H5
  views/                  # 现有页面尽量复用
  h5/                     # 可选：H5 专属页面和包装组件
```

后续可把 PC 专属代码移到 `src/pc/`，H5 专属代码移到 `src/h5/`，共享部分留在 `src/shared/`；这一阶段仍保持一个 Vite 构建。

### 5.3 PC 和 H5 的职责

PC：

- 使用现有 `layout/index.vue`。
- 保留 `useResponsive`、`responsive.css`、`mobile-table-cards.js` 和 `installViewport`。
- 在手机上打开 PC 路由时不自动跳转，但布局仍可读、表格仍能转卡片、底部导航仍可用。
- PC 登录页增加一个“手机端入口”链接，指向 `/h5/login`，只作为入口，不自动跳转。

H5：

- 使用新的 `H5Layout.vue`，只保留移动端顶部栏、底部导航、账号抽屉和安全区处理。
- 不渲染 PC 侧栏，不依赖窗口宽度决定布局。
- 复用现有页面和 API；工作台直接使用 `MobileDashboard`。
- 列表页继续复用移动表格卡片适配。
- H5 登录页和 H5 工作台必须有直接可访问的 URL，刷新和深链不能 404。

## 6. `/mobile` 下线与配置迁移

最终决定：`/mobile` 不再作为 SPA 页面。

- `/mobile`、`/mobile/*` 路由从 SPA 移除；根 `index.html` 的静态脚本把旧链接分流到 `/h5`。
- 旧公众号配置 `/mobile/wechat-binding` 在后台读取或发起授权时自动改写为 `/h5/wechat-binding`。
- 客户 H5 的 `staff-entry`、客服待办和后台文档统一使用 `/h5/login`、`/h5/dashboard`。
- 不依赖 Nginx 做旧路径跳转；旧路径不再注册。

## 7. 代码改造清单

### 前端后台

| 文件/区域 | 调整 |
| --- | --- |
| `delta-admin-ui/src/App.vue` | 删除按窗口宽度自动切换路由的 watcher 和监听；保留全局加载、站点配置错误提示 |
| `delta-admin-ui/src/router/index.js` | 拆分 PC/H5 路由；白名单加入 `/h5/login`；移除 `/mobile` 路由和自动 viewport 分流 |
| `delta-admin-ui/src/utils/h5-route.js` | 提供 `h5Path`、`isH5Path`、`h5RedirectTarget`；不再生成旧 `/mobile` 路由 |
| `delta-admin-ui/src/layout/index.vue` | 保留 PC 侧栏和 PC 移动适配；移除基于路由自动切换的登录跳转 |
| 新增 `delta-admin-ui/src/layout/H5Layout.vue` | 移动端专用壳：顶部栏、底部导航、账号抽屉、表格卡片安装 |
| `delta-admin-ui/src/views/dashboard/index.vue` | H5 路由下强制渲染 `MobileDashboard`；PC 仍按窗口宽度决定 |
| `delta-admin-ui/src/views/login/index.vue` | PC 增加“手机端入口”链接；H5 路由使用移动版样式 |
| `delta-admin-ui/src/styles/responsive.css` | 保留 PC 响应式；补充 `/h5` 页面安全区、横向溢出和触控尺寸 |
| `delta-admin-ui/test/h5-route.test.mjs` | 覆盖 `/h5` 路由、H5 会话内跳转和旧 `/mobile` 不存在 |
| 新增 `delta-admin-ui/test/h5-layout.test.mjs` | 覆盖 H5 入口、无自动跳转、深链和旧链接 |

### 前台移动端

| 文件/区域 | 调整 |
| --- | --- |
| `delta-mp/utils/staff-entry.js` | 默认入口从 `/mobile/login` 改为 `/h5/login`，工作台改为 `/h5/dashboard` |
| `delta-mp/pages-cs/login/index.vue` | 继续只做工作人员跳转，不改业务 |
| `delta-mp/pages/workbench/index.vue` | 客服跳转使用新的 H5 地址 |
| `delta-mp/test/staff-entry.test.mjs` | 更新默认路径断言，确认不再生成 `/mobile` |
| `delta-mp/test/staff-binding-entry.test.mjs` | 更新后台入口和公众号绑定路径测试 |

### 后端和配置

| 文件/区域 | 调整 |
| --- | --- |
| `SysConfigController.java` | 新配置示例改为 `https://staff.example.com/h5/wechat-binding`；旧 `/mobile/wechat-binding` 配置自动改写 |
| `OfficialWechatController/Service` 相关测试 | 覆盖新 `/h5/wechat-binding`，并验证旧配置在读取时归一化 |
| `catalog.json` | 更新发布说明和版本号；新增 H5 入口说明 |
| `development-conventions.md` | 后台手机入口固定为 `/h5`，`/mobile` 已移除 |
| `VIBE_CODING.md`、`项目结构说明.md` | 同步新的入口和路由结构 |

### 不建议改动

- 不改动 API 路由和业务 Service。
- 不改动授权中心组件列表。
- 不改动 `package_release.py` 的三组件格式。
- 不改动 `worker.py` 的组件名和健康检查路径。
- 第一阶段不改宿主 Nginx 配置。

## 8. 前台页面检查清单

前台基本不变，但需要逐页回归。客户主包 31 页，打手分包 19 页。

客户主包重点：

- 首页、分类、打手列表、打手详情。
- 商品列表、商品详情。
- 下单、游客下单/查单、订单支付。
- 订单列表、订单详情、评价、投诉列表/详情/创建。
- 钱包、充值、我的、资料、实名、收藏关注、隐私中心。
- 聊天列表、聊天室、系统消息、待办中心。
- 登录、维护页、用户协议、隐私政策、公众号绑定。

打手分包重点：

- 入驻申请、我的主页、编辑资料。
- 接单大厅、我的订单、订单详情、提交进度。
- 邀请列表、邀请队友。
- 收益、收益明细、提现、提现记录、提现账户。
- 评价列表、客服/工作人员入口。

每页至少检查：

- 320、375、390、414 宽度。
- iOS 安全区和微信内置浏览器。
- 直接打开、刷新、返回、深链、查询参数。
- 登录/未登录、游客/正式用户、打手身份。
- 加载、空数据、错误、无权限。
- 横向溢出、表格/卡片转换、底部按钮遮挡。
- H5 构建和微信小程序构建均通过。

## 9. 后台页面检查清单

PC 端 46 个页面和面板全部检查，优先：

- 登录、仪表盘、订单列表/详情、派单。
- 用户、打手、退款、投诉、履约审核。
- 聊天、待办、系统通知、公众号绑定。
- 系统配置、角色权限、更新页面、功能商城。

H5 端优先：

- `/h5/login`
- `/h5/dashboard`
- `/h5/order/list`（订单详情使用页面内抽屉）
- `/h5/chat/service`
- `/h5/complaint`、`/h5/refund`
- `/h5/withdraw`、`/h5/player`
- `/h5/dashboard` 内的待办中心和系统通知抽屉
- `/h5/wechat-binding`

每页检查直接打开、刷新、登录回跳、权限拒绝、空数据、长文本、手机键盘、底部固定操作和安全区。

## 10. 更新安全设计

这是本次改造最重要的约束。

### 10.1 保持 admin 制品契约不变

现有 `cangshuo-admin.zip` 的契约是：

```text
index.html
cangshuo-version.json
assets/...
```

更新执行器会检查根目录 `index.html` 和 `cangshuo-version.json`，然后把整个目录挂到 `/usr/share/nginx/html`。

单构建双路由方案继续满足这个契约：

```text
index.html                 # PC 入口
cangshuo-version.json
assets/...
```

H5 只是同一 SPA 的 `/h5` 路由，不需要新增 `h5/` 子目录，也不需要新的组件包。

### 10.2 不改宿主机 Nginx

当前 admin 的 `nginx.conf` 是宿主机挂载文件，更新执行器不会改它。因此：

- 不依赖新增 `location /h5/`。
- 不依赖 `/h5/index.html` 的独立回退。
- 依赖现有 `try_files $uri $uri/ /index.html` 把 `/h5/...` 深链交给同一个 SPA。

这样旧客户更新 admin ZIP 后，路由立即可用，不会出现“程序更新了但 Nginx 没改”的问题。

### 10.3 `/mobile` 下线

- `/mobile`、`/mobile/*` 不再注册，静态脚本将旧链接分流到 `/h5`。
- 旧公众号配置自动改写为 `/h5/wechat-binding`。
- 发布前验证旧配置的授权发起和回调，不依赖旧路径本身可访问。

### 10.4 不动授权中心

- 更新计划仍然是 backend/admin/h5 三个组件。
- 不新增 `admin-h5` 组件。
- 不修改组件版本比较逻辑。
- 不修改 `cangshuo-version.json` 的字段和位置。

### 10.5 版本和回滚

本次是可见的入口结构调整，建议使用 MINOR：`1.11.0`。

- `/mobile` 已移除，但只影响旧工作人员入口和旧配置；配置会迁移，所以仍按 MINOR 发布。
- 不是单纯缺陷修复，不建议只升 PATCH。
- 发布前保留旧 admin 制品和 `component-versions.json`。
- 回滚只回滚 admin 组件，不涉及数据库迁移。
- 回滚到旧 admin 制品后旧 `/mobile` 仍可用，但新 H5 路由会消失；回滚需要同时评估入口影响。

## 11. 实施阶段

### 阶段 0：基线和页面清单

- 导出当前 PC 路由、H5 路由、页面清单和移动端入口。
- 导出现有 `staff_web_url`、公众号回调配置和旧 `/mobile` 使用点。
- 选定一个可人工回退的试点客户。

### 阶段 1：路由和壳层

- 删除自动 viewport 跳转。
- 增加 `/h5` 路由树和 `H5Layout`。
- 移除 `/mobile` 路由，并加入旧公众号配置迁移。
- 更新 PC 登录页手机入口。
- 更新 staff-entry 和公众号回调文档。
- 通过路由、登录、深链、旧链接测试。

### 阶段 2：页面适配

- PC 端保留响应式表格卡片和窄屏布局。
- H5 端优先完成登录、仪表盘、订单、聊天、退款、投诉、提现、待办和公众号绑定。
- 前台客户/打手页面逐页回归。
- 修复横向滚动、底部遮挡、键盘、安全区和返回路径。

### 阶段 3：发布和试点

- 版本 1.11.0。
- 构建 admin、H5、后端，打包三个 ZIP。
- 先在试点客户更新，验证：
  - `/`、`/login`、`/dashboard`；
  - `/h5/`、`/h5/login`、`/h5/dashboard`；
  - `/mobile` 静态分流到 `/h5`；
  - 旧 `/mobile/wechat-binding` 配置自动改写后能完成回调；
  - 手机不自动跳转；
  - PC 在手机上仍能操作。
- 观察后再通知其他客户更新。

### 阶段 4：后续清理

- `/mobile` 已移除，静态兼容脚本待所有客户 H5 更新后删除。
- 若确实需要独立 H5 构建，先升级更新器支持宿主机 Nginx 配置或提供安全的 `/h5/...` 回退方案，再拆分。

## 12. 验收清单

- [ ] 后台根路径默认 PC，不再按窗口宽度自动跳 H5。
- [ ] `/h5/` 可直接打开，刷新不 404。
- [ ] `/h5/login` 登录后回 H5 工作台。
- [ ] PC 登录页有明确手机端入口。
- [ ] PC 页面在 375px 宽度仍可读、可操作、无横向溢出。
- [ ] H5 页面有独立底部导航、安全区和账号抽屉。
- [ ] `/mobile` 旧路径静态分流到 `/h5`。
- [ ] 旧公众号回调配置自动改写为 `/h5/wechat-binding`，query 和 hash 不丢失。
- [ ] 前台客户 31 页、打手 19 页逐页检查。
- [ ] 后台 PC 46 个页面/面板检查。
- [ ] 管理端、移动端、后端测试和构建通过。
- [ ] admin ZIP 仍是根 `index.html` + `cangshuo-version.json`。
- [ ] 更新执行器不需要新增组件或修改 Nginx。
- [ ] 试点客户更新成功，失败可回滚 admin。

## 13. 需要确认的决策

1. `/h5` 是否统一使用尾斜杠 `/h5/`。建议是。
2. PC 页面在手机上是否保留当前紧凑布局和底部导航。建议保留，避免误开 PC 页面时完全不可用。
3. 是否接受第一阶段“一个构建、两套路由”，暂不做两个独立构建。建议接受，优先保证用户更新安全。
4. 试点客户选 742、747 还是 delta。建议选一个可人工回退、访问量可控的客户。
5. 版本是否按 `1.11.0` 发布。建议是。

