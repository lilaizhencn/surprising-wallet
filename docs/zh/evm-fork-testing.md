# EVM Fork 测试

[English version](../en/evm-fork-testing.md)

`evm-fork/` 目录包含 ETH、BNB、Polygon、Arbitrum、Optimism、Base 和 Avalanche C-Chain 回归测试使用的 Hardhat fork 环境。

该目录保留在仓库根目录，因为 `scripts/regtest/all-chain-regtest.sh` 和 `evm-fork/scripts/run-fork-regression.sh` 按固定路径解析它。

## 依赖

- Node.js 18+
- npm
- Maven/JDK 17+
- 已使用 `docs/db/` 初始化的 PostgreSQL 测试库
- 可选：私有或更稳定 RPC 的环境变量

## 安装

```bash
cd evm-fork
npm install
cd ..
```

## 运行全部 EVM Fork 回归

```bash
scripts/regtest/all-chain-regtest.sh test-evm
```

脚本会在 `127.0.0.1:8545` 启动 Hardhat fork，部署 mock ERC20 合约，运行 Java 集成测试，停止 fork，然后进入下一条链。

同时运行多用户业务流测试：

```bash
RUN_MULTIUSER=true scripts/regtest/all-chain-regtest.sh test-evm
```

## 只跑部分链

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

支持的过滤名称：

```text
ETH
BNB
POLYGON
ARBITRUM
OPTIMISM
BASE
AVAX_C
```

## RPC 环境变量

```bash
export ETH_RPC_URL='https://...'
export BNB_RPC_URL='https://...'
export POLYGON_RPC_URL='https://...'
```

部分公共 endpoint 有限流。私有 RPC 会让 fork 测试更稳定。

## 输出

| 路径 | 用途 |
|---|---|
| `evm-fork/logs/*.hardhat.log` | Hardhat fork 日志 |
| `evm-fork/deployments/*.json` | Mock token 部署元数据 |
| `evm-fork/contracts/MockERC20.sol` | 测试 ERC20 合约 |
| `evm-fork/scripts/deploy-mock-erc20.js` | 部署脚本 |

## 常见失败

RPC chain mismatch：

- 配置的 RPC endpoint 不是预期网络。
- 更换 endpoint 或设置正确环境变量。

Fork 启动超时：

- 公共 endpoint 慢或限流。
- 增加 `FORK_START_TIMEOUT_SEC` 或使用私有 RPC。

Mock 部署失败：

- Hardhat fork 没有正确启动。
- 查看 `evm-fork/logs/<CHAIN>.hardhat.log`。

