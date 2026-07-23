// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/**
 * 批量提现的单条数据模型。
 * 整个数组会被哈希并纳入热钱包 EOA 的 EIP-712 签名，任何字段被修改都会导致验签失败。
 */
library Eip7702PayoutTypes {
    struct PayoutItem {
        // 后端提现记录的稳定标识，用于链上事件与数据库记录一一对应。
        bytes32 withdrawalId;
        // 条目位置，必须等于数组索引，防止 Relayer 对已签名条目重排。
        uint256 itemIndex;
        // address(0) 表示原生币，否则表示 ERC-20 合约地址。
        address token;
        // 用户实际收款地址。
        address recipient;
        // 最小单位金额：原生币为 wei，Token 为其 decimals 对应的最小单位。
        uint256 amount;
        // 单条提现的 gas 上限，用来隔离恶意/异常收款合约的 gas 消耗。
        uint256 callGasLimit;
    }
}

interface IERC20PayoutToken {
    function balanceOf(address account) external view returns (uint256);
    function transfer(address recipient, uint256 amount) external returns (bool);
}

/**
 * 单个租户热钱包 EOA 的 EIP-7702 批量提现 Delegate。
 *
 * 工作方式：热钱包 EOA 通过 EIP-7702 委托本代码后，平台 Relayer 向该热钱包地址调用
 * payoutBatch。代码虽然来自 Delegate 合约，但运行时 address(this)、资产与存储都属于热钱包
 * EOA，因此每笔提现直接从租户热钱包扣款，外层交易的 gas 则由 Relayer 支付。
 *
 * 安全边界：
 * 1. EXPECTED_EXECUTOR 在部署时固化，只有指定平台 Relayer/执行器可以提交批次。
 * 2. 热钱包必须对 batchId、完整 itemsHash、nonce、deadline、chainId 和自身地址做 EIP-712
 *    签名；执行器不能替换收款人、币种或金额。
 * 3. 不暴露 arbitrary call、approve、delegatecall 或升级函数，授权能力被限制为提现转账。
 * 4. nonce 防重放，deadline 防止陈旧签名长期有效，重入锁保护批次执行。
 * 5. 每条提现独立执行和记事件；单条失败不阻塞同批其他提现，后端必须逐项结算。
 */
