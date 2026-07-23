// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/**
 * EIP-7702 归集请求的数据模型。
 *
 * EIP-7702 不会把用户充值地址转换成一个新的合约地址，而是让原有 EOA 在执行交易时
 * 临时/持续委托本文件中的 Delegate 代码。Delegate 运行时，address(this)、余额和存储
 * 都属于该 EOA，因此它可以直接转出该地址持有的原生币或 ERC-20，同时地址本身不变。
 */
library Eip7702CollectionTypes {
    struct CollectionRequest {
        // 后端生成的归集批次 ID，同一外层交易中的全部条目必须一致。
        bytes32 batchId;
        // 条目在批次中的位置，必须从 0 连续递增，防止重排或替换条目。
        uint256 itemIndex;
        // 被归集的充值 EOA；Delegate 上下文中必须等于 address(this)。
        address authority;
        // 唯一允许调用 Delegate 的批量归集合约。
        address collector;
        // address(0) 表示原生币，否则表示 ERC-20 合约地址。
        address token;
        // 归集目标，通常是当前租户的热钱包地址。
        address recipient;
        // 最小单位金额：原生币为 wei，Token 为其 decimals 对应的最小单位。
        uint256 amount;
        // 每个充值 EOA 独立维护的业务 nonce，用于防止同一授权被重复执行。
        uint256 operationNonce;
        // 授权过期时间（Unix 秒），避免旧签名长期有效。
        uint256 deadline;
        // Collector 调用单个 Authority 时提供的 gas 上限，隔离异常条目的消耗。
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

/**
 * 充值地址的 EIP-7702 归集 Delegate，支持原生币和 ERC-20。
 *
 * 安全边界：
 * 1. 本合约代码通过 EIP-7702 在充值 EOA 的地址/余额/存储上下文中运行；部署合约本身
 *    不保存用户资产。
 * 2. 只接受部署时固化的 EXPECTED_COLLECTOR 调用，不提供 arbitrary call、approve、
 *    delegatecall 或升级入口，避免把充值地址变成通用智能账户。
 * 3. 每个请求还必须由该充值 EOA 对完整 EIP-712 数据签名；Relayer/Collector 不能自行
 *    修改币种、金额、收款地址、nonce 或过期时间。
 * 4. operationNonce 存在充值 EOA 自己的确定性存储槽中，成功执行后递增，防止重放。
 * 5. 转账前后核对收款方余额，手续费型/通缩型 Token 不会被误记为足额归集。
 */
contract Eip7702CollectionDelegate is IEip7702CollectionDelegate {
    error UnauthorizedCollector();
    error InvalidRequest();
    error ExpiredRequest();
    error InvalidNonce();
    error InvalidSignature();
    error ReentrantCall();
    error TokenCallFailed();
    error NativeCallFailed();
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
    // 使用命名哈希槽，避免 Delegate 以后增加普通状态变量时发生存储槽冲突。
    bytes32 private constant OPERATION_NONCE_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.collection.nonce.v1")) - 1);
    bytes32 private constant REENTRANCY_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.collection.reentrancy.v1")) - 1);
    uint256 private constant SECP256K1N_HALF =
        0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0;

    // immutable 值固化在 Delegate 字节码中；只有这个 Collector 能发起归集。
    address public immutable EXPECTED_COLLECTOR;

    constructor(address expectedCollector) {
        if (expectedCollector == address(0)) revert InvalidRequest();
        EXPECTED_COLLECTOR = expectedCollector;
    }

    /** 委托后的充值 EOA 仍需能接收 ETH 等原生币，因此保留 payable receive。 */
    receive() external payable { }

    /** 返回当前充值 EOA 的业务 nonce；调用的是哪个 EOA，就读取哪个 EOA 的存储。 */
    function operationNonce() external view returns (uint256 value) {
        bytes32 slot = OPERATION_NONCE_SLOT;
        assembly {
            value := sload(slot)
        }
    }

    /**
     * EIP-712 域同时绑定 chainId 和当前充值 EOA 地址。
     * 同一签名不能拿到另一条链或另一个充值地址上复用。
     */
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

    /**
     * 执行单个地址的一笔归集。
     *
     * 正常调用路径：平台 Relayer -> Eip7702BatchCollector.collectBatch ->
     * 充值 EOA（已委托本 Delegate）-> collect。
     *
     * 任一检查或转账失败都会回滚该条目的 nonce 和资产变化；Collector 使用低级 call
     * 隔离每个条目，所以一个地址失败不会让同批其他地址全部失败。
     */
    function collect(
        Eip7702CollectionTypes.CollectionRequest calldata request,
        bytes calldata authoritySignature
    ) external returns (uint256 actualReceived) {
        if (msg.sender != EXPECTED_COLLECTOR) revert UnauthorizedCollector();
        if (
            request.authority != address(this)
                || request.collector != EXPECTED_COLLECTOR
                || request.recipient == address(0)
                || request.recipient == address(this)
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

        // token == address(0) 是原生币分支，否则调用 ERC-20 transfer。
        uint256 balanceBefore = _balanceOf(request.token, request.recipient);
        if (request.token == address(0)) {
            _safeNativeTransfer(request.recipient, request.amount);
        } else {
            _safeTokenTransfer(request.token, request.recipient, request.amount);
        }
        uint256 balanceAfter = _balanceOf(request.token, request.recipient);
        if (balanceAfter < balanceBefore) revert UnexpectedReceivedAmount();
        actualReceived = balanceAfter - balanceBefore;
        // 只接受精确到账，避免链上成功但账务按请求金额多记。
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

    /** 进入单条执行的重入锁；状态实际写入当前充值 EOA 的命名存储槽。 */
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

    function _balanceOf(address token, address account) private view returns (uint256) {
        return token == address(0)
            ? account.balance
            : IERC20CollectionToken(token).balanceOf(account);
    }

    function _safeNativeTransfer(address recipient, uint256 amount) private {
        (bool success,) = recipient.call{value: amount}("");
        if (!success) revert NativeCallFailed();
    }

    /**
     * 兼容两类 ERC-20：标准 Token 返回 true，部分老 Token 成功时不返回数据。
     * 明确返回 false 或底层调用 revert 都视为失败。
     */
    function _safeTokenTransfer(address token, address recipient, uint256 amount) private {
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

    /**
     * 恢复 EIP-712 签名者，并强制 low-s 与标准 v 值，阻止签名可塑性。
     */
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

/**
 * EIP-7702 多地址批量归集入口。
 *
 * 一笔外层链上交易调用 collectBatch，合约再逐个调用多个充值 EOA。Gas 由平台 Relayer
 * 支付，充值地址无需预存原生币。每个子调用都限制 gas 并独立记录成功/失败事件，某个地址
 * 余额不足、签名错误或 Token 异常不会回滚整个批次。
 *
 * 注意：此合约只负责调度和事件审计，真正的资产转出权限仍由每个充值 EOA 的 EIP-712
 * 签名控制。管理员只能管理 Relayer，不能绕过 Authority 签名提取资产。
 */
contract Eip7702BatchCollector {
    error Unauthorized();
    error InvalidConfiguration();
    error InvalidBatch();

    uint256 public constant MAX_ITEMS = 100;
    uint256 public constant MIN_ITEM_GAS = 60_000;
    uint256 public constant MAX_ITEM_GAS = 500_000;

    // 管理员采用两步交接，避免地址输错后永久失去管理权。
    address public admin;
    address public pendingAdmin;
    // 只有白名单 Relayer 能提交批次，防止第三方消耗已签名请求或恶意占用 gas。
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

    /** 第一步：现管理员设置候选管理员。 */
    function setPendingAdmin(address nextAdmin) external onlyAdmin {
        if (nextAdmin == address(0)) revert InvalidConfiguration();
        pendingAdmin = nextAdmin;
        emit PendingAdminSet(nextAdmin);
    }

    /** 第二步：候选地址主动确认，完成管理员交接。 */
    function acceptAdmin() external {
        if (msg.sender != pendingAdmin) revert Unauthorized();
        admin = pendingAdmin;
        pendingAdmin = address(0);
        emit AdminAccepted(admin);
    }

    /** 启用或停用平台 Relayer；变更通过事件进入链上审计记录。 */
    function setRelayer(address relayer, bool enabled) external onlyAdmin {
        if (relayer == address(0)) revert InvalidConfiguration();
        relayers[relayer] = enabled;
        emit RelayerSet(relayer, enabled);
    }

    /**
     * 在一笔交易中归集多个充值地址。
     *
     * 批次级结构错误直接回滚（例如 batchId 不一致、索引不连续）；条目执行错误则只记录
     * CollectionItemResult(success=false)，随后继续处理其他条目。后端依据事件逐项入账，
     * 不能仅凭外层交易成功就把所有条目标记成功。
     */
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

            // 调用充值 EOA；EIP-7702 会把执行转到 Delegate，但 address(this) 仍是该 EOA。
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
