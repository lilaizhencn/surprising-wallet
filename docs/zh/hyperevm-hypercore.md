# HyperEVM 与 HyperCore 接入说明

[English version](../en/hyperevm-hypercore.md)

## 当前范围

`HYPEREVM` 走项目现有的 EVM 通用适配器：

- 原生币：`HYPE`
- 链 ID：testnet 为 `998`，mainnet 为 `999`
- 地址派生：复用 EVM 的 secp256k1/BIP44 路径
- 充值扫描：原生转账和 ERC20 `Transfer` 事件
- 提现与归集：复用 EVM 签名、广播、确认和账本流程

HyperEVM testnet 的 Circle Native USDC 按 ERC20 配置：

```text
chain: HYPEREVM
symbol: USDC
standard: ERC20
network: testnet
contract: 0x2B3370eE501B4a559b57D449569354196457D8Ab
decimals: 6
```

HyperEVM mainnet 的 USDC 合约不同：

```text
0xb88339CB7199b77E23DB6E890353E22632Ba630f
```

因为当前 `token_config` 按 `chain + symbol` 唯一，每套部署数据库只保存本环境正在使用的合约地址。
`dev`/`test2` 数据库保留 testnet 合约；启用 prod 前，只在 prod 数据库中替换为 mainnet 合约。

## HyperCore 范围

`HYPERCORE` 已按单独的 `hypercore` 链族接入。它不是 EVM JSON-RPC 链，不能当作 `HYPEREVM` 下的另一条 ERC20 链来配置。

当前钱包范围：

- 地址模型：secp256k1/BIP44 账户地址，和 EVM 兼容链使用同一类 owner account 派生方式。
- 充值扫描：通过官方 Hyperliquid `/info` API 的 `spotClearinghouseState` 读取账户余额快照，扫描器把正向余额增量记为钱包充值入账。
- 元数据同步：通过 `/info` 的 `spotMeta` 同步到 `hypercore_token_metadata` 和 `hypercore_spot_asset`。
- 提现与归集：通过 `/exchange` 发送已签名的 Hyperliquid user action。Core USDC 使用 `usdSend`，HYPE 等 HIP-1 token 使用 `spotSend`。
- RPC 配置：启用的 `HYPERCORE` profile 必须同时配置 `info` 和 `exchange` 两类 RPC 节点。测试环境使用 `https://api.hyperliquid-testnet.xyz`。

交易和下单能力刻意不放进钱包路径。当前接入只覆盖托管钱包需要的地址展示、余额观测、提现和归集。
如果后续要做 HyperEVM <-> HyperCore 转移，应作为明确的桥接/内部转移功能处理，不应当伪装成 ERC20 充值事件。
