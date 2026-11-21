# AI 项目上下文入口（Delta 1.18.3）

这份目录是给 AI 和新维护者用的“当前事实入口”，不是历史发布记录。开始改代码前先读必读规则，再用本目录定位功能和当前发布，最后读目标代码与测试。

## 阅读顺序

1. docs/VIBE_CODING.md、docs/development-conventions.md：发布、安全、版本和授权的最高约束
2. PROJECT_CONTEXT.md：系统组成、仓库、部署和多租户边界
3. FEATURE_MAP.md：功能对应到后端、管理端、H5、数据库和测试
4. PITFALLS.md：已经踩过的坑和必须回归的规则
5. CHANGE_RECIPES.md：不同改动类型的固定动作
6. inventory.json：从代码生成的 Controller、后台页面/路由、API 模块、H5 页面、数据库表、迁移和测试清单
7. MODULE_BOUNDARIES.md：15 个后端模块的包根、职责、依赖方向和测试位置
8. VERIFICATION.md：实际可执行的验证命令
9. CURRENT_RELEASE.md：当前线上版本、标签、计划和已知观察项

## 当前事实

- 共享组当前版本：1.18.3
- 正式标签：v1.18.3，提交 0c0c1cb2fc3d9fd1f944fedd0058df90fe3c9b52
- 共享组：delta-shared-prod，租户 742、747、jax
- 520 演示站有独立分支和镜像，不是共享组成员
- 最新源码不等于客户生产副本，生产版本以授权中心和发布记录为准

## 常用命令

    python scripts/ai_impact.py

根据当前 git 变更列出受影响功能、关联测试和建议验证命令。

## 使用规则

- 不要用旧发布说明推断当前版本。
- 不要只凭页面名称猜接口和数据表，先查 FEATURE_MAP.md。
- 不要直接跨租户复制数据库、Redis、uploads、环境文件或密钥。
- 改支付、退款、提现、押金、权限、租户、主题或配置前，先读 PITFALLS.md。
- 改完必须按 VERIFICATION.md 跑受影响检查；不能只凭构建成功宣称业务验收完成。
- 发现新坑时，补 PITFALLS.md 或对应测试，而不是只修当前页面。

## 维护

- 新功能上线时同步更新 FEATURE_MAP.md、project-map.json 和 CURRENT_RELEASE.md。
- 发布新版本时更新版本、标签、提交和部署事实。
- scripts/generate_ai_inventory.py 生成 inventory.json；scripts/check_ai_context.py 会校验版本、路径、契约和 inventory 是否过期。
