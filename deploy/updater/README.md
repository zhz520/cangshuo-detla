# 正式版更新执行器

本目录同时包含独立实例和共享服务组两套更新协议，必须先确认目标部署形态：

- 独立实例：客户在系统更新页面检查授权中心同步的正式版，选择更新项并点击安装；发布方不代客户点击。
- JAX、742、747 共享服务组：客户后台只展示版本和变更说明，不提供共享后端独立安装按钮；发布方使用授权中心 `delta-shared-prod` 服务组计划和 `cangshuo-shared-worker.service` 统一发布。

两套通道不能混用。共享组规则、发布步骤、备份和回退见 `docs/OPERATIONS.md`。授权中心只签发维护期内、符合付费授权的更新计划。非必要内容可以逐项随时取消；必要更新和所选内容的依赖自动加入，依赖被取消时其可选后续项也会取消。客户端只有验证公钥。

`package_release.py D:\CodexData\outputs\detla-release` 将已验证的后端、后台、H5 产物打为三个 ZIP，并生成包含 Git commit、工作区是否干净和三个 SHA-256 的 `release-manifest.json`。上传至对应版本 GitHub Release；授权中心通过 Releases API 读取 GitHub 提供的 SHA-256 digest。无 digest、预览版、草稿和未配置更新内容的版本不能安装。

打包脚本只允许从干净的 Git 工作区执行，并拒绝打包已经存在的版本标签。正确顺序是先完成测试与构建，从待发布提交打包，再创建正式标签和上传 Release，避免同一版本号对应两份不同程序。

`worker.py /absolute/path/worker.json` 由宿主机 systemd 管理，不监听新端口。配置由运维写入，包括 `root`、`state_dir`、`customer_id`、`product_id`、`repository`、`compose_directory`、`compose_files`、`mysql_container`、`containers`（backend/admin/h5）和 `backup_paths`、`authority_url` 和可选的 `verifier_image`。客户端仅向自己的状态目录提交签名计划，不能提供服务器命令或路径。

微信支付证书与普通上传文件都必须位于持久化目录。当前版本默认把支付证书写入 `FILE_UPLOAD_PATH/private/wxpay`；旧实例仍兼容 `/www/data/private/wxpay`。生产 Compose 应将该目录绑定到宿主机专用目录，并把宿主机目录同时加入 `backup_paths`，否则重建后端容器会导致数据库仍记录证书路径、实际文件却丢失。

执行器先验证 Ed25519 签名、实例绑定、有效期和组件版本，再下载并检查摘要、归档路径和内部版本。安装前暂停新业务、备份 MySQL 与客户文件；通过额外 Compose 挂载独立发布目录，保留原环境变量、支付配置、业务数据、域名和品牌。后端、管理后台和 H5 按依赖顺序分别启动，每一步完成运行与接口健康检查后再启动下一组件，避免较慢的 Java 启动被 Compose 的依赖超时误判。健康检查失败自动回退；回退失败保留业务暂停标记，防止继续运行损坏程序。执行意外中断后按宿主私有日志恢复。备份目录仅 root 可访问。

当前更新包只接受数据库 schemaVersion=1，不执行数据库结构迁移。需要修改表结构的版本必须先增加可验证的迁移及回退协议，不得用当前执行器强行安装。程序回退不覆盖更新期间已确认的支付回调；数据库备份保留供运维恢复。

后端启动模块负责执行经过审查的安全新增字段兼容检查：在 Web 服务开始承接业务请求前检查必需列，缺失时按带安全默认值的加列语句补齐，并保留重复执行能力。新版本如新增字段，必须同步把检查加入 `DatabaseCompatibility` 并配套测试；不能只升级 Java 实体和查询。破坏性变更、数据改写和复杂回退仍须使用单独迁移工单，不能依赖启动检查。

各组件分别记录已安装版本和更新项，项目当前版本取组件的最低版本，避免先更新后端后隐藏尚未更新的后台/H5 内容。未确认在途订单规则的现有项目保留 PENDING 策略，不自动启用到期拦截。选择策略后，到期依据本地签名维护凭据执行，续期同步后恢复。

其他 Web/H5/小程序项目可在授权中心登记各自软件、仓库、项目、实例、维护期、模块与支付配置，并导出实例接入凭据。新客户端须实现 `/api/client/report` 协议、公钥验签、域名和维护期校验；此 Delta Java 执行器不会自动兼容不同技术栈。

私有仓库令牌只保存在授权中心。客户端通过短期签名计划请求中心转发更新包，不保存 GitHub 令牌。旧版 Linux 主机通过 Node 容器验签和下载，避免旧版系统 OpenSSL 缺少 Ed25519 和根证书的问题。

## 共享租户注册前检

