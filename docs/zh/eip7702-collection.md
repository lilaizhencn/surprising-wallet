# EIP-7702 免 Gas 批量归集实施方案

> 状态：ETH 第一阶段已实现；通用 worker 已扩展到按 `chain + network` 隔离运行。BNB 阶段已通过 chainId 97 的
> Hardhat Prague 真实 type-4、PostgreSQL 两租户五地址生产路径，以及 BSC Testnet 官方 RPC 的
> `eth_estimateGas + authorizationList` 能力门禁。Polygon 阶段已通过 chainId 80002 的同组本地门禁和项目 PublicNode RPC；
> 官方列表中的 dRPC 会忽略 authorization intrinsic gas，已明确排除。Base 阶段已通过 chainId 84532 的同组本地门禁、
> 官方与项目 RPC 严格 estimate 门禁，并把 OP Stack 的执行费、L1 数据费和 operator fee 纳入预留、结算与独立审计字段。
> Arbitrum 阶段已通过 chainId 421614 的本地门禁和官方/项目 RPC，并按 Nitro `gasUsedForL1` 拆分已烘焙进 gasUsed 的父链费。
> 四条扩展链均尚未完成有资金的公开测试网 E2E、独立审计和灰度，因此只能配置 `SHADOW`，主网必须保持禁用。实际操作分别以
> [ETH 上线手册](./eip7702-eth-production-runbook.md) 和
> [BNB 上线手册](./eip7702-bnb-production-runbook.md)、[Polygon 上线手册](./eip7702-polygon-production-runbook.md)、
> [Base 上线手册](./eip7702-base-production-runbook.md)、[Arbitrum 上线手册](./eip7702-arbitrum-production-runbook.md) 为准。

## 1. EIP-7702 解决什么问题

传统 EOA 只能由自己的私钥发起一笔调用，并且必须持有原生币支付 Gas。对托管钱包会产生两个直接问题：

- ERC20 充值地址只有 USDT、USDC，没有 ETH，平台必须先给每个地址打一笔 ETH 才能归集；
- 一个 EOA 一笔交易只能执行一个顶层调用，批量、代付、权限约束都需要额外账户体系。

