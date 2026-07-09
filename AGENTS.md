# AGENTS.md

Surprising Wallet 涉及链上资产和钱包账户，所有改动必须保守、可审计、可回滚。

## 项目边界

- 这是钱包和链相关项目，包含 Java 后端模块、currency SDK、EVM fork / Hardhat 工程。
- 不要把 exchange 后端测试默认依赖 wallet，除非任务明确涉及充值、提现、链上到账或钱包账务。
- 不提交私钥、助记词、真实 token、RPC 密钥、生产配置。

## 资金和链上安全

- 充值、提现、归集、手续费、链上确认、重放、幂等、回调都要有明确审计路径。
- 任何余额变化都要能通过流水对账。
- 链上交易要区分 pending、confirmed、failed、reorg 相关状态。
- 不要用浮点数处理金额，优先使用整数最小单位或 BigDecimal。

## 验证

- Java 模块优先使用 `mvn -pl <module> -am test` 或 `mvn test`。
- `evm-fork` 相关改动优先检查 Hardhat 脚本和本地链。
- 生产相关配置变更必须说明风险和回滚方式。

## 提交

- 通过验证后 commit and push。
- 不提交 `.idea/`、`logs/`、本地 key 文件、链数据目录、构建产物。

