# 基础设施


`../../../infra` 目录包含运行和测试支撑文件。该目录保留在仓库根目录，因为 regtest 脚本和外部工具按固定路径引用它。

## 结构

```text
infra/
  regtest/
    bitcoin/
    litecoin/
    dogecoin/
    bitcoincash/
```

## UTXO Regtest 镜像

`infra/regtest/*` 包含本地 BTC/LTC/DOGE/BCH regtest 节点的 Dockerfile 和 entrypoint。

这些文件由以下命令使用：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

不要把这些文件移动到 `..`；regtest 脚本会从仓库根目录解析它们。

## 操作规则

`../../../infra` 不是文档内容，而是运行/测试基础设施。它的说明放在 `..`，可执行基础设施文件保留在根目录。