[EIP-7702](https://eips.ethereum.org/EIPS/eip-7702) 增加 `0x04` Set Code 交易。EOA 可以签署
authorization，把自身执行逻辑持久委托给一个已经部署的合约，同时保留原地址和原私钥。外层交易发送者可以是另一个
账户，因此协议可以承载：

- 批量调用：一个外层交易执行多个操作；
- Gas 代付：Relayer 支付原生币，业务账户不需要持有 Gas；
- 权限降级：delegate 只开放受限操作，而不是暴露完整私钥能力；
- 后续智能账户能力：限额、Session Key、恢复、自动化和 ERC-4337 集成。

EIP-7702 本身只提供“EOA 指向代码”和新的交易信封。批量逻辑、操作签名、Relayer、Gas 结算、失败恢复仍然必须由本项目实现。

## 2. 使用后的直接优势

| 能力 | 当前模式 | 7702 目标模式 |
|---|---|---|
| ERC20 归集前置 Gas | 每个充值地址先收一笔 ETH | 充值地址不需要 ETH，Relayer 统一支付 |
| 多地址归集 | 一地址一笔 txHash | 一批地址共用一个外层 txHash |
| Gas 尾款 | 地址容易残留原生币 | 原生币集中在 Relayer |
| Gas 运维 | 大量地址补充、告警和对账 | 少量链级 Relayer 余额管理 |
| 失败定位 | 每笔交易独立 | 批次 + itemIndex + logIndex 逐项定位 |
| 后续扩展 | EOA 只能直接转账 | 可扩展受限批量、代付、Session Key 等能力 |

批处理不会降低 Gas Price，只会减少重复的 21,000 基础交易成本。首次 delegation 每个空 EOA 还有一次性
authorization 成本；地址已经委托后的后续批次节省更明显。最终收益必须通过目标网络 fork 实测，不能使用固定百分比承诺。

## 3. 本项目实现状态

ETH/BNB/Polygon/Base/Arbitrum 共用的第一阶段当前已经具备：

- `surprising-parent/pom.xml` 使用 Web3j `6.0.0`，依赖中已有 `Transaction7702` 和 `AuthorizationTuple`；
- `evm-fork` 使用 Hardhat `2.26.3`，包含 Prague/EIP-7702 交易字段；
- `AccountSecp256k1KeyService` 能从现有 sig2 根密钥派生 EVM Authority 私钥；
- `ChainJdbcRepository.reserveEvmNonce` 已有数据库 Nonce 预留；
- `CustodyGasService` 已有租户 Gas 预留、释放和结算框架；
- `AccountChainWorkflowService` 已有 EVM 归集候选、广播和确认调度。
- `Eip7702CollectionDelegate` 与 `Eip7702BatchCollector` 已实现、可编译、可部署并有失败隔离测试；
- Web3j 已能生成真实 type-4 authorization list、EIP-712 item 签名和无新 authorization 的 type-2 后续批次；
- 批次严格按 tenant、chain、token、租户热钱包隔离，一个批次只有一个 canonical txHash；
- 签名原文进入加密 outbox，未知广播只恢复同一份原始交易，不重新签名、不消耗新 nonce；
- Gas 预留和结算已扩展为 `COLLECTION_BATCH` 操作，一个租户批次只扣一笔实际链费；
- OP Stack 链按执行费、L1 数据费和 operator fee 的总额预留与结算，并分别保留原子单位审计字段；
- Arbitrum 按 `gasUsedForL1` 拆分 Nitro 已烘焙进 gasUsed 的父链费，审计拆分但总额不重复叠加；
- 回执同时核验 Collector/item/batch 事件、逐项身份、精确 ERC-20 `Transfer`、区块 canonicality 和确认数；
- `PAUSED` 只停止新批次，已广播和广播未知批次仍会继续恢复、确认和结算。

BNB、Polygon、Base、Arbitrum 已分别完成 chainId 97、80002、84532、421614 的本地真实 type-4 和生产路径测试；相应测试网至少一个 RPC
通过严格 authorization estimate 门禁，Base 的官方与项目 RPC 均通过。尚未完成的生产门禁是有资金的公开测试网 E2E、
第三方合约审计、灰度和主网变更审批；
Console 批次详情与运维 API 属于后续可视化工作，不影响当前后台归集闭环。

## 4. 目标架构

```text
确认充值 / 链上余额
        |
        v
Evm7702CollectionCandidateService
        |
        v
按 tenant + chain + token + hotWallet 分批
        |
        +--> Authority 首次使用：生成 EIP-7702 authorization
        +--> 每个 item：生成 EIP-712 CollectionRequest 签名
        |
        v
Evm7702BatchTransactionService
        |
        +--> eth_estimateGas / 模拟
        +--> 租户 Gas 账本预留
        +--> Relayer 签署并广播外层交易
        v
BatchCollector
        |
        +--> 调用 Authority A 的 WalletDelegate.collect
        +--> 调用 Authority B 的 WalletDelegate.collect
        +--> 调用 Authority C 的 WalletDelegate.collect
        v
Token 直接进入现有默认热提钱包
        |
        v
Receipt + CollectionItemResult + 热钱包余额对账
        |
        v
批次 Gas 结算 / item 成败 / 重试 / 审计
```

一个批次只有一个 canonical `txHash`。子项没有独立 txHash，以 `batchId + itemIndex + logIndex` 标识。

## 5. 完整参考合约

在实施步骤中把以下代码保存为：

```text
evm-fork/contracts/Eip7702Collection.sol
```

该实现有意不提供任意 `execute`、`approve` 或 `delegatecall`，只允许签名授权的 ERC20 归集。

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

library Eip7702CollectionTypes {
    struct CollectionRequest {
        bytes32 batchId;
        uint256 itemIndex;
        address authority;
        address collector;
        address token;
        address recipient;
        uint256 amount;
        uint256 operationNonce;
        uint256 deadline;
        uint256 callGasLimit;
    }
}

interface IERC20CollectionToken {
    function balanceOf(address account) external view returns (uint256);
    function transfer(address recipient, uint256 amount) external returns (bool);
}

interface IEip7702CollectionDelegate {
    function collect(
        Eip7702CollectionTypes.CollectionRequest calldata request,
        bytes calldata authoritySignature
    ) external returns (uint256 actualReceived);

    function operationNonce() external view returns (uint256);
}

contract Eip7702CollectionDelegate is IEip7702CollectionDelegate {
    using Eip7702CollectionTypes for Eip7702CollectionTypes.CollectionRequest;

    error UnauthorizedCollector();
    error InvalidRequest();
    error ExpiredRequest();
    error InvalidNonce();
    error InvalidSignature();
    error ReentrantCall();
    error TokenCallFailed();
    error UnexpectedReceivedAmount();

    string public constant NAME = "SurprisingWallet7702Collection";
    string public constant VERSION = "1";

    bytes32 public constant COLLECTION_TYPEHASH = keccak256(
        "CollectionRequest(bytes32 batchId,uint256 itemIndex,address authority,address collector,address token,address recipient,uint256 amount,uint256 operationNonce,uint256 deadline,uint256 callGasLimit)"
    );
    bytes32 private constant EIP712_DOMAIN_TYPEHASH = keccak256(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    );
    bytes32 private constant NAME_HASH = keccak256(bytes(NAME));
    bytes32 private constant VERSION_HASH = keccak256(bytes(VERSION));
    bytes32 private constant OPERATION_NONCE_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.collection.nonce.v1")) - 1);
    bytes32 private constant REENTRANCY_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.collection.reentrancy.v1")) - 1);
    uint256 private constant SECP256K1N_HALF =
        0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0;

    address public immutable EXPECTED_COLLECTOR;

    constructor(address expectedCollector) {
        if (expectedCollector == address(0)) revert InvalidRequest();
        EXPECTED_COLLECTOR = expectedCollector;
    }

    function operationNonce() external view returns (uint256 value) {
        bytes32 slot = OPERATION_NONCE_SLOT;
        assembly {
            value := sload(slot)
        }
    }

    function domainSeparator() public view returns (bytes32) {
        return keccak256(
            abi.encode(
                EIP712_DOMAIN_TYPEHASH,
                NAME_HASH,
                VERSION_HASH,
                block.chainid,
                address(this)
            )
        );
    }

    function collect(
        Eip7702CollectionTypes.CollectionRequest calldata request,
        bytes calldata authoritySignature
    ) external returns (uint256 actualReceived) {
        if (msg.sender != EXPECTED_COLLECTOR) revert UnauthorizedCollector();
        if (
            request.authority != address(this)
                || request.collector != EXPECTED_COLLECTOR
                || request.token == address(0)
                || request.recipient == address(0)
                || request.amount == 0
                || request.callGasLimit == 0
        ) revert InvalidRequest();
        if (block.timestamp > request.deadline) revert ExpiredRequest();

        uint256 currentNonce = _operationNonce();
        if (request.operationNonce != currentNonce) revert InvalidNonce();

        bytes32 structHash = keccak256(
            abi.encode(
                COLLECTION_TYPEHASH,
                request.batchId,
                request.itemIndex,
                request.authority,
                request.collector,
                request.token,
                request.recipient,
                request.amount,
                request.operationNonce,
                request.deadline,
                request.callGasLimit
            )
        );
        bytes32 digest = keccak256(
            abi.encodePacked("\x19\x01", domainSeparator(), structHash)
        );
        if (_recover(digest, authoritySignature) != address(this)) {
            revert InvalidSignature();
        }

        _enter();
        _setOperationNonce(currentNonce + 1);

        uint256 balanceBefore = IERC20CollectionToken(request.token).balanceOf(
            request.recipient
        );
        _safeTransfer(request.token, request.recipient, request.amount);
        uint256 balanceAfter = IERC20CollectionToken(request.token).balanceOf(
            request.recipient
        );
        actualReceived = balanceAfter - balanceBefore;
        if (actualReceived != request.amount) revert UnexpectedReceivedAmount();

        _exit();
    }

    function _operationNonce() private view returns (uint256 value) {
        bytes32 slot = OPERATION_NONCE_SLOT;
        assembly {
            value := sload(slot)
        }
    }

    function _setOperationNonce(uint256 value) private {
        bytes32 slot = OPERATION_NONCE_SLOT;
        assembly {
            sstore(slot, value)
        }
    }

    function _enter() private {
        bytes32 slot = REENTRANCY_SLOT;
        uint256 entered;
        assembly {
            entered := sload(slot)
        }
        if (entered != 0) revert ReentrantCall();
        assembly {
            sstore(slot, 1)
        }
    }

    function _exit() private {
        bytes32 slot = REENTRANCY_SLOT;
        assembly {
            sstore(slot, 0)
        }
    }

    function _safeTransfer(address token, address recipient, uint256 amount) private {
        (bool success, bytes memory result) = token.call(
            abi.encodeWithSelector(
                IERC20CollectionToken.transfer.selector,
                recipient,
                amount
            )
        );
        if (!success || (result.length != 0 && !abi.decode(result, (bool)))) {
            revert TokenCallFailed();
        }
    }

    function _recover(bytes32 digest, bytes calldata signature)
        private
        pure
        returns (address signer)
    {
        if (signature.length != 65) revert InvalidSignature();
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        if (uint256(s) > SECP256K1N_HALF) revert InvalidSignature();
        if (v != 27 && v != 28) revert InvalidSignature();
        signer = ecrecover(digest, v, r, s);
        if (signer == address(0)) revert InvalidSignature();
    }
}

contract Eip7702BatchCollector {
    using Eip7702CollectionTypes for Eip7702CollectionTypes.CollectionRequest;

    error Unauthorized();
    error InvalidConfiguration();
    error InvalidBatch();

    uint256 public constant MAX_ITEMS = 100;
    uint256 public constant MIN_ITEM_GAS = 60_000;
    uint256 public constant MAX_ITEM_GAS = 500_000;

    address public admin;
    address public pendingAdmin;
    mapping(address => bool) public relayers;

    event PendingAdminSet(address indexed pendingAdmin);
    event AdminAccepted(address indexed admin);
    event RelayerSet(address indexed relayer, bool enabled);
    event CollectionItemResult(
        bytes32 indexed batchId,
        uint256 indexed itemIndex,
        address indexed authority,
        address token,
        address recipient,
        uint256 requestedAmount,
        uint256 actualReceived,
        bool success,
        bytes32 errorHash
    );
    event BatchProcessed(
        bytes32 indexed batchId,
        uint256 totalItems,
        uint256 successCount,
        uint256 failureCount
    );

    constructor(address initialAdmin, address initialRelayer) {
        if (initialAdmin == address(0) || initialRelayer == address(0)) {
            revert InvalidConfiguration();
        }
        admin = initialAdmin;
        relayers[initialRelayer] = true;
        emit RelayerSet(initialRelayer, true);
    }

    modifier onlyAdmin() {
        if (msg.sender != admin) revert Unauthorized();
        _;
    }

    modifier onlyRelayer() {
        if (!relayers[msg.sender]) revert Unauthorized();
        _;
    }

    function setPendingAdmin(address nextAdmin) external onlyAdmin {
        if (nextAdmin == address(0)) revert InvalidConfiguration();
        pendingAdmin = nextAdmin;
        emit PendingAdminSet(nextAdmin);
    }

    function acceptAdmin() external {
        if (msg.sender != pendingAdmin) revert Unauthorized();
        admin = pendingAdmin;
        pendingAdmin = address(0);
        emit AdminAccepted(admin);
    }

    function setRelayer(address relayer, bool enabled) external onlyAdmin {
        if (relayer == address(0)) revert InvalidConfiguration();
        relayers[relayer] = enabled;
        emit RelayerSet(relayer, enabled);
    }

    function collectBatch(
        Eip7702CollectionTypes.CollectionRequest[] calldata requests,
        bytes[] calldata signatures
    ) external onlyRelayer {
        uint256 length = requests.length;
        if (length == 0 || length > MAX_ITEMS || signatures.length != length) {
            revert InvalidBatch();
        }

        bytes32 batchId = requests[0].batchId;
        uint256 successCount;
        for (uint256 i = 0; i < length; ++i) {
            Eip7702CollectionTypes.CollectionRequest calldata request = requests[i];
            if (
                request.batchId != batchId
                    || request.itemIndex != i
                    || request.collector != address(this)
                    || request.authority == address(0)
                    || request.callGasLimit < MIN_ITEM_GAS
                    || request.callGasLimit > MAX_ITEM_GAS
            ) revert InvalidBatch();

            (bool success, bytes memory result) = request.authority.call{
                gas: request.callGasLimit
            }(
                abi.encodeCall(
                    IEip7702CollectionDelegate.collect,
                    (request, signatures[i])
                )
            );

            uint256 actualReceived;
            if (success && result.length == 32) {
                actualReceived = abi.decode(result, (uint256));
                success = actualReceived == request.amount;
            } else {
                success = false;
            }
            if (success) ++successCount;

            emit CollectionItemResult(
                batchId,
                i,
                request.authority,
                request.token,
                request.recipient,
                request.amount,
                actualReceived,
                success,
                success ? bytes32(0) : keccak256(result)
            );
        }

        emit BatchProcessed(batchId, length, successCount, length - successCount);
    }
}
```

### 5.1 合约安全说明

- Delegate 的 immutable collector 会写入 runtime code，同一 delegate 可供同一网络上的所有 Authority 使用；
- EIP-712 domain 的 `verifyingContract` 在 delegated execution 上下文中是 Authority EOA，防止跨地址重放；
- 精确检查热钱包余额增量，不支持转账税 Token；
- operation nonce 在每个 Authority 自身 storage 中独立保存；
- BatchCollector 单项失败不会回滚其他 item，但批次结构错误会整体 revert；
- Collector 管理员只管理 Relayer，不能绕过每个 Authority 的签名；
- 合约仍需审计，尤其要验证 delegated execution 的 `address(this)`、storage slot 和重入行为。

## 6. 一步步实施

以下顺序是依赖顺序。不要先改 Console，也不要在账务和回滚尚未完成时广播真实资金交易。

### 第 1 步：增加逐链能力配置

修改 `docs/db/surprising-wallet-init-pgsql.sql`，增加 `evm_7702_config`。目标 DDL：

```sql
CREATE TABLE public.evm_7702_config (
    id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    network varchar(64) NOT NULL,
    version integer NOT NULL,
    delegate_address varchar(42) NOT NULL,
    delegate_code_hash varchar(66) NOT NULL,
    collector_address varchar(42) NOT NULL,
    collector_code_hash varchar(66) NOT NULL,
    relayer_address varchar(42) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DISABLED',
    max_batch_items integer NOT NULL DEFAULT 10,
    max_batch_value_usd numeric(38, 18) NOT NULL DEFAULT 10000,
    max_batch_gas bigint NOT NULL DEFAULT 5000000,
    block_gas_ratio numeric(5, 4) NOT NULL DEFAULT 0.3000,
    signature_ttl_seconds integer NOT NULL DEFAULT 300,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (chain, network, version),
    CHECK (status IN ('DISABLED', 'SHADOW', 'ACTIVE', 'PAUSED')),
    CHECK (max_batch_items BETWEEN 1 AND 100),
    CHECK (max_batch_value_usd > 0),
    CHECK (max_batch_gas > 0),
    CHECK (block_gas_ratio > 0 AND block_gas_ratio <= 0.5),
    CHECK (signature_ttl_seconds BETWEEN 30 AND 1800)
);

CREATE UNIQUE INDEX evm_7702_config_one_enabled_version
    ON public.evm_7702_config(chain, network)
    WHERE status IN ('SHADOW', 'ACTIVE', 'PAUSED');
```

不要给所有 `family=evm` 的链自动插入 ACTIVE 配置。先用 SHADOW 验证当前网络和全部 RPC 节点确实支持 Type-4
查询、估算、广播和 Receipt。

在 `wallet-service` 增加：

```text
com.surprising.wallet.service.chain.evm.Evm7702ConfigRepository
com.surprising.wallet.service.chain.evm.Evm7702RuntimeConfig
```

启动校验应读取启用的 `chain_profile`，逐项验证配置 network、chainId、合约地址和 code hash。任何不一致只允许
SHADOW；ACTIVE 配置应使该链 7702 归集启动失败，避免静默使用错误合约。

### 第 2 步：加入合约文件并显式启用 Prague

1. 创建 `evm-fork/contracts/Eip7702Collection.sol`，内容使用第 5 节完整代码。
2. 修改 `evm-fork/hardhat.config.js`：

```javascript
require("@nomicfoundation/hardhat-ethers");

const chainId = process.env.HARDHAT_CHAIN_ID
  ? Number(process.env.HARDHAT_CHAIN_ID)
  : 31337;
const deploymentRpcUrl = process.env.EVM_DEPLOY_RPC_URL;
const deploymentPrivateKey = process.env.EVM_DEPLOYER_PRIVATE_KEY;

if ((deploymentRpcUrl && !deploymentPrivateKey)
    || (!deploymentRpcUrl && deploymentPrivateKey)) {
  throw new Error("EVM_DEPLOY_RPC_URL and EVM_DEPLOYER_PRIVATE_KEY must be set together");
}

module.exports = {
  solidity: {
    version: "0.8.24",
    settings: {
      optimizer: { enabled: true, runs: 200 }
    }
  },
  networks: {
    hardhat: {
      chainId,
      hardfork: "prague"
    },
    localhost: {
      url: "http://127.0.0.1:8545"
    },
    ...(deploymentRpcUrl ? {
      deployment: {
        url: deploymentRpcUrl,
        accounts: [deploymentPrivateKey]
      }
    } : {})
  }
};
```

3. 编译：

```bash
cd evm-fork
npm ci
npx hardhat compile
```

编译产物中的 ABI、creation bytecode 和 deployed bytecode 应进入现有合约工件管理流程。不要把 Hardhat
`artifacts/`、`cache/` 或本地链数据整体提交到仓库；只提交项目运行时明确需要的规范化 artifact。

### 第 3 步：增加完整部署脚本

创建 `evm-fork/scripts/deploy-eip7702-collection.js`：

```javascript
const fs = require("fs");
const path = require("path");
const hre = require("hardhat");

function requiredAddress(name) {
  const value = (process.env[name] || "").trim();
  if (!hre.ethers.isAddress(value) || value === hre.ethers.ZeroAddress) {
    throw new Error(`${name} must be a non-zero EVM address`);
  }
  return hre.ethers.getAddress(value);
}

async function runtimeCodeHash(address) {
  const code = await hre.ethers.provider.getCode(address);
  if (code === "0x") throw new Error(`missing runtime code at ${address}`);
  return hre.ethers.keccak256(code);
}

async function main() {
  const chain = (process.env.EVM_CHAIN || "ETH").trim().toUpperCase();
  const network = (process.env.EVM_NETWORK || "local").trim().toLowerCase();
  const admin = requiredAddress("EIP7702_ADMIN_ADDRESS");
  const relayer = requiredAddress("EIP7702_RELAYER_ADDRESS");
  const [deployer] = await hre.ethers.getSigners();
  const rpcNetwork = await hre.ethers.provider.getNetwork();

  const Collector = await hre.ethers.getContractFactory("Eip7702BatchCollector");
  const collector = await Collector.deploy(admin, relayer);
  await collector.waitForDeployment();
  const collectorAddress = await collector.getAddress();

  const Delegate = await hre.ethers.getContractFactory("Eip7702CollectionDelegate");
  const delegate = await Delegate.deploy(collectorAddress);
  await delegate.waitForDeployment();
  const delegateAddress = await delegate.getAddress();

  const deployment = {
    chain,
    network,
    chainId: rpcNetwork.chainId.toString(),
    deployer: deployer.address,
    admin,
    relayer,
    collectorAddress,
    collectorCodeHash: await runtimeCodeHash(collectorAddress),
    delegateAddress,
    delegateCodeHash: await runtimeCodeHash(delegateAddress)
  };

  const outDir = path.join(__dirname, "..", "deployments");
  fs.mkdirSync(outDir, { recursive: true });
  const out = path.join(outDir, `${chain}-EIP7702.json`);
  fs.writeFileSync(out, `${JSON.stringify(deployment, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify(deployment, null, 2)}\n`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
```

本地执行：

```bash
cd evm-fork
HARDHAT_CHAIN_ID=31337 npx hardhat node
```

另开终端，使用 Hardhat 本地公开测试账户地址作为 admin 和 relayer：

```bash
cd evm-fork
EVM_CHAIN=ETH \
EVM_NETWORK=local \
EIP7702_ADMIN_ADDRESS=0x本地测试管理员地址 \
EIP7702_RELAYER_ADDRESS=0x本地测试Relayer地址 \
npx hardhat run scripts/deploy-eip7702-collection.js --network localhost
```

生产部署要求：

- deployer 使用硬件钱包或受控部署签名器；
- admin 使用平台多签，不使用 wallet-server 在线密钥；
- Relayer 使用独立的链级在线 Gas 钱包；
- 部署后从至少两个独立 RPC 获取 runtime code 并比对 code hash；
- 在浏览器验证源码，并把地址和 code hash 录入 `evm_7702_config`；
- 初始状态只能是 SHADOW。

测试网/主网部署时由 CI Secret 或一次性受控终端预先注入 `EVM_DEPLOY_RPC_URL` 和
`EVM_DEPLOYER_PRIVATE_KEY`，不要把值写进命令历史、`.env` 或仓库。执行命令本身为：

```bash
cd evm-fork
EVM_CHAIN=ETH \
EVM_NETWORK=sepolia \
EIP7702_ADMIN_ADDRESS=0x多签地址 \
EIP7702_RELAYER_ADDRESS=0x该网络Relayer地址 \
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
```

脚本输出地址后，先核对 `chainId`、两份 runtime code hash 和浏览器源码，再由双人复核 SQL 写入 SHADOW 配置。生产部署推荐
把脚本的签名部分接入硬件钱包/远程签名器；上面的 `EVM_DEPLOYER_PRIVATE_KEY` 方式只用于受控测试网或部署流水线。

### 第 4 步：增加 Authority delegation 投影

增加目标表：

```sql
CREATE TABLE public.evm_7702_account (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    network varchar(64) NOT NULL,
    authority_address varchar(42) NOT NULL,
    delegate_address varchar(42),
    delegate_version integer,
    delegation_status varchar(24) NOT NULL DEFAULT 'NOT_DELEGATED',
    observed_authority_nonce numeric(78, 0),
    observed_operation_nonce numeric(78, 0),
    activation_tx_hash varchar(128),
    revocation_tx_hash varchar(128),
    last_code_hash varchar(66),
    last_observed_block bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, chain, authority_address),
    UNIQUE (tenant_id, custody_address_id, chain),
    FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT,
    CHECK (delegation_status IN (
        'NOT_DELEGATED', 'DELEGATING', 'ACTIVE', 'REVOKING',
        'REVOKED', 'UNKNOWN', 'MANUAL_REVIEW'
    ))
);
```

在 `CustodyAddressService` 成功创建 EVM 地址的同一数据库事务中插入投影，状态为 `NOT_DELEGATED`。创建地址不发链上
交易、不占用 Authority nonce、不向地址发送 ETH。

增加定时核验任务：

```text
Evm7702DelegationReconcileJob
```

它读取 `eth_getCode(authority)`：

- `0x`：NOT_DELEGATED；
- `0xef0100 || approvedDelegate`：ACTIVE；
- 指向零地址或已清除：REVOKED；
- 任意其他代码：MANUAL_REVIEW，并暂停该地址归集。

### 第 5 步：增加批次和子项表

```sql
CREATE TABLE public.evm_collection_batch (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    network varchar(64) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    token_contract varchar(42) NOT NULL,
    token_decimals integer NOT NULL,
    hot_wallet varchar(42) NOT NULL,
    relayer_address varchar(42) NOT NULL,
    delegate_version integer NOT NULL,
    status varchar(32) NOT NULL,
    item_count integer NOT NULL,
    estimated_gas bigint,
    gas_limit bigint,
    max_fee_per_gas numeric(78, 0),
    max_priority_fee_per_gas numeric(78, 0),
    canonical_tx_hash varchar(128),
    actual_gas_used bigint,
    effective_gas_price numeric(78, 0),
    l2_fee_atomic numeric(78, 0),
    l1_fee_atomic numeric(78, 0),
    operator_fee_atomic numeric(78, 0),
    total_fee_atomic numeric(78, 0),
    actual_fee numeric(78, 24),
    confirmed_block_number bigint,
    confirmed_block_hash varchar(128),
    error_code varchar(64),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_at timestamptz,
    confirmed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CHECK (item_count > 0),
    CHECK (status IN (
        'CREATED', 'LOCKED', 'SIMULATED', 'SIGNING', 'SUBMITTED',
        'BROADCAST_UNKNOWN', 'CONFIRMING', 'CONFIRMED',
        'PARTIAL_FAILED', 'FAILED', 'REORGED', 'MANUAL_REVIEW'
    ))
);

CREATE UNIQUE INDEX evm_collection_batch_tx_key
    ON public.evm_collection_batch(chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;

CREATE TABLE public.evm_collection_batch_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_index integer NOT NULL,
    collection_record_id bigint NOT NULL,
    custody_address_id uuid NOT NULL,
    authority_address varchar(42) NOT NULL,
    token_contract varchar(42) NOT NULL,
    recipient varchar(42) NOT NULL,
    requested_amount_atomic numeric(78, 0) NOT NULL,
    actual_received_atomic numeric(78, 0),
    authorization_included boolean NOT NULL DEFAULT false,
    authorization_nonce numeric(78, 0),
    operation_nonce numeric(78, 0) NOT NULL,
    signature_deadline timestamptz NOT NULL,
    call_gas_limit bigint NOT NULL,
    status varchar(32) NOT NULL,
    log_index integer,
    error_code varchar(64),
    error_hash varchar(66),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (batch_id, item_index),
    UNIQUE (collection_record_id),
    UNIQUE (batch_id, authority_address),
    FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_collection_batch(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, collection_record_id)
        REFERENCES public.collection_record(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT,
    CHECK (requested_amount_atomic > 0),
    CHECK (actual_received_atomic IS NULL OR actual_received_atomic >= 0),
    CHECK (status IN (
        'CREATED', 'SIGNED', 'SUBMITTED', 'CONFIRMED', 'FAILED',
        'RETRYABLE', 'REORGED', 'MANUAL_REVIEW'
    ))
);

CREATE TABLE public.evm_collection_batch_attempt (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    relayer_nonce numeric(78, 0) NOT NULL,
    tx_hash varchar(128) NOT NULL,
    max_fee_per_gas numeric(78, 0) NOT NULL,
    max_priority_fee_per_gas numeric(78, 0) NOT NULL,
    gas_limit bigint NOT NULL,
    rpc_node_id bigint,
    calldata_hash varchar(66) NOT NULL,
    signed_tx_ciphertext bytea NOT NULL,
    encryption_key_version varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    error_code varchar(64),
    error_message text,
    replaced_by_tx_hash varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_at timestamptz,
    observed_at timestamptz,
    PRIMARY KEY (id),
    UNIQUE (batch_id, attempt_no),
    UNIQUE (tx_hash),
    FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_collection_batch(tenant_id, id) ON DELETE RESTRICT,
    CHECK (status IN (
        'CREATED', 'SUBMITTED', 'PENDING', 'CONFIRMED',
        'DROPPED', 'REPLACED', 'FAILED', 'UNKNOWN'
    ))
);
```

`evm_collection_batch_item.collection_record_id` 的唯一约束保证一条归集记录只能进入一个 item，不在 `collection_record`
上再增加反向 ID，避免循环外键。

当前 `collection_record` 本身没有 `tenant_id/custody_address_id`，这不满足目标 SaaS 隔离边界。直接修改初始化 DDL 和
`ChainJdbcRepository.createCollectionRecord`，为所有新归集记录写入非空 `tenant_id`、`custody_address_id`，并建立：

```sql
UNIQUE (tenant_id, id),
FOREIGN KEY (tenant_id, custody_address_id)
    REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT
```

批次候选只能通过这两个字段归属租户，禁止根据地址字符串反查后“猜”租户。批次开始处理后只能通过批次状态机更新对应记录，
不再让当前 `processCollection` 对这些记录逐条广播。

`signed_tx_ciphertext` 是崩溃恢复 outbox：外层交易签名后、调用 RPC 前，使用项目密钥管理系统做 envelope encryption，与
本地计算的 `tx_hash` 一起原子落库。RPC 超时后重发完全相同的 raw transaction，避免不确定状态下重新组包。明文 raw tx、
authorization 和 operation signature 不写日志；attempt 终结并超过审计保留期后按策略清除密文。

### 第 6 步：把租户 Gas 账务扩展为“业务操作级”

当前 `custody_gas_usage` 强依赖 `custody_withdrawal_id`，`CustodyRepository.reserveGasUsage`、
`releaseGasUsage`、`settleGasUsage` 也都以提现 ID 为唯一入口。7702 归集的一笔网络费属于整个批次，不能给每个 item
重复扣一遍。项目处于开发阶段，应直接把现有模型重构为目标模型，不增加第二张 Gas 表，也不做双写：

```sql
ALTER TABLE public.custody_gas_usage
    DROP CONSTRAINT IF EXISTS custody_gas_usage_custody_withdrawal_id_fkey;

ALTER TABLE public.custody_gas_usage
    DROP CONSTRAINT IF EXISTS custody_gas_usage_withdrawal_fk,
    DROP CONSTRAINT IF EXISTS custody_gas_usage_withdrawal_key,
    DROP CONSTRAINT IF EXISTS custody_gas_usage_tenant_order_key;

ALTER TABLE public.custody_gas_usage
    RENAME COLUMN custody_withdrawal_id TO operation_id;

ALTER TABLE public.custody_gas_usage
    RENAME COLUMN order_no TO reference_no;

ALTER TABLE public.custody_gas_usage
    ADD COLUMN operation_type varchar(32) NOT NULL DEFAULT 'WITHDRAWAL';

ALTER TABLE public.custody_gas_usage
    DROP CONSTRAINT IF EXISTS custody_gas_usage_operation_check;

ALTER TABLE public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_operation_check
    CHECK (operation_type IN ('WITHDRAWAL', 'COLLECTION_BATCH'));

CREATE UNIQUE INDEX custody_gas_usage_operation_key
    ON public.custody_gas_usage(tenant_id, operation_type, operation_id);
```

实际开发时直接修改 `docs/db/surprising-wallet-init-pgsql.sql` 的建表语句，并重建开发库；上面的 SQL 只用于说明目标变化，
不是要求生产环境边运行边执行。由于 `operation_id` 是多态引用，数据库不能同时对两张业务表建立普通外键；Repository 必须在
同一个事务内锁定并验证对应 `custody_withdrawal` 或 `evm_collection_batch` 的 `tenant_id`，集成测试覆盖伪造跨租户 ID。
Java 侧同步改成：

```java
reserveGasUsage(UUID tenantId, String operationType, UUID operationId,
                String referenceNo, String chain, BigDecimal reservedAmount)
releaseGasUsage(UUID tenantId, String operationType, UUID operationId, String reason)
settleGasUsage(UUID tenantId, String operationType, UUID operationId,
               BigDecimal actualAmount, String pricingSource, String txHash)
```

具体改动文件：

1. `wallet-server/.../jobs/custody/CustodyRepository.java`：所有查询必须同时带 `tenant_id + operation_type + operation_id`，
   `custody_ledger_entry.reference_type` 对归集写 `COLLECTION_BATCH`，`reference_id` 写批次号。
2. `wallet-server/.../jobs/custody/CustodyGasService.java`：保留提现估算入口，新增批次估算入口；不要继续用当前 ERC-20
   固定 `65_000` Gas。批次必须先 `eth_estimateGas`，再乘配置的安全系数。
3. 广播前预占：L1 与 Arbitrum 链为 `gasLimit * maxFeePerGas / 10^nativeDecimals`；Arbitrum estimate 已包含父链数据
   buffer。OP Stack 链还必须在签名后调用 GasPriceOracle，
   加上 `getL1Fee(signedRawTransaction)` 和 `getOperatorFee(gasLimit)`。
4. 最终确认后，L1 链按回执的 `gasUsed * effectiveGasPrice` 结算；OP Stack 链按
   `gasUsed * effectiveGasPrice + l1Fee + operatorFee` 结算。多占退回、少占补扣；余额不足进入 `OVERDUE`，但不能回滚
   已经发生的链上归集。
   Arbitrum 的 receipt gasUsed 已包含父链数据 gas，只按 `gasUsed * effectiveGasPrice` 结算，并用 `gasUsedForL1` 拆分审计。
5. replacement 交易只属于同一个 operation，同 nonce 的多次 attempt 最终只结算 canonical receipt 一次。

推荐初始参数：估算 Gas 安全系数 `1.20`、每批最多 `50` 个地址、合约硬上限 `100`。上线后以实际区块 Gas 上限和
成功率动态调整，不能把固定值写死在 Java 常量中。

### 第 7 步：增加 7702 签名、组包和回执解析服务

在 `wallet-service/src/main/java/com/surprising/wallet/service/chain/evm/` 增加：

```text
Evm7702AuthorizationService.java   # 为尚未委托或版本变化的地址签 authorization
Evm7702OperationSigner.java       # 为每个 CollectionRequest 生成 EIP-712 签名
Evm7702BatchTransactionService.java # 模拟、构造 type-4、签名和广播
Evm7702ReceiptParser.java          # 按事件解析每个 item 的结果
```

在 `wallet-server/src/main/java/com/surprising/wallet/jobs/account/` 增加
`Evm7702CollectionWorkflowService.java`，它负责租户隔离、锁定候选记录、创建批次、预占 Gas、调用上述链服务和推进状态。
私钥仍通过现有 `AccountSecp256k1KeyService` 按地址派生，禁止把私钥或完整签名写日志、数据库或异常信息。

#### 7.1 生成 EOA authorization

项目当前 Web3j 版本为 `6.0.0`，已经包含 `AuthorizationTuple` 和 `Transaction7702`。核心实现如下；生产代码还要把
RPC、配置和异常映射封装到上述服务中：

```java
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;

public AuthorizationTuple authorizeDelegate(
        long chainId,
        String delegateAddress,
        BigInteger authorityNonce,
        Credentials authorityCredentials) {
    if (chainId <= 0) {
        throw new IllegalArgumentException("7702 authorization must bind an exact chainId");
    }
    if (authorityNonce.signum() < 0) {
        throw new IllegalArgumentException("authority nonce must be non-negative");
    }
    return AuthorizationTuple.from(
            chainId,
            delegateAddress,
            authorityNonce,
            authorityCredentials);
}
```

`authorityNonce` 从 `eth_getTransactionCount(authority, "pending")` 获取。组包前必须再次核对，且同一批次不允许同一个
authority 出现两次。authorization 必须使用具体 chainId，不使用规范允许的 `0` 通配值，避免签名被跨链复用。

不是每一批都要附 authorization。先读取 `eth_getCode(authority, "latest")`：

- 空代码：附带授权，并把 `authorization_included=true`。
- 代码严格等于 `0xef0100 || delegateAddress`：已有正确委托，不再附带授权。
- 其他代码或委托到旧版本：停止自动归集，进入 `MANUAL_REVIEW`；经过审批后才能用新 authorization 迁移。

注意：authorization 的委托写入发生在外层调用执行之前；即使后续 `collectBatch` 回滚，成功处理的委托也可能保留。
因此不能把“业务调用失败”当成“没有设置委托”。

#### 7.2 给每个归集请求签 EIP-712

签名域必须是：

```text
name              = SurprisingWallet7702Collection
version           = 1
chainId           = 当前精确 chainId
verifyingContract = authority 地址（不是 delegate 实现地址）
```

签名结构与合约完全一致：

```text
CollectionRequest(
  bytes32 batchId,uint256 itemIndex,address authority,address collector,
  address token,address recipient,uint256 amount,uint256 operationNonce,
  uint256 deadline,uint256 callGasLimit
)
```

Java 中使用 Web3j `StructuredDataEncoder` 生成 EIP-712 digest，再用该充值地址的 `Credentials` 签名。必须对最终待广播的
完整请求签名，不能先签空 recipient/amount 后补字段。`operationNonce` 通过调用 authority 地址上的
`operationNonce()` 读取；同一 authority 只能有一个在途请求。`deadline` 初始建议为当前时间加 15 分钟，过期后重新建批，
不能复用旧签名。输出固定为 65 字节 `r || s || v`，规范化为 low-s 且 `v` 为 27/28，与合约 `_recover` 一致；加入一组
Java digest 与 Solidity `domainSeparator/COLLECTION_TYPEHASH` 完全相同的固定测试向量，防止 JSON 字段顺序或大整数编码偏差。

#### 7.3 构造并签署 type-4 外层交易

下面代码对应 Web3j 6.0.0 的真实 API。`data` 是对 `Eip7702BatchCollector.collectBatch(requests, signatures)` 的 ABI
编码，`relayerCredentials` 是每条链的赞助 Gas 热钱包，不是租户充值地址：

```java
import org.web3j.crypto.AccessListObject;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

public String signType4(
        long chainId,
        BigInteger relayerNonce,
        BigInteger maxPriorityFeePerGas,
        BigInteger maxFeePerGas,
        BigInteger gasLimit,
        String collectorAddress,
        String data,
        List<AuthorizationTuple> authorizations,
        Credentials relayerCredentials) {
    RawTransaction raw = RawTransaction.createTransaction(
            chainId,
            relayerNonce,
            maxPriorityFeePerGas,
            maxFeePerGas,
            gasLimit,
            collectorAddress,
            BigInteger.ZERO,
            data,
            List.<AccessListObject>of(),
            authorizations);

    // 7702 必须使用这个重载。不要调用 signMessage(raw, chainId, credentials)。
    byte[] signed = TransactionEncoder.signMessage(raw, relayerCredentials);
    return Numeric.toHexString(signed);
}
```

当前 `EvmAccountTransactionService.broadcast` 使用的 `signMessage(tx, chainId, credentials)` 只适合现有普通交易路径，不能
复制到 7702 服务。广播用 `eth_sendRawTransaction`；本地计算的 txHash、RPC 返回的 txHash 必须一致，否则进入
`BROADCAST_UNKNOWN` 并按 nonce 查询，绝不能立即换 nonce 重发。

relayer nonce 继续复用 `ChainJdbcRepository.reserveEvmNonce(chain, relayerAddress, pendingNonce)` 的数据库锁定能力，
但需要把内部 `long` 改为 `BigInteger`/`numeric(78,0)`，避免协议值被 Java `long` 人为截断。一个 relayer 地址必须由一个
逻辑 nonce 分配器负责。

#### 7.4 模拟顺序

广播前按以下顺序执行，任何一步失败都不扣实际 Gas：

1. 校验租户、chain、token、recipient、collector、delegate version 完全匹配配置。
2. `eth_call` 读取 token 余额，余额不足的 item 不进批。
3. 读取每个 authority 的 code、transaction nonce、operation nonce，生成 authorization 和 EIP-712 签名。
4. 用与最终交易完全相同的 `from/to/data/authorizationList` 调 `eth_estimateGas`；所选 RPC 必须支持 Prague/type-4。
5. `eth_call` 或 `debug_traceCall`（节点支持时）做最终模拟。
6. 在数据库事务中固化批次、item、calldata 哈希、授权摘要、Gas 参数，预占租户 Gas 余额并预留 relayer nonce。
7. 事务提交后生成外层签名；在调用 RPC **之前**，把本地 txHash 和 envelope-encrypted raw transaction 原子写入 attempt outbox。
8. outbox 提交后调用 `eth_sendRawTransaction`，按本地 txHash 推进状态；任何超时都由 outbox 重发同一字节串。

### 第 8 步：替换当前 EVM 逐笔归集调度

当前 `AccountChainWorkflowService` 每轮最多取 20 条归集记录，随后 `processCollection` 对每条 EVM 记录调用
`sendNative/sendToken`。目标改造如下：

1. 非 EVM 链保持原调度。
2. EVM 原生币先保持当前逐地址转账；7702 第一阶段只接管 ERC-20。
3. 对 `evm_7702_config.status='ACTIVE'` 的网络，ERC-20 记录只由
   `Evm7702CollectionWorkflowService` 认领，不能再进入 `processCollection`。
4. 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 认领记录，并按
   `tenant_id + chain + network + token_contract + hot_wallet + delegate_version` 分组。严禁跨租户拼批。
5. 每组从 20 个开始，稳定后提高到 50；同时受 calldata、估算 Gas、区块 Gas 上限和合约 `MAX_BATCH_SIZE` 限制。
6. 批内 item 可部分失败：成功 item 确认，失败 item 根据错误码进入 `RETRYABLE` 或 `MANUAL_REVIEW`，不能让成功地址重复归集。

建议调度入口保持在 `AccountChainWorkflowService.processCollections()`，但把 EVM token 候选集一次性交给批次服务；不要在
原来的 `processCollection()` 里临时收集全局列表，这会破坏数据库认领边界。

### 第 9 步：回执、TXID、失败、重放和重组处理

#### 9.1 一批有几个 TXID

一次 `collectBatch` 外层交易只有 **一个链上 txHash**。每个归集 item 没有独立 txHash，使用以下组合唯一追踪：

```text
batch_id + item_index + canonical_tx_hash + log_index
```

Console 可以让每条归集记录显示同一个 txHash，并额外显示“批次 8/37”。不要生成假的子 TXID。replacement 可能产生多个
attempt txHash，但最终只能有一个 canonical txHash；所有 attempt 都必须留存审计。

#### 9.2 解析结果

交易 receipt 成功不等于所有 item 成功。`Evm7702ReceiptParser` 必须校验：

- receipt `to` 是配置的 collector，`status=1`，block hash 仍是 canonical。
- `BatchCollected` 的 `batchId/itemCount` 与数据库一致。
- 每个 item 恰好有一个 `CollectionItemResult`，event address 是 collector，`itemIndex/authority/token/recipient` 均匹配。
- `success=true` 时，再核对 ERC-20 `Transfer(authority, recipient, amount)` 日志以及目标余额变化；合约已经拒绝手续费型 token。
- 缺日志、重复日志或字段不符一律进入 `MANUAL_REVIEW`，不能猜测成功。

#### 9.3 状态规则

```text
CREATED -> LOCKED -> SIMULATED -> SIGNING -> SUBMITTED -> CONFIRMING
                                                    |-> BROADCAST_UNKNOWN
CONFIRMING -> CONFIRMED | PARTIAL_FAILED | FAILED | REORGED
REORGED -> RETRYABLE（重新读取 code/nonce/余额后建新批） | MANUAL_REVIEW
```

- RPC 超时发生在广播后：先记录 `BROADCAST_UNKNOWN`，用本地 txHash 和 relayer nonce 在多个节点查询，禁止直接重发新 nonce。
- pending 超时：用同一个 relayer nonce、相同 calldata 和 authorization list 提高费用替换，记录 attempt 链；不要修改 item 请求。
- receipt `status=0`：不能假设 authorization 也失败，逐地址重新读取 code；释放未实际消耗部分后按真实 receipt 结算 Gas。
- 达到 `chain_profile.withdraw_confirmations` 后才确认。每次确认都核对 block hash；发生 reorg 时撤销“物理归集确认”状态并重新扫描，
  但不要冲销用户此前充值产生的负债账，因为归集只是平台内部地址间移动。
- item 失败重试前必须读取 token 余额和 `operationNonce()`；若币已经移动，不得再次发起相同金额。

### 第 10 步：增加 Console 和管理 API

第一阶段不需要用户侧交互，authorization 和 operation signature 都由托管地址密钥在后端生成。Console 面向租户管理员提供：

```text
GET  /api/admin/evm-7702/configs
PUT  /api/admin/evm-7702/configs/{chain}/{network}  # 超级管理员、双人审批
GET  /api/tenant/collection-batches
GET  /api/tenant/collection-batches/{batchId}
POST /api/tenant/collection-batches/{batchId}/retry # 仅重试失败 item，幂等键必填
POST /api/admin/collection-batches/{batchId}/review # 人工处置，审计原因必填
```

租户只能查询自己 `tenant_id` 的批次和 item；配置、delegate/collector 地址变更属于平台级高风险操作。页面至少显示网络、token、
热钱包、item 成功/失败数、预估/实际 Gas、canonical txHash、attempt、确认数、错误分类和时间线。所有 retry/review/config
操作写入现有审计事件体系，不在接口返回 authorization 签名和原始私钥材料。

### 第 11 步：测试、灰度和正式启用

#### 11.1 合约单元测试

在 `evm-fork/test/Eip7702Collection.test.js` 至少覆盖：

1. 正确 EIP-712 签名可以把 ERC-20 从 delegated EOA 转到热钱包。
2. 非白名单 Relayer 无法调用 collector。
3. recipient、token、amount、collector、chainId 任意一个被修改后签名失效。
4. operation nonce 不能重放，过期请求不能执行。
5. 一个 item 失败不影响同批其他 item；批次结构错误整体回滚。
6. 返回 `false`、不返回值、revert、转账税、恶意重入 Token 均得到预期结果。
7. 100 个 item 边界成功，101 个被拒绝，单 item Gas 上下限生效。
8. admin 两步交接、Relayer 增删权限正确。
9. delegation 首次设置、重复使用、迁移、撤销后，code 和 storage 行为符合预期。

合约逻辑单测可用 `hardhat_setCode` 把已部署 Delegate 的 runtime code 临时设置到测试 Authority；真正的 authorization
编码、type-4 签名与同交易生效必须由 `Evm7702BatchTransactionService` 集成测试通过 Web3j 发到 Prague Hardhat 节点验证。
不要依赖当前 ethers `6.13.5` 中不存在或不稳定的 7702 高层辅助 API。

执行：

```bash
cd evm-fork
npm ci
npx hardhat compile
npx hardhat test test/Eip7702Collection.test.js
```

#### 11.2 Java 测试

新增测试建议与执行命令：

```text
wallet-service/.../evm/Evm7702AuthorizationServiceTest.java
wallet-service/.../evm/Evm7702OperationSignerTest.java
wallet-service/.../evm/Evm7702BatchTransactionServiceTest.java
wallet-service/.../evm/Evm7702ReceiptParserTest.java
wallet-server/.../account/Evm7702CollectionWorkflowServiceTest.java
wallet-server/.../custody/CustodyGasServiceTest.java
wallet-server/.../custody/CustodyOperationsIntegrationTest.java
```

```bash
cd backendservices
mvn -pl wallet-parent/wallet-service -am test
mvn -pl wallet-parent/wallet-server -am test
```

除常规成功路径外，必须注入 RPC timeout、同 nonce replacement、重复调度、进程在广播前/后宕机、部分 item 失败、Gas
超预留、余额在签名后变化、reorg、错误 code hash、跨租户 ID 等场景。测试断言不仅检查链上余额，还要检查批次状态、每个 item、
Gas 锁定/解锁、ledger entry 唯一性和审计事件。

#### 11.3 本地端到端验收

按这个顺序操作：

1. 启动 Prague Hardhat 节点，部署 MockERC20、BatchCollector 和 Delegate。
2. 启动 PostgreSQL，使用修改后的初始化 SQL 建库。
3. 在 `chain_profile/chain_rpc_node/evm_7702_config` 写本地链配置，状态先为 `SHADOW`。
4. 通过现有开户流程创建三个租户充值地址，不给这些地址转 ETH。
5. 只给三个地址铸造 MockERC20；确认三笔充值后触发归集候选。
6. SHADOW 模式只生成分组、签名摘要和模拟结果，不广播，并对比旧路径估算。确认无跨租户组批。
7. 将本地配置切为 `ACTIVE`，重新产生三笔归集；Relayer 账户必须有本地 ETH。
8. 等待确认，断言三个充值地址 ERC-20 余额为 0、各租户对应热钱包增加正确金额、三个地址 ETH 余额始终为 0。
9. 断言只有一个 canonical txHash、三个 item/logIndex、一个 Gas usage、三条物理归集记录，没有重复用户余额流水。
10. 重启服务并重复扫描，断言不会再次广播或重复扣 Gas。

#### 11.4 测试网灰度

1. 选择明确已启用 EIP-7702 且所有供应商 RPC 均支持 type-4 的测试网。
2. 独立部署并验证代码，录入测试网专用 admin/relayer/config，禁止复用生产签名器。
3. SHADOW 至少运行 7 天，记录候选数、模拟通过率、预估/实际 Gas 偏差和 RPC 差异。
4. ACTIVE 先限制一个内部测试租户、一种标准 ERC-20、每批 2 个、每日低金额上限。
5. 逐步扩到 10、20、50 个，并观测 P50/P95 Gas/item、失败率、pending 时间和人工介入率。
6. 完成合约独立审计、威胁建模和恢复演练后，才允许申请主网灰度。

## 7. 上线、暂停和回滚

### 7.1 上线开关

开关粒度为 `chain + network + version`，状态语义：

- `DISABLED`：完全不生成 7702 批次。
- `SHADOW`：只分组、构造并模拟，不签外层交易、不广播、不改变原归集认领权。
- `ACTIVE`：符合条件的 EVM ERC-20 只走 7702。
- `PAUSED`：停止新批，继续确认和对账已经提交的交易。

从 ACTIVE 回退时先切 PAUSED，处理所有 `SUBMITTED/BROADCAST_UNKNOWN/CONFIRMING` 批次；在途清零后才切 DISABLED。
绝不能通过关闭任务忽略已广播交易。

### 7.2 合约回滚与撤销 delegation

停止 7702 归集不要求立刻清除每个 EOA 的 delegation。紧急顺序应为：

1. 多签在 Collector 中移除全部在线 Relayer。
2. 配置切 PAUSED，停止产生和签署新请求。
3. 对在途交易按 txHash/nonce 持续确认，完成 Gas 和 item 对账。
4. 修复并部署新 Collector + 新 Delegate，写入新 version，重新从 SHADOW 开始。
5. 只有确需清除或迁移时，才让每个 Authority 签新的 7702 authorization；这需要链上 Gas，由平台 Relayer 赞助并记录批次。

由于旧 delegate 将 collector 固化为 immutable 地址，移除旧 Collector 的 Relayer 后，旧 delegate 即使仍挂在 EOA 上也不能
被外部任意调用转走 token。不要销毁合约；保留代码和部署记录才能审计历史交易。

数据库回滚不能删除 batch/item/attempt/Gas/ledger/audit 记录。代码回滚必须保留只读的旧版本回执解析能力，直到所有旧版本
在途批次终结。这不是业务双写兼容，而是资金交易的必要历史审计。

## 8. 手续费会不会降低

结论是“通常会降低每个 item 的平均 Gas，但第一批不一定”。粗略比较：

```text
旧模式总 Gas ≈ N × (21,000 + ERC20 transfer 执行与 calldata)

7702 首次总 Gas ≈ 21,000
               + N × authorization 成本
               + Collector 循环与事件
               + N × delegated transfer 执行

7702 后续总 Gas ≈ 21,000
               + Collector 循环与事件
               + N × delegated transfer 执行
```

协议中给新 Authority delegation 的账户成本是每个 `25,000` Gas 量级；已经有 delegation 时不再重复承担这部分。因此：

- 只归集一个新地址，可能不比旧模式便宜，主要收益是免去先补 ETH 的运维和那笔补 Gas 交易。
- 同一地址之后再次收款，或一个批次地址较多，平摊的基础成本更低。
- 一笔批次 calldata 和事件更多，不能简单认为 N 笔一定缩成 1/N。
- Gas Price 由网络决定，批量不会让单价变低；省的是重复交易信封、补 Gas 交易和运维损耗。

上线决策使用 fork/测试网的真实数据，指标计算：

```text
averageGasPerSuccessfulItem = receipt.gasUsed / successItemCount
effectiveFeePerItem         = actualNetworkFee / successItemCount
savingRate                  = 1 - 7702实际费用 / 同时段旧路径估算费用
```

失败 item 也消耗链上 Gas。对租户账务建议整批按实际网络费扣一次；Console 可以展示“按成功 item 平均”的统计值，但不要生成
多条实际网络费借项，否则会与链上 receipt 无法一一对账。

## 9. 第一阶段完成后，地址是否还需要 ETH

对于满足下列条件的 **EVM ERC-20 归集**，新地址创建后不需要再转入 ETH：

- 目标链已经激活 EIP-7702，RPC 支持 type-4；
- Token 是标准 ERC-20，不收转账税、不 rebasing；
- 该地址私钥可由现有 sig2 托管体系安全派生；
- Relayer 有足够原生币，租户 Gas 账户有足够可用余额；
- 地址没有未知代码或冲突 delegation。

不满足时不能假装免 Gas：该网络继续使用明确配置的原路径，或暂停并提示补充 Gas。第一阶段的合约不处理原生 ETH 归集；原生币
本身就在地址里，可继续用现有 `sendNative`。因此准确表述是“标准 ERC-20 充值地址不再需要预充值 ETH”，不是“系统所有链、
所有资产永远不需要 Gas”。

同一个外层交易可以从多个充值地址归集到热提钱包，前提是它们属于同一租户、同一链、同一 token、同一热钱包和同一批准的
delegate 版本。不同租户即使技术上可以放进一个交易，也必须拆批，保证 Gas 账务、权限和故障边界清晰。

首次归集也不需要先单独发一笔“开通 7702”的链上交易：Authority 在链下签 authorization，Relayer 把它放进第一次批次的
type-4 `authorizationList`，同一笔外层交易先设置 delegation，再调用 Collector 完成归集。首次仍只有一个外层 txHash，只是
每个首次地址会多消耗 authorization Gas；以后该地址保持正确 delegation 时不再重复附带 authorization。

## 10. 安全红线

- Delegate 不提供任意 call、approve、delegatecall、合约升级或用户传入 selector。
- 热钱包 recipient 从租户配置读取并纳入签名；接口请求不能临时指定任意地址。
- Authority 私钥和 Relayer 私钥职责分离；Relayer 泄露不能伪造 Authority operation 签名。
- Collector admin 必须是多签，部署者完成权限交接后不保留管理员权限。
- 每条链独立 Relayer、nonce、余额和限额；不跨链复用 authorization，不用 chainId 0。
- 每批限制 item 数、USD 总价值、Gas、calldata 和区块 Gas 比例；异常自动 PAUSED。
- RPC 返回值按不可信输入处理；广播、receipt、日志和 code hash 至少支持双节点核验。
- 不支持的 Token 默认拒绝，使用 allowlist；代理 Token 升级或 code hash 变化触发重新评估。
- 金额全程使用最小单位 `BigInteger/numeric(78,0)`，只在租户 Gas 账本显示层使用精确 `BigDecimal`，禁止浮点数。
- 明文 authorization、业务签名和 raw transaction 只存在于签名/组包内存；为崩溃恢复仅保存 envelope-encrypted raw
  transaction、digest、密钥版本和必要审计元数据，禁止明文日志和 API 返回。
- 所有状态转移带期望旧状态条件，所有外部操作带幂等键；定时任务可重复执行但不能重复广播或重复记账。

## 11. 后续基于 7702 的系统优化点

第一阶段稳定后，按风险从低到高推进：

1. **动态批次优化器**：依据余额、Gas 价格、calldata、区块容量和历史成功率自动决定是否等待拼批，而不是固定每批 20/50。
2. **多 Relayer 高可用**：每链多个隔离 Relayer，基于 nonce lane、余额阈值和节点健康切换；仍保持一次 operation 只结算一笔费。
3. **自动 Gas 补给**：从平台 Gas Treasury 向 Relayer 自动补充原生币，使用阈值、日限额、审批和完整流水。
4. **Delegate 版本迁移器**：批量检查 code、分批授权新版本、暂停异常地址，并支持旧版本只读对账。
5. **Session Key/限额**：让短期自动化密钥只能在时间窗、Token allowlist、单笔/日累计限额内归集；主 Authority 签名器离线化。
6. **ERC-4337 集成**：在网络生态成熟时接 Bundler/Paymaster，实现 UserOperation 队列、策略化赞助和更丰富的账户恢复；7702
   可作为 EOA 进入智能账户体系的桥梁，但 4337 不是第一阶段归集的前置条件。
7. **原生币受限归集**：审计后为 delegate 增加只向固定热钱包发送 native asset 的专用入口，必须预留 dust 和失败恢复；不要
   直接给现有 v1 增加任意 value call，应部署 v2 并重新授权。
8. **租户资产兑换编排**：租户用热钱包资产做 LI.FI/DEX/跨链兑换时，可让 v2 delegate 承载“精确 approve + 调用 allowlist
   router + 最小到账 + deadline”的一次性签名。只允许已归集到租户 Treasury 的资产参与，不能直接把所有用户充值地址接到
   任意路由器。这与本方案的归集批次、Gas 账务分开建模。
9. **风控与可观测性**：按 Token、delegate version、RPC、Relayer 建立失败率、Gas 偏差、reorg、unknown broadcast、签名失败
   告警，自动阻断异常组合。

## 12. 文件级实施清单

按顺序提交小而可回滚的变更，每个提交都能独立通过测试：

1. `evm-fork/contracts/Eip7702Collection.sol`、部署脚本、合约测试和部署说明。
2. `docs/db/surprising-wallet-init-pgsql.sql`：配置、account 投影、batch/item/attempt、Gas usage 目标结构。
3. `wallet-service`：配置仓储、authorization、EIP-712、type-4、receipt parser 与单元测试。
4. `wallet-server`：批次 Repository、Workflow、Gas 通用化、状态恢复与集成测试。
5. `AccountChainWorkflowService`：EVM ERC-20 ACTIVE 路由切换；删除不再使用的逐条 token 归集分支，而不是保留双实现。
6. 管理 API、租户查询 API、Console 批次详情与审计。
7. Hardhat fork + 本地端到端脚本、测试网 SHADOW、受限 ACTIVE。

第一阶段验收标准：

- 三个 ETH 为 0 的 EVM 充值地址能在一笔 type-4 交易中把标准 ERC-20 归集到正确租户热钱包；
- 只有一个 canonical txHash，每个 item 可独立对账和重试；
- 一个租户一笔批次只产生一次实际 Gas 扣账；
- 重复任务、进程重启、RPC 超时、replacement、部分失败和 reorg 都不造成重复转账或重复记账；
- 跨租户、错误热钱包、错误 chainId、过期/重放签名、未知 delegation 全部被拒绝并留下审计记录；
- ACTIVE 可立即切 PAUSED，已广播批次仍能继续确认、结算和查询。

## 13. 当前项目 EVM 链支持矩阵（核验于 2026-07-22）

### 13.1 核验口径

当前初始化 SQL 一共配置了 12 个 EVM 链族、24 个网络。测试网 `chain_profile.enabled=true`，主网 profile 已预置但
`enabled=false`。这里的“链已支持”必须同时考虑三层，不能根据 `family='evm'` 直接判断：

1. **协议层**：网络已经激活 EIP-7702 或等价实现，接受 type `0x04` 和 authorization list。
2. **RPC 层**：项目当前 `chain_rpc_node` 对应的节点能够解析、估算、广播和查询 type-4；链支持不代表每家 RPC 都支持。
3. **项目层**：第 6 节的合约、Web3j 服务、账务和状态机完成并通过该网络端到端测试。ETH 已完成第一阶段；BNB、Polygon、
   Base、Arbitrum 已分别完成 chainId 97、80002、84532、421614 的本地真实 type-4 和两租户五地址生产路径。BNB 官方 RPC、Polygon 项目
   PublicNode，以及 Base 官方/项目 RPC 通过严格 estimate 门禁；Base 还完成 OP Stack 完整费用入账。三条链都还没有有资金的
   公开测试网 E2E。其他链仍须逐链集成。没有任何网络可以因此直接设为生产 `ACTIVE`。

本次 RPC 探测不是发送普通 legacy 交易，也不是只传一个可能被节点忽略的 `authorizationList` 字段，而是使用项目当前
Web3j `6.0.0` 真实构造并签署 type-4 raw transaction，再调用仓库配置的 `eth_sendRawTransaction`：

- 返回 `nonce too low`、`insufficient funds`、`gas price too low`：节点已经完成 type-4 解码并进入 txpool 校验，记为“接受”。
- 返回 `transaction type not supported` 或 `EIP-7702 authorization list not supported`：记为“不支持”。
- 超时、限流、网关拒绝：只能记为“RPC 未验证”，不能推断链协议不支持。

探测使用公开测试私钥派生的无资金/旧 nonce 地址，所有请求都在 txpool 校验阶段被拒绝，没有产生链上交易。该探测只能证明
RPC 接受交易信封，不能代替 funded end-to-end delegation 测试。

### 13.2 逐网络结果

| 项目 chain | network | chainId | 当前 profile | 协议/官方依据 | 当前仓库 RPC 探测 | 7702 配置结论 |
|---|---|---:|---|---|---|---|
| ETH | sepolia | 11155111 | enabled | Pectra 已激活 | 接受 type-4，进入 nonce 校验 | 可做第一优先级 SHADOW/E2E |
| ETH | mainnet | 1 | disabled | Pectra 2025-05-07 激活 | 接受 type-4，进入 nonce 校验 | 测试网通过后再开 SHADOW |
| BNB | testnet | 97 | enabled | Pascal/BEP-441 已启用 | 2026-07-22 官方 RPC 成功 estimate 有效 authorization list；本地真实 type-4 与生产路径通过 | 已集成；仅 SHADOW，等待有资金公开测试网 E2E |
| BNB | mainnet | 56 | disabled | Pascal/BEP-441 已启用 | 接受 type-4，进入 Gas 价格校验 | 测试网通过后再开 SHADOW |
| POLYGON | amoy | 80002 | enabled | Bhilai/PIP-61 体系 | 项目 PublicNode 成功 estimate；官方列表 dRPC 忽略 authorization 成本；本地真实 type-4/生产路径通过 | 已集成；仅 SHADOW，需独立备份 RPC 和有资金 E2E |
| POLYGON | mainnet | 137 | disabled | Bhilai 于 2025-07-01 上线 | 接受 type-4，进入最低 tip 校验 | Amoy 通过后再开 SHADOW |
| ARBITRUM | sepolia | 421614 | enabled | ArbOS 40 Callisto；当前 ArbOS 51 | 官方/项目 RPC 严格 estimate、只读 gasUsedForL1、本地真实 type-4/生产路径通过 | 已集成；仅 SHADOW，需双生产 RPC 和有资金 E2E |
| ARBITRUM | mainnet | 42161 | disabled | ArbOS 40 于 2025-06-18 上线 | 接受 type-4，进入 nonce 校验 | Sepolia 通过后再开 SHADOW |
| OPTIMISM | sepolia | 11155420 | enabled | OP Stack Isthmus | 接受 type-4，进入 nonce 校验 | 可做第一优先级 SHADOW/E2E |
| OPTIMISM | mainnet | 10 | disabled | Isthmus 于 2025-05-09 激活 | 接受 type-4，进入 nonce 校验 | Sepolia 通过后再开 SHADOW |
| BASE | sepolia | 84532 | enabled | Isthmus 于 2025-04-17 激活 | 官方/项目 RPC 严格 estimate、只读 L1 fee、本地真实 type-4/生产路径通过 | 已集成；仅 SHADOW，需双生产 RPC 和有资金 E2E |
| BASE | mainnet | 8453 | disabled | Isthmus 于 2025-05-09 激活 | 接受 type-4，进入 nonce 校验 | Sepolia 通过后再开 SHADOW |
| AVAX_C | fuji | 43113 | enabled | 未找到官方 7702 激活依据 | **拒绝：transaction type not supported** | 必须 DISABLED，继续旧归集 |
| AVAX_C | mainnet | 43114 | disabled | 未找到官方 7702 激活依据 | **拒绝：transaction type not supported** | 必须 DISABLED，继续旧归集 |
| HYPEREVM | testnet | 998 | enabled | 官方仍声明 Cancun without blobs | **拒绝：transaction type not supported** | 必须 DISABLED，继续旧归集 |
| HYPEREVM | mainnet | 999 | disabled | 官方仍声明 Cancun without blobs | **拒绝：transaction type not supported** | 必须 DISABLED，继续旧归集 |
| MANTLE | sepolia | 5003 | enabled | 未找到明确的官方 7702 激活公告 | 接受 type-4，进入余额校验 | **条件支持**；先做 funded E2E，只可 SHADOW |
| MANTLE | mainnet | 5000 | disabled | 未找到明确的官方 7702 激活公告 | 接受 type-4，进入 nonce 校验 | 测试网 E2E 及官方确认前保持 DISABLED |
| LINEA | sepolia | 59141 | enabled | Linea 当前 stack 已内置 EIP-7702 | 接受 type-4，进入 nonce 校验 | 可做 SHADOW/E2E |
| LINEA | mainnet | 59144 | disabled | Linea 当前 stack 已内置 EIP-7702 | 接受 type-4，进入 Gas 价格校验 | Sepolia 通过后再开 SHADOW |
| SCROLL | sepolia | 534351 | enabled | Euclid 2025-03-13 完成 | 接受 type-4，进入 nonce 校验 | 可做第一优先级 SHADOW/E2E |
| SCROLL | mainnet | 534352 | disabled | Euclid 2025-04-22 完成 | 接受 type-4，进入 nonce 校验 | Sepolia 通过后再开 SHADOW |
| UNICHAIN | sepolia | 1301 | enabled | OP Stack Isthmus | 接受 type-4，进入 nonce 校验 | 可做 SHADOW/E2E |
| UNICHAIN | mainnet | 130 | disabled | Isthmus 于 2025-05-09 激活 | 接受 type-4，进入 nonce 校验 | Sepolia 通过后再开 SHADOW |

结论：当前可以进入项目测试阶段的是 **ETH、BNB、POLYGON、ARBITRUM、OPTIMISM、BASE、MANTLE（条件）、LINEA、
SCROLL、UNICHAIN**；当前明确不能使用本方案的是 **AVAX_C、HYPEREVM**。这里的“可以测试”不等于“可以生产广播”。

### 13.3 官方依据

- Ethereum：[Pectra Mainnet Announcement](https://blog.ethereum.org/en/2025/04/23/pectra-mainnet)，明确包含 EIP-7702，
  并说明此前已在 Sepolia 激活。
- BNB Chain：[Pascal Hardfork](https://www.bnbchain.org/en/blog/bnb-chain-announces-pascal-hard-fork) 与
  [BEP-441](https://github.com/bnb-chain/BEPs/blob/master/BEPs/BEP-441.md)，BEP 状态为 Enabled。
- Polygon PoS：[Bhilai Hardfork](https://polygon.technology/blog/first-milestone-to-gigagas-1000-tps-with-bhilai-hardfork)，明确
  宣布 EIP-7702 已上线。
- Arbitrum：[ArbOS 40 Callisto](https://blog.arbitrum.foundation/the-smartest-wallet-you-already-own-is-on-arbitrum/)，明确说明
  Arbitrum One 已于 2025-06-18 获得 EIP-7702 支持；[ArbOS 51 Dia](https://docs.arbitrum.io/run-arbitrum-node/arbos-releases/arbos51)
  已于 2026-01-08 激活并修复一项 EIP-7702 预编译委托行为差异。
- OP Stack/Optimism/Unichain：[Isthmus specification](https://specs.optimism.io/protocol/isthmus/overview.html) 明确纳入
  EIP-7702；[Upgrade Proposal 15a](https://gov.optimism.io/t/upgrade-proposal-15a-absolute-prestate-updates-for-isthmus-activation-blob-preimage-fix/9869)
  记录 OP Mainnet、Unichain、Base 的激活时间。
- Base：[Isthmus activation table](https://docs.base.org/base-chain/specs/upgrades/isthmus/overview) 同时给出 Sepolia 和
  mainnet 的激活时间并列出 EIP-7702。
- Scroll：[Euclid Upgrade](https://docs.scroll.io/en/technology/overview/scroll-upgrades/euclid-upgrade/) 给出两个网络的
  上线阶段和 EIP-7702 支持。
- Linea：[EIP-7702 is now built into the Linea stack](https://linea.build/blog/your-wallet-just-got-smarter-without-changing-your-address)。
- HyperEVM：[官方 EVM 版本说明](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/hyperevm) 仍是 Cancun
  without blobs，没有 Prague/EIP-7702；实际 RPC 也明确拒绝 type-4。
- Avalanche C-Chain 和 Mantle 没有找到足以单独作为上线依据的官方 EIP-7702 激活说明，因此分别以实际 type-4 拒绝和
  接受结果标记为“不支持”与“条件支持”。这两个结论每次网络升级后都要重新核验。

### 13.4 上线前每条链必须执行的能力门禁

不能把上表硬编码成永久能力列表。实现第 1 步 `evm_7702_config` 后，每个 `chain + network + RPC node` 都必须依次通过：

1. `eth_chainId` 与 `chain_profile.chain_id` 完全一致。
2. 用无资金探测账户提交正确编码但不会入链的 type-4，确认不是 `transaction type not supported`；该步骤只用于诊断。
3. 部署本方案 Collector/Delegate，核对两个 runtime code hash。
4. 用测试 Authority 和 funded Relayer 执行一笔首次 authorization + 单 item ERC-20 归集，确认 Authority 原生币余额为 0。
5. 再向同一 Authority 铸币，执行不带 authorization 的第二次归集，验证 delegation 持久、operation nonce 递增。
6. 执行两个 Authority 的批次，验证一个 canonical txHash、两个 item event 和一次 Gas 结算。
7. 在项目配置的每一个 primary/backup RPC 上分别完成 estimate、send、getTransaction、receipt 和 log 查询；任一生产节点不支持，
   该节点不得加入 7702 RPC 池。
8. 把探测时间、节点、client version、txHash、receipt、code hash 和测试结果写入审计记录，通过后才允许 `SHADOW -> ACTIVE`。

网络升级或 RPC 供应商切换时自动把该网络降为 `PAUSED`，重新跑门禁。AVAX_C、HYPEREVM 后续即使官方宣布升级，也只能先从
测试网 SHADOW 重新开始，不能仅修改静态表格后直接 ACTIVE。

## 14. 参考资料

- [EIP-7702: Set EOA account code](https://eips.ethereum.org/EIPS/eip-7702)
- [Ethereum.org：EIP-7702](https://ethereum.org/roadmap/pectra/7702/)
- [ERC-4337 Account Abstraction](https://eips.ethereum.org/EIPS/eip-4337)
