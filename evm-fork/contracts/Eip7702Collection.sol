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

/**
 * Minimal EIP-7702 delegate for ERC-20 collection.
 *
 * This contract intentionally exposes no arbitrary call, approve or delegatecall entry point.
 * It executes in an Authority EOA's storage and address context through EIP-7702 delegation.
 */
contract Eip7702CollectionDelegate is IEip7702CollectionDelegate {
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
        if (balanceAfter < balanceBefore) revert UnexpectedReceivedAmount();
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
