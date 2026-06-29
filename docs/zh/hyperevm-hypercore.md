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

因为当前 `token_config` 按 `chain + symbol` 唯一，每套部署数据库只能保存本环境正在使用的合约地址。
`dev`/`test2` 数据库保留 testnet 合约；未来启用 prod 前，只在 prod 数据库中替换为 mainnet 合约。

## HyperCore 边界

HyperCore 不应该按普通 EVM token 链建模。它是 Hyperliquid 的核心账户/订单簿层，HyperEVM 才是 EVM 执行层。
所以 HyperCore 不能只靠新增一条 `token_config` 配置完成，需要单独的适配器。

推荐分阶段做：

1. 新增 `hypercore` 链族和适配器，通过官方 API 读取 HyperCore 账户状态。
2. 把 HyperEVM <-> HyperCore 转移建成明确的桥接/内部转移记录，不按 ERC20 充值事件处理。
3. 等托管、提现风控规则确定后，再接入 Hyperliquid API action 的签名能力。
4. 除非产品明确需要交易功能，否则钱包路径里先不做下单/交易能力。

这样可以保持现有钱包流程稳定：HyperEVM 负责 HYPE 和 ERC20 资产，HyperCore 后续作为独立账户层能力接入。
