# 文档

此目录存放项目文档、数据库 schema 文件和文档图片。

## 导航

| 主题 | 文档 |
|---|---|
| 多租户托管钱包 | [zh/multi-tenant-custody.md](zh/multi-tenant-custody.md) |
| EIP-7702 免 Gas 批量归集 | [zh/eip7702-collection.md](zh/eip7702-collection.md) |
| EIP-7702 原生归集与批量提现 | [zh/eip7702-native-collection-and-batch-withdrawal.md](zh/eip7702-native-collection-and-batch-withdrawal.md) |
| EIP-7702 生产 Runbook（ETH/Arbitrum/Base/BNB/Optimism/Polygon） | [zh/](zh/) 下各 `eip7702-*-production-runbook.md` |
| Custody API | [openapi/custody-v1.yaml](openapi/custody-v1.yaml) |
| 启动与测试 | [zh/startup-and-testing.md](zh/startup-and-testing.md) |
| 数据库 | [zh/database.md](zh/database.md) |
| 架构 | [zh/architecture.md](zh/architecture.md) |
| 运行代码流程 | [zh/system-code-flow.md](zh/system-code-flow.md) |
| 脚本与 regtest | [zh/scripts-and-regtest.md](zh/scripts-and-regtest.md) |
| EVM fork 测试 | [zh/evm-fork-testing.md](zh/evm-fork-testing.md) |
| 基础设施 | [zh/infra.md](zh/infra.md) |
| 沙盒钱包环境 | [zh/sandbox-wallet-environment.md](zh/sandbox-wallet-environment.md) |
| HyperEVM / HyperCore | [zh/hyperevm-hypercore.md](zh/hyperevm-hypercore.md) |

## 目录结构

```text
resources/docs/
  assets/       架构图和文档图片
  db/           SQL schema/init 文件
  openapi/      Custody API 机器可读契约
  zh/           中文文档
```

项目模块位于仓库根目录，`resources/` 下存放文档、基础设施配置和合约模板。
