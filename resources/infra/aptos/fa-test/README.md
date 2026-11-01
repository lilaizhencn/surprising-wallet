# Aptos MPC 验收测试资产

此 Move 包只为独立本地 Aptos 链提供 USDC、USDT 两种 6 位 FA 测试资产。AptosFramework 固定为 `eea778c2276e302119d6e469ec3dd0b78fcce039`，本次以官方 Aptos CLI 9.4.0 编译、发布并运行。Mint/部署账号只属于测试夹具；钱包付款公钥和签名来自实际 cb-mpc 三节点。

复用独立管理的 loopback Aptos localnet，REST 默认 `http://127.0.0.1:8080/v1`、faucet 默认 `http://127.0.0.1:18081`，chain ID 必须为 4。启动节点可使用 `aptos node run-localnet --test-dir /tmp/surprising-wallet-aptos-localnet --no-txn-stream --faucet-port 18081 --bind-to 127.0.0.1 --assume-yes`。不要开启 indexer；钱包数据库只复用已运行的本机 PostgreSQL 18。

在仓库外创建权限 0700、名称以 `/tmp/surprising-wallet-mpc-aptos-admin` 开头的测试目录，使用 `aptos key generate --output-file fixture.key` 创建测试注资者，然后以 `aptos init --network custom --rest-url http://127.0.0.1:8080 --faucet-url http://127.0.0.1:18081 --private-key-file fixture.key --profile mpc-fixture --assume-yes` 建立本地夹具 profile。设置 `umask 077`，不要提交生成的配置和 key 文件。

用该 profile 发布此包：`aptos move publish --package-dir <此包绝对路径> --output-dir <仓库外构建目录> --named-addresses test_fa=<测试注资者公开地址> --profile mpc-fixture --skip-fetch-latest-git-deps --assume-yes`。通过 `aptos move view --function-id <测试注资者公开地址>::test_assets::metadata_addresses --profile mpc-fixture` 取得 USDC、USDT metadata。

仅公开信息写入仓库外 JSON：

```json
{
  "endpoint": "http://127.0.0.1:8080/v1",
  "publisher": "<测试注资者公开地址>",
  "adminDirectory": "/tmp/surprising-wallet-mpc-aptos-admin.<隔离后缀>",
  "USDC": "<USDC metadata 公开地址>",
  "USDT": "<USDT metadata 公开地址>"
}
```

设置 `MPC_APTOS_TEST_FA_FIXTURE` 为该公开 JSON 的绝对路径，运行 `bash resources/infra/aptos/run-fa-flow.sh`。探针使用夹具 profile 向新创建的 MPC 源地址铸造模拟资产；主路径的 DKG、付款签名、持久意图、广播和账务使用实际实现。脚本在现有本机 PostgreSQL 18 内创建并删除隔离测试库；不会启动额外数据库。节点、夹具账号及其密钥文件由创建者在验收完成后清理。

三组真实 MPC 的 APT/USDT/USDC 正式提现、丢失响应后恢复、归集、真实 Move 失败退款和实际净费核对均已通过。底层编码或 HTTP 连通性测试仍不能单独代替这些业务验收。
