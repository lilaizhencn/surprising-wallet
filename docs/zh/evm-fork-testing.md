# EVM Fork 测试

[English version](../en/evm-fork-testing.md)

`evm-fork/` 目录包含 ETH、BNB、Polygon、Arbitrum、Optimism、Base 和 Avalanche C-Chain 回归测试使用的 Hardhat fork 环境。

该目录保留在仓库根目录，因为 `scripts/regtest/all-chain-regtest.sh` 和 `evm-fork/scripts/run-fork-regression.sh` 按固定路径解析它。

## 依赖

- Node.js 18+
- npm
- Maven/JDK 21
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
export BNB_FORK_BLOCK='12345678'
export POLYGON_FORK_BLOCK='12345678'
```

部分公共 endpoint 有限流。私有 RPC 会让 fork 测试更稳定。

RPC 策略：

- 脚本只保留已验证可稳定完成 fork 回归的公共默认 endpoint。
- BNB 与 Polygon 不再内置公共默认 fork RPC；需要通过 `BNB_RPC_URL`、`POLYGON_RPC_URL` 注入经过验证的私有或 archive-capable RPC。
- 未显式设置 `BNB_FORK_BLOCK`、`POLYGON_FORK_BLOCK` 时，脚本会读取当前 RPC 最新区块并以该高度启动 fork，避免旧固定区块触发 historical state/missing trie node 问题。
- 需要全链必须通过时，设置 `REQUIRE_ALL_EVM_FORKS=true`。否则无稳定 RPC 的链会标记 `BLOCKED_CHAINS`，脚本继续跑其余链。

2026-06-25 测试结论：

- ETH、ARBITRUM、OPTIMISM、BASE、AVAX_C 使用当前脚本默认 endpoint 完成 fork 回归。
- BNB 公共 RPC 在 fork 或 Java 回归阶段出现 historical state、missing trie node 或 upstream 403，不作为默认 fork RPC。
- Polygon Amoy 公共 RPC 可完成部分 full-chain fork，但 multi-user 流程出现 timeout；`polygon-amoy.drpc.org` 出现节点任务异常，不作为默认 fork RPC。
- 官方 RPC 入口可作为网络参考，最终是否可用于 Hardhat fork 以本脚本回归结果为准：BNB Chain JSON-RPC 文档 `https://docs.bnbchain.org/bnb-smart-chain/developers/json_rpc/json-rpc-endpoint/`，Polygon RPC 文档 `https://docs.polygon.technology/pos/reference/rpc-endpoints/`。

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
