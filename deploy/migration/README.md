# 共享租户迁移校验工具

这两个工具只读源数据或只连接指定租户数据库执行 SELECT，不执行迁移、删除或业务写入。报告文件默认写入调用方指定路径，建议放在 root-only 目录，不通过聊天或日志传递。

## 文件增量

`deploy/migration/file_delta.py manifest --root <dir> --output <json>` 生成路径、大小和 SHA-256 清单。清单只含文件元数据，不含文件内容。

`compare` 比较源和目标，报告 `missing`、`changed`、`extra`、字节数；`verify` 在存在差异时返回非零；`sync` 默认只输出计划，必须显式加 `--apply` 才复制。复制只新增或替换有差异的文件，不删除目标额外文件；源文件变化会导致校验失败并保留原目标文件。`--exclude .thumbnails` 可跳过可再生成的缩略图。

示例：

```bash
python deploy/migration/file_delta.py compare --source /old/uploads --target /new/uploads --report /root/migration/files.json
python deploy/migration/file_delta.py sync --source /old/uploads --target /new/uploads --exclude .thumbnails --apply
python deploy/migration/file_delta.py verify --source /old/uploads --target /new/uploads --exclude .thumbnails
```

工具拒绝符号链接和越界路径；目标额外文件不会自动删除，迁移后应由运维确认后再清理。

## 财务对账

`deploy/migration/financial_reconciliation.py audit --mysql-container <container> --database <tenant_database> --report <json>` 通过 `docker exec` 在指定 MySQL 容器内执行只读检查。数据库名只允许字母、数字和下划线。报告只包含检查名、违规数量和状态，不包含用户、订单、支付单号或手机号。

检查覆盖：钱包和打手钱包负余额、交易 `balance_after = balance_before + 方向金额`、按用户/身份的交易连续性、钱包余额与最后一笔交易一致性、订单支付金额、退款申请金额、提现冻结金额、待结算收入和退款不超过支付金额。`ADMIN_DEDUCT` 的金额字段为正数但方向为扣减，其他交易按金额正负方向计算。

`--fail-on-violation` 可在发现违规时返回非零，适合变更单门禁。对账通过不代表资金流水与第三方支付平台账单完全一致；真实切换前仍需逐笔核对微信账单、退款、提现和结算。

## 暂存验证记录

2026-11-10 在 742 暂存 742 clone 上执行：12 项财务检查全部为 0 违规；文件比较在排除 `.thumbnails` 和演练用的 `742/` 目录后为 510 个文件完全一致。该 clone 中包含真实业务数据，报告和暂存目录必须保持 root-only，不能作为客户数据交付物。

## 租户迁移状态机

`deploy/migration/tenant_state.py` 按租户记录迁移阶段和证据。阶段顺序为 `planned`、`backup_verified`、`target_ready`、`maintenance_started`、`data_imported`、`files_synced`、`finance_verified`、`accepted`、`route_switched`、`observed`、`old_stopped`；除正常前进外，从 `target_ready` 起可进入 `rolled_back`，任何阶段都可进入 `failed`。不能跳阶段，终态不能再前进。

初始化示例：

```bash
python deploy/migration/tenant_state.py init --state-dir /var/lib/delta-migration --tenant 742 --change-ticket CHG-2026-001
python deploy/migration/tenant_state.py transition --state-dir /var/lib/delta-migration --tenant 742 --to backup_verified --evidence backup.json
python deploy/migration/tenant_state.py show --state-dir /var/lib/delta-migration --tenant 742
```

证据只接受各阶段固定的布尔值、SHA-256、数据库标识、时间窗和审计文本，不接受密码、Token、私钥或任意命令。备份摘要、导入报告、文件差异报告、财务报告和客户验收证据必须由对应工具产生；维护窗口未开启、观察期未结束、路由代次未递增或证据缺失都会被拒绝。状态文件和审计默认 `600`，租户目录 `700`。该状态机只记录和校验流程，不执行备份、数据导入、DNS 或服务切换。

## 迁移就绪检查

`deploy/migration/cutover_readiness.py audit` 汇总发布 manifest/候选 ZIP、租户状态、注册文件、文件差异报告、财务报告、暂存验收、维护窗口和客户确认，输出 JSON go/no-go 报告。它校验候选 ZIP 的大小和 SHA-256、状态必须处于 `accepted`、租户在注册文件中启用、文件报告 identical、财务 0 违规、暂存报告通过、窗口当前开启且客户已确认。缺少任何一项都会列入 `blocking`；报告只含检查名、版本、数量和状态，不写凭据或业务标识。

示例：

```bash
python deploy/migration/cutover_readiness.py audit \
  --tenant 742 --release-manifest release-manifest.json --artifacts-dir release-assets \
  --state-dir /var/lib/delta-migration --registry tenant-registry.json \
  --file-report files.json --finance-report finance.json --staging-report staging.json \
  --maintenance-start 2026-11-11T01:00:00+08:00 --maintenance-end 2026-11-11T03:00:00+08:00 \
  --customer-accepted --report readiness.json --fail-if-not-ready
```

该工具不会修改状态、DNS、数据库或容器；它只是把切换前证据汇总为可复核的门禁。