contract Eip7702PayoutDelegate {
    error UnauthorizedExecutor();
    error UnauthorizedSelfCall();
    error InvalidBatch();
    error ExpiredRequest();
    error InvalidNonce();
    error InvalidSignature();
    error ReentrantCall();
    error AssetCallFailed();
    error UnexpectedReceivedAmount();

    string public constant NAME = "SurprisingWallet7702Payout";
    string public constant VERSION = "1";
    uint256 public constant MAX_ITEMS = 100;
    uint256 public constant MIN_ITEM_GAS = 30_000;
    uint256 public constant MAX_ITEM_GAS = 500_000;

    bytes32 public constant PAYOUT_ITEM_TYPEHASH = keccak256(
        "PayoutItem(bytes32 withdrawalId,uint256 itemIndex,address token,address recipient,uint256 amount,uint256 callGasLimit)"
    );
    bytes32 public constant PAYOUT_REQUEST_TYPEHASH = keccak256(
        "PayoutRequest(bytes32 batchId,address authority,address executor,bytes32 itemsHash,uint256 operationNonce,uint256 deadline)"
    );
    bytes32 private constant EIP712_DOMAIN_TYPEHASH = keccak256(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    );
    bytes32 private constant NAME_HASH = keccak256(bytes(NAME));
    bytes32 private constant VERSION_HASH = keccak256(bytes(VERSION));
    // 使用命名哈希槽，避免 Delegate 版本演进时与普通 Solidity 状态变量发生槽冲突。
    bytes32 private constant OPERATION_NONCE_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.payout.nonce.v1")) - 1);
    bytes32 private constant REENTRANCY_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.payout.reentrancy.v1")) - 1);
    uint256 private constant SECP256K1N_HALF =
        0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0;

    // immutable 值写入 Delegate 字节码；Delegate 在热钱包上下文执行时仍读取同一常量。
    address public immutable EXPECTED_EXECUTOR;

    event PayoutItemResult(
        bytes32 indexed batchId,
        uint256 indexed itemIndex,
        bytes32 indexed withdrawalId,
        address token,
        address recipient,
        uint256 requestedAmount,
        uint256 actualReceived,
        bool success,
        bytes32 errorHash
    );
    event PayoutBatchProcessed(
        bytes32 indexed batchId,
        address indexed authority,
        uint256 totalItems,
        uint256 successCount,
        uint256 failureCount
    );

    constructor(address expectedExecutor) {
        if (expectedExecutor == address(0)) revert InvalidBatch();
        EXPECTED_EXECUTOR = expectedExecutor;
    }

    /** 委托后的热钱包仍需接收原生币归集，因此保留 payable receive。 */
    receive() external payable { }

    /** 读取当前热钱包 EOA 的业务 nonce，而不是 Delegate 部署地址的存储。 */
    function operationNonce() external view returns (uint256 value) {
        return _operationNonce();
    }

    /**
     * EIP-712 域绑定 chainId 与当前热钱包地址，阻止签名跨链或跨租户热钱包复用。
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
     * 批量执行一个租户热钱包中的多笔提现。
     *
     * 批次签名、nonce 或结构错误会回滚整个外层调用；通过批次校验后，每个 item 使用
     * 限定 gas 的自调用独立执行。nonce 在处理条目前先递增，但如果函数整体 revert，EVM
     * 会把 nonce 一并回滚；正常返回时即便部分 item 失败，nonce 也会保留递增，防止重放。
     */
    function payoutBatch(
        bytes32 batchId,
        Eip7702PayoutTypes.PayoutItem[] calldata items,
        uint256 operationNonce_,
        uint256 deadline,
        bytes calldata authoritySignature
    ) external returns (uint256 successCount, uint256 failureCount) {
        if (msg.sender != EXPECTED_EXECUTOR) revert UnauthorizedExecutor();
        uint256 length = items.length;
        if (batchId == bytes32(0) || length == 0 || length > MAX_ITEMS) revert InvalidBatch();
        if (block.timestamp > deadline) revert ExpiredRequest();
        uint256 currentNonce = _operationNonce();
        if (operationNonce_ != currentNonce) revert InvalidNonce();
        _verifyRequest(batchId, items, operationNonce_, deadline, authoritySignature);
        _enter();
        _setOperationNonce(currentNonce + 1);
        (successCount, failureCount) = _processItems(batchId, items);
        _exit();
        emit PayoutBatchProcessed(batchId, address(this), length, successCount, failureCount);
    }

    /**
     * 逐项隔离执行。address(this).call 会形成新的调用帧：某个 item revert 时只返回 false，
     * 外层循环仍能继续；每项结果通过 PayoutItemResult 事件供后端精确结算和重试。
     */
    function _processItems(
        bytes32 batchId,
        Eip7702PayoutTypes.PayoutItem[] calldata items
    ) private returns (uint256 successCount, uint256 failureCount) {
        uint256 length = items.length;
        for (uint256 i = 0; i < length; ++i) {
            Eip7702PayoutTypes.PayoutItem calldata item = items[i];
            (bool success, bytes memory result) = address(this).call{
                gas: item.callGasLimit
            }(
                abi.encodeCall(
                    this.executePayout,
                    (item.token, item.recipient, item.amount)
                )
            );
            uint256 actualReceived;
            if (success && result.length == 32) {
                actualReceived = abi.decode(result, (uint256));
                success = actualReceived == item.amount;
            } else {
                success = false;
            }
            if (success) ++successCount;
            else ++failureCount;
            emit PayoutItemResult(
                batchId,
                i,
                item.withdrawalId,
                item.token,
                item.recipient,
                item.amount,
                actualReceived,
                success,
                success ? bytes32(0) : keccak256(result)
            );
        }
    }

    /**
     * 对批次总请求做 EIP-712 验签。itemsHash 覆盖数组顺序和每个字段，所以 Relayer 只能
     * 原样广播热钱包已批准的请求。
     */
    function _verifyRequest(
        bytes32 batchId,
        Eip7702PayoutTypes.PayoutItem[] calldata items,
        uint256 operationNonce_,
        uint256 deadline,
        bytes calldata authoritySignature
    ) private view {
        bytes32 structHash = keccak256(
            abi.encode(
                PAYOUT_REQUEST_TYPEHASH,
                batchId,
                address(this),
                EXPECTED_EXECUTOR,
                _validateAndHashItems(items),
                operationNonce_,
                deadline
            )
        );
        bytes32 digest = keccak256(
            abi.encodePacked("\x19\x01", domainSeparator(), structHash)
        );
        if (_recover(digest, authoritySignature) != address(this)) revert InvalidSignature();
    }

    /**
     * 单条提现的隔离执行入口，只允许热钱包 EOA 自调用。
     * 外部账户无法直接调用该函数绕开批次签名；同时要求重入锁已由 payoutBatch 打开。
     * 转账后以收款方余额差验证实际到账金额，手续费型 Token 不会被误判为足额提现。
     */
    function executePayout(address token, address recipient, uint256 amount)
        external
        returns (uint256 actualReceived)
    {
        if (msg.sender != address(this)) revert UnauthorizedSelfCall();
        if (_entered() != 1) revert ReentrantCall();
        uint256 balanceBefore = _balanceOf(token, recipient);
        if (token == address(0)) {
            (bool success,) = recipient.call{value: amount}("");
            if (!success) revert AssetCallFailed();
        } else {
            (bool success, bytes memory result) = token.call(
                abi.encodeWithSelector(IERC20PayoutToken.transfer.selector, recipient, amount)
            );
            if (!success || !_validTokenReturn(result)) revert AssetCallFailed();
        }
        uint256 balanceAfter = _balanceOf(token, recipient);
        if (balanceAfter < balanceBefore) revert UnexpectedReceivedAmount();
        actualReceived = balanceAfter - balanceBefore;
        if (actualReceived != amount) revert UnexpectedReceivedAmount();
    }

    /**
     * 验证条目结构并生成有序 itemsHash。gas 下限保证基础转账可执行，上限防止单个条目
     * 消耗掉几乎全部批次 gas；itemIndex 强制数组顺序不可歧义。
     */
    function _validateAndHashItems(Eip7702PayoutTypes.PayoutItem[] calldata items)
        private
        pure
        returns (bytes32)
    {
        bytes32[] memory hashes = new bytes32[](items.length);
        for (uint256 i = 0; i < items.length; ++i) {
            Eip7702PayoutTypes.PayoutItem calldata item = items[i];
            if (
                item.withdrawalId == bytes32(0)
                    || item.itemIndex != i
                    || item.recipient == address(0)
                    || item.amount == 0
                    || item.callGasLimit < MIN_ITEM_GAS
                    || item.callGasLimit > MAX_ITEM_GAS
            ) revert InvalidBatch();
            hashes[i] = keccak256(
                abi.encode(
                    PAYOUT_ITEM_TYPEHASH,
                    item.withdrawalId,
                    item.itemIndex,
                    item.token,
                    item.recipient,
                    item.amount,
                    item.callGasLimit
                )
            );
        }
        return keccak256(abi.encodePacked(hashes));
    }

    /** 查询原生币或 ERC-20 收款方余额；ERC-20 返回值必须严格为 32 字节。 */
    function _balanceOf(address token, address account) private view returns (uint256) {
        if (token == address(0)) return account.balance;
        (bool success, bytes memory result) = token.staticcall(
            abi.encodeWithSelector(IERC20PayoutToken.balanceOf.selector, account)
        );
        if (!success || result.length != 32) revert AssetCallFailed();
        return abi.decode(result, (uint256));
    }

    /** 兼容标准 ERC-20 的 true 返回值和部分老 Token 的空返回值。 */
    function _validTokenReturn(bytes memory result) private pure returns (bool) {
        if (result.length == 0) return true;
        if (result.length != 32) return false;
        uint256 value;
        assembly {
            value := mload(add(result, 32))
        }
        return value == 1;
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

    function _entered() private view returns (uint256 value) {
        bytes32 slot = REENTRANCY_SLOT;
        assembly {
            value := sload(slot)
        }
    }

    function _enter() private {
        if (_entered() != 0) revert ReentrantCall();
        bytes32 slot = REENTRANCY_SLOT;
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

    /**
     * 从 EIP-712 digest 恢复热钱包签名者，并通过 low-s/v 检查阻止签名可塑性。
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