`python deploy/updater/shared_registry.py validate <registry.json>` 校验共享租户注册文件的租户标识、域名唯一性、MySQL JDBC 地址、启用租户的数据库凭据字段和平台授权字段。

`redact` 输出可审计的脱敏副本；`sql --tenant <id>` 只生成创建数据库、租户账号和授权的 SQL 模板，密码位置保持占位符。该工具不连接数据库、不执行 SQL，也不替代备份、数据导入、支付/退款对账或客户切换。

注册文件包含敏感凭据，生产上必须限制为运维账号可读，不能提交到 Git 或通过聊天发送。

## 新增租户凭据合并

`python deploy/updater/tenant_onboarding.py merge --authorization-export <runtime-tenants.json> --credentials <tenant-credentials.json> --output <tenant-registry.json> --allowed-root <config-dir>` 将授权中心 `/api/runtime/tenants` 导出的租户元数据与本地受保护凭据合并为 `shared_registry.py` 可校验的注册文件。默认只输出脱敏摘要和字段名差异；增加 `--apply` 才写入目标。

授权导出中的 `tenantId` 必须是显式运行租户标识（例如 `jax`、`742`、`747`），`platformInstanceId` 只作为 UUID 身份一致性校验。工具不会从实例 UUID 反推 `tenantId`。凭据文件的每个条目也必须重复填写同一显式 `tenantId`；`platformInstanceId` 可选，填写时必须与授权导出一致。

```json
{
  "formatVersion": 1,
  "tenants": [
    {
      "tenantId": "jax",
      "platformInstanceId": "00000000-0000-0000-0000-000000000001",
      "jdbcUrl": "jdbc:mysql://mysql:3306/delta_jax?useSSL=false",
      "username": "delta_jax",
      "password": "<root-only-credential>",
      "minimumIdle": 0,
      "maximumPoolSize": 8,
      "miniappEnabled": false
    }
  ]
}
```

合并会拒绝授权导出中的凭据字段、重复域名、启用租户缺少本地数据库凭据、凭据租户不在授权导出、`platformInstanceId` 不一致以及路径越界。目标必须位于 `--allowed-root` 下，父目录必须已经存在；最终文件不得是符号链接，写入使用同目录临时文件和原子替换，POSIX 权限为 `0600`。目标中不属于本次授权导出的既有租户会保留，不会因导出只包含部分租户而被删除。

凭据文件在 POSIX 上必须仅所有者可读。可附加 `--audit-log <audit.jsonl> --actor <operator>` 记录脱敏审计，内容只包括操作者、时间、输入 SHA-256、租户集合和结果，不包含密码、小程序密钥、JDBC 地址或用户名。

`python deploy/updater/tenant_onboarding.py sql-template --authorization-export <runtime-tenants.json> --credentials <tenant-credentials.json> [--tenant jax]` 只生成建库、建账号和授权 SQL 模板，密码位置固定为占位符，不连接数据库，也不输出真实密码。该工具不执行生产变更，建库、导入、备份、对账、维护窗口和客户验收仍按共享迁移门禁执行。

## 共享服务组发布计划

`python deploy/updater/shared_release.py config-example` 输出共享服务组执行器配置模板。`validate <envelope.json> --config <config.json>` 验证 Ed25519 签名、服务组绑定、有效期、维护窗口、数据库 schema=1、备份要求、租户注册文件中的启用租户集合、组件版本不降级、三个制品的大小/摘要和 GitHub Release 地址。

计划只包含发布标签、版本、租户、时间窗和三个制品摘要，不包含服务器命令或任意路径。主机路径、容器名、Compose 文件和密钥位置全部来自运维配置。`--require-window` 只在维护窗口内接受计划；`--audit` 追加脱敏审计记录。输出是 dry-run 步骤，不启动容器、不连接数据库、不执行迁移。

签名信封格式为 `cangshuo-shared-release-v1`，payload 是同一字段的 JSON 的 base64url，签名为 `format + "." + payload` 的 Ed25519 签名。`inspect` 仅在验签后输出计划内容；不要把含凭据的配置或私钥打印到日志。该协议已在 2026-11-11 完成首个 1.13.0 生产共享发布，当前用于 JAX、742、747。

## 共享服务组执行器

`python deploy/updater/shared_worker.py execute <envelope.json> --config <config.json> --confirm <service-group-id>` 只接受已验签、处于维护窗口内、租户集合与注册文件一致的计划。执行顺序固定为：获取独占锁、逐租户 `mysqldump` 和文件归档、下载并校验三个制品、按组件解包和版本核对、写入 Compose 覆盖文件、后端健康检查、后台健康检查、H5 健康检查、写入当前版本和审计记录。

