# 文档

[English version](README.md)

此目录存放项目文档、数据库 schema 文件和文档图片。

## 导航

| 主题 | 英文 | 中文 |
|---|---|---|
| 多租户托管钱包 | [en/multi-tenant-custody.md](en/multi-tenant-custody.md) | [zh/multi-tenant-custody.md](zh/multi-tenant-custody.md) |
| EIP-7702 免 Gas 批量归集（目标设计） | - | [zh/eip7702-collection.md](zh/eip7702-collection.md) |
| Custody API | [openapi/custody-v1.yaml](openapi/custody-v1.yaml) | [openapi/custody-v1.yaml](openapi/custody-v1.yaml) |
| 启动与测试 | [en/startup-and-testing.md](en/startup-and-testing.md) | [zh/startup-and-testing.md](zh/startup-and-testing.md) |
| 数据库 | [en/database.md](en/database.md) | [zh/database.md](zh/database.md) |
| 架构 | [en/architecture.md](en/architecture.md) | [zh/architecture.md](zh/architecture.md) |
| 运行代码流程 | [en/system-code-flow.md](en/system-code-flow.md) | [zh/system-code-flow.md](zh/system-code-flow.md) |
| 脚本与 regtest | [en/scripts-and-regtest.md](en/scripts-and-regtest.md) | [zh/scripts-and-regtest.md](zh/scripts-and-regtest.md) |
| EVM fork 测试 | [en/evm-fork-testing.md](en/evm-fork-testing.md) | [zh/evm-fork-testing.md](zh/evm-fork-testing.md) |
| 基础设施 | [en/infra.md](en/infra.md) | [zh/infra.md](zh/infra.md) |

## 目录结构

```text
docs/
  assets/       架构图和文档图片
  db/           SQL schema/init 文件和历史 DB 备份
  en/           英文文档
  openapi/      Custody API 机器可读契约
  zh/           中文文档
```

运行目录仍保留在仓库根目录：

- `scripts/` 用于 regtest 和测试辅助脚本
- `infra/` 用于 Docker 和 mock coin 基础设施
- `evm-fork/` 用于 Hardhat fork 运行环境
