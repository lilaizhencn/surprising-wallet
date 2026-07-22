// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

library Eip7702PayoutTypes {
    struct PayoutItem {
        bytes32 withdrawalId;
        uint256 itemIndex;
        address token;
        address recipient;
        uint256 amount;
        uint256 callGasLimit;
    }
}

interface IERC20PayoutToken {
    function balanceOf(address account) external view returns (uint256);
    function transfer(address recipient, uint256 amount) external returns (bool);
}

/**
 * EIP-7702 delegate for one tenant hot-wallet EOA.
 *
 * The relayer pays outer-transaction gas while every payout is debited directly
 * from the delegated hot wallet. The surface intentionally excludes arbitrary
 * call, approve, delegatecall and contract-upgrade entry points.
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
    bytes32 private constant OPERATION_NONCE_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.payout.nonce.v1")) - 1);
    bytes32 private constant REENTRANCY_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.payout.reentrancy.v1")) - 1);
    uint256 private constant SECP256K1N_HALF =
        0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0;

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

    /** Keeps the delegated hot-wallet EOA able to receive native collections. */
    receive() external payable { }

    function operationNonce() external view returns (uint256 value) {
        return _operationNonce();
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

    /** Isolated item execution. Only the delegated EOA may call itself here. */
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

    function _balanceOf(address token, address account) private view returns (uint256) {
        if (token == address(0)) return account.balance;
        (bool success, bytes memory result) = token.staticcall(
            abi.encodeWithSelector(IERC20PayoutToken.balanceOf.selector, account)
        );
        if (!success || result.length != 32) revert AssetCallFailed();
        return abi.decode(result, (uint256));
    }

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