`health_host` 必须是该服务组启用租户的登记域名，用于共享后端带 Host 的站点健康检查。`compose_override` 默认是 `<root>/compose.shared-release.json`；`release_retention` 至少为 2，确保回退目标不会被清理。`allow_execute` 为 false 时只允许 `validate`。Compose 文件、容器名、MySQL 容器、备份路径和公钥都来自运维配置，不接受签名计划提供的命令或路径。

失败时恢复原 Compose 覆盖文件、原 `current.json` 和上一个版本，删除本次已安装标记和发布目录；回退健康检查失败会保留 `journal.json` 和 `maintenance` 门禁，禁止继续执行。`python deploy/updater/shared_worker.py recover --config <config.json>` 按宿主私有日志恢复。审计记录只包含服务组、发布 ID、版本、租户和结果，不含签名、凭据或业务数据。

执行器不执行数据库迁移，计划仍只接受 `schemaVersion=1`。真实共享组发布必须另行取得当前任务的变更授权，完成维护窗口、逐租户备份和生产验收；不能把单元测试或 dry-run 当作生产验收。

`compose_files` 中的相对文件名会按 `compose_directory` 解析，不依赖执行器当前工作目录。2026-11-10 已在 742 暂存 Compose 项目用当前后端 JAR、后台和 H5 产物完成覆盖安装；三个组件健康检查通过，移除覆盖后基础容器再次健康检查通过。该演练使用的是当前版本产物，不是跨版本回退。

同一天又用一个无法启动的后端 JAR 模拟更新失败：健康检查按预期失败，恢复原 fixture 后三组件再次健康检查通过。该演练验证失败检测和恢复路径，但没有使用两个不同的正式版本，不能替代跨版本回退验收。

随后用当前 JAR 生成 catalog 版本 `1.11.1` 的合成前置包，完成 `1.11.1` 到当前 `1.11.2` 的升级，再用损坏的 `1.11.3` 后端触发失败并恢复 `1.11.2`；三组件在每个健康阶段均通过版本检查。该演练验证跨版本升级/回退机制，但 `1.11.1` 不是正式 Release，`1.11.3` 也不是真实版本差异，正式发布包和客户迁移仍需验收。

## 共享服务组守护模式与安装

共享生产主机为 CentOS 7，系统 Python 3.6 不带 `cryptography`，因此执行器不由宿主机 Python 直接运行。`Dockerfile.shared-worker` 使用 Python 3.11、固定 `cryptography==46.0.5`，并从 Docker 官方 CLI 镜像复制 `docker` 与 `docker compose` 插件。容器通过只读挂载 `/var/run/docker.sock` 调用宿主 Docker，因此该容器实际具有宿主 Docker 控制权限，只允许 root 安装、启动和维护，不能暴露公网或交给普通用户。

`shared_worker.py daemon --config <config.json> --inbox <private-dir> --interval 5` 只会轮询私有 inbox 中的 JSON 计划，逐份验签并执行。文件先原子移动到 `processing/`，成功后进入 `processed/`，失败进入 `rejected/` 并写入只含异常类型的 error 文件。`allow_execute=false` 或存在未恢复 `journal.json` 时，守护模式拒绝启动。

生产安装顺序：

1. 将授权中心验证公钥放到配置指定的 `public_key_path`，权限可为公开只读；
2. 把 `shared-worker.json.example` 复制为 root-only 实际配置，先保持 `allow_execute=false`；
3. 由 `install_shared_worker.py` 构建镜像、创建 0700 状态目录并安装 `cangshuo-shared-worker.service`；
4. 用真实签名计划运行 `shared_worker.py validate`，确认服务组、租户集合、维护窗口和三个制品摘要；
5. 完成当前版本备份和回退演练后，才把配置改为 `allow_execute=true` 并启用服务。

`allowed_root`、Compose 文件、容器名、备份路径和公钥路径全部来自运维配置文件；签名计划不能提供命令、路径或容器名。共享执行器仍只接受 `schemaVersion=1`，不执行数据库迁移。

当前客户端仓库为私有仓库时，运维配置必须设置 `github_token_path`。令牌文件只允许 root 读取（0600），执行器通过 GitHub Release API 解析资产 ID，再由短期签名下载地址获取制品；跳转到 GitHub 资产域时不会携带令牌。令牌不得写入镜像、签名计划、日志或普通文档。

2026-11-11 已在生产共享组完成 1.13.0 真实签名发布：计划绑定 `delta-shared-prod` 和 `742`、`jax`、`747` 三租户集合，执行前逐租户备份，五容器健康检查通过。第一次因后端 JAR 挂载路径错误自动回退，修复后的第二个计划成功。2026-11-12 又通过计划 `286f1484-fad3-40c0-9793-ac4c5eaea42f` 完成 1.14.0 安装，审计结果为 `SUCCEEDED`；后续必须继续使用服务组计划，不能改用实例更新页。
