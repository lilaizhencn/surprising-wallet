# Polkadot public runtime gateway

该服务只封装公开 Substrate RPC、元数据、费用估计、最终事件及已签原文广播。钱包的 Ed25519 联合签名由独立 cb-mpc 节点完成；服务不加载私钥、Seed、助记词或 Keyring，不提供转账签名、开发注资、Token 发行接口。

要求 Node.js 22 及以上受维护版本；容器固定 Node.js 24.22.2 LTS 镜像摘要，并使用锁文件安装。运行 `npm ci --omit=dev --ignore-scripts && npm start`，配置 `POLKADOT_RUNTIME_HOST`、`POLKADOT_RUNTIME_PORT` 和 `POLKADOT_RUNTIME_API_KEY`。默认只监听 `127.0.0.1:8787`；每个请求使用 `Authorization: Bearer <configured-key>`，`/health` 可匿名读取。Docker Compose 要求显式提供 API key，默认端口也只映射到本机。

公开 POST 接口统一接收已配置的 `rpcUrl`（ws/wss）并返回 `{ok,result}`：

| 接口 | 输入与用途 |
| --- | --- |
| `/v1/polkadot/latest-finalized` | 最终高度、hash、完整 genesis |
| `/v1/polkadot/native-balance` | `address` 的 free/reserved/frozen 原子余额 |
| `/v1/polkadot/asset-info` | `assetId` 的元数据及 sufficient 属性 |
| `/v1/polkadot/asset-balance` | `assetId,address` 的原子余额 |
| `/v1/polkadot/network-info` | `address,assetId?` 的 nonce、参考区块、当前运行时版本、交易扩展、付款方法索引及原生精度/存续储备 |
| `/v1/polkadot/payment-info` | `address,to,amountAtomic,assetId?`；以公开 dummy witness 估计费用，无签名密钥 |
| `/v1/polkadot/broadcast-signed` | `extrinsicHex`；只广播调用方已持久化的选定原文 |
| `/v1/polkadot/extrinsic-proof` | `txHash,fromBlock,validUntilHeight`；只在原 mortal era 内查找最终原文/header/事件，同时检查 pending 池 |
| `/v1/polkadot/scan-transfers` | 最多 500 个已最终区块、受跟踪地址及资产 ID；读取成功外部转账事件 |

DOT 托管业务统一连接 Asset Hub；中继链只用于显式公共协议组件测试。请求的业务 Token 资产 ID 与手续费资产选择不同；钱包编码器固定用原生币支付费用。金额始终是原子单位十进制字符串，不能写死网络的小数位数。

最终证明读取原始 extrinsic、header 及 SCALE 事件存储。运行时升级所在区块按父区块执行时的 registry 解码事件，避免用当前版本解释历史事件。缺失历史状态时返回错误，由钱包继续核对。扫描不把 `balances.Deposit` 或 `Endowed` 当作外部充值，因为这些事件也可能属于费用返还；充值由真实 `Transfer` / `Transferred` 和成功阶段驱动。

Java 负责租户归属、固定签名意图、nonce/业务事务、广播前原文持久化、原文恢复和最终本金/费用结算。此网关不访问钱包 PostgreSQL，也不引入数据库。集成测试复用已安装的本机 PostgreSQL 18；链节点的 RocksDB 仅保存隔离区块链账本。

当前分支 Java DOT 已切换到公共 RPC、PolkadotSubmissionService 与独立 MPC；本轮新业务链路尚未完成真实验收。不可用独立编码向量或组件验收代替完整钱包业务验收。

链节点需提供验收窗口内的原始状态；长期恢复应使用 archive 节点，首次最终证明必须尽早持久化。已裁剪的状态不会被缓存元数据伪造；错误保持待核对。隔离测试节点从新 archive 数据卷启动，旧测试账本保留在原卷中。

`run-polkadot-flow.sh` 复用既有隔离节点和网关，通过 `MPC_DOT_TEST_FIXTURE` 指向仓库外受保护描述符。描述符包括 `RELAY` / `ASSET_HUB` 的 loopback 管理员测试端点及固定 `genesisHash`、外部 `adminKeyFile` 路径、官方 SDK `nodeModules` 路径、测试 `USDT` / `USDC` 资产 ID，以及公开网关 `runtimeUrl` / `runtimeApiKey` / `publicRpcUrls`（网关容器内可达地址）。仅独立测试 CLI 读取管理员密钥；Java 只使用公开端点和持久化 MPC 联合密钥。统一入口必须同时执行实际 SCALE 组件和租户业务验收，不能运行原私钥签名夹具替代。

主币付款使用 balances.transferKeepAlive，Token 使用 assets.transfer 允许清空资产账户。Java 仍要求主币 ED 和费用储备，最终回执拒绝付款人的 system.KilledAccount；清空 Token 不得重置原生 nonce。费用估计使用相同的调用方式。

主网 Metadata V16 可能同时包含多个交易扩展版本。`signing-domain.mjs` 明确选择支持 V4 的 pipeline 0，并核对实际 SCALE/implicit 类型。公开主网 2.5.2 元数据测试包含非空 V1 签名/PGAS 字段，验证它们不能混入 V4；不是把未知扩展视为空。`npm test` 使用已保存的公开主网元数据和原始官方运行时来源，不执行主网付款。托管 RPC 的 purpose 为 Asset Hub `rpc` 和网关 `runtime`，Relay 的 `relay_rpc` 只由隔离协议测试显式配置。
