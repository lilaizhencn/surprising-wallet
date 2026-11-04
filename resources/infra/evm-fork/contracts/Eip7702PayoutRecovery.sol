// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./Eip7702Payout.sol";

interface IERC20RecoveryToken {
    function balanceOf(address account) external view returns (uint256);
    function transfer(address recipient, uint256 amount) external returns (bool);
}

/**
 * Preinstalled EIP-7702 payout delegate with an independent, fixed-destination
 * emergency path. Deploy a distinct instance per tenant wallet and safe.
 *
 * Two separate guardian keys can move one asset at a time to SAFE without the
 * hot EOA signature. This only works while the EOA still delegates to this
 * contract. A thief holding the complete EOA key can re-delegate or race it;
 * this contract cannot guarantee recovery after a full private-key leak.
 */
contract Eip7702PayoutRecoveryDelegate is Eip7702PayoutDelegate {
    error InvalidRecoveryConfiguration();
    error InvalidRecoveryRequest();
    error RecoveryExpired();
    error RecoverySignature();
    error RecoveryReentrant();
    error RecoveryTransferFailed();

    string public constant RECOVERY_NAME = "SurprisingWallet7702Recovery";
    string public constant RECOVERY_VERSION = "1";
    uint256 public constant MAX_RECOVERY_TTL = 15 minutes;
    bytes32 public constant RECOVERY_TYPEHASH = keccak256(
        "Recovery(address authority,address safe,address token,uint256 amount,uint256 nonce,uint256 deadline)"
    );
    bytes32 private constant DOMAIN_TYPEHASH = keccak256(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    );
    bytes32 private constant NAME_HASH = keccak256(bytes(RECOVERY_NAME));
    bytes32 private constant VERSION_HASH = keccak256(bytes(RECOVERY_VERSION));
    bytes32 private constant NONCE_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.recovery.nonce.v1")) - 1);
    bytes32 private constant LOCK_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.recovery.lock.v1")) - 1);
    // The inherited payout code increments this nonce with checked arithmetic.
    // MaxUint256 permanently rejects all payout batches through this delegate.
    bytes32 private constant PAYOUT_NONCE_SLOT =
        bytes32(uint256(keccak256("surprising.wallet.eip7702.payout.nonce.v1")) - 1);
    uint256 private constant SECP256K1N_HALF =
        0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0;

    address public immutable SAFE;
    address public immutable GUARDIAN_A;
    address public immutable GUARDIAN_B;
    address public immutable GUARDIAN_C;

    event EmergencyAssetMoved(
        address indexed authority, address indexed safe, address indexed token,
        uint256 amount, uint256 nonce
    );

    constructor(address executor, address safe, address[3] memory guardians)
        Eip7702PayoutDelegate(executor)
    {
        if (safe == address(0) || safe == address(this)
            || guardians[0] == address(0) || guardians[1] == address(0)
            || guardians[2] == address(0) || guardians[0] == guardians[1]
            || guardians[0] == guardians[2] || guardians[1] == guardians[2]
            || executor == safe || executor == guardians[0]
            || executor == guardians[1] || executor == guardians[2]
            || safe == guardians[0] || safe == guardians[1] || safe == guardians[2]) {
            revert InvalidRecoveryConfiguration();
        }
        SAFE = safe;
        GUARDIAN_A = guardians[0];
        GUARDIAN_B = guardians[1];
        GUARDIAN_C = guardians[2];
    }

    function recoveryNonce() external view returns (uint256 value) {
        bytes32 slot = NONCE_SLOT;
        assembly { value := sload(slot) }
    }

    function recoveryDomainSeparator() public view returns (bytes32) {
        return keccak256(abi.encode(
            DOMAIN_TYPEHASH, NAME_HASH, VERSION_HASH, block.chainid, address(this)
        ));
    }

    /**
     * Both guardians sign the exact full-balance amount observed by the
     * caller. Concurrent deposits or spends invalidate the request; an
     * operator must observe again and obtain a new pair of approvals.
     */
    function emergencySweep(
        address token, uint256 amount, uint256 nonce, uint256 deadline,
        bytes calldata signatureA, bytes calldata signatureB
    ) external {
        _authorizeSweep(token, amount, nonce, deadline, signatureA, signatureB);
        if (_balance(token, address(this)) != amount) revert InvalidRecoveryRequest();
        bytes32 lockSlot = LOCK_SLOT;
        bytes32 payoutNonceSlot = PAYOUT_NONCE_SLOT;
        assembly {
            sstore(lockSlot, 1)
            sstore(payoutNonceSlot, not(0))
        }
        _transferFull(token, amount);
        assembly { sstore(lockSlot, 0) }
        emit EmergencyAssetMoved(address(this), SAFE, token, amount, nonce);
    }

    function _authorizeSweep(
        address token, uint256 amount, uint256 nonce, uint256 deadline,
        bytes calldata signatureA, bytes calldata signatureB
    ) private {
        if (address(this) == SAFE || amount == 0) revert InvalidRecoveryRequest();
        if (block.timestamp > deadline || deadline > block.timestamp + MAX_RECOVERY_TTL) {
            revert RecoveryExpired();
        }
        bytes32 nonceSlot = NONCE_SLOT;
        bytes32 lockSlot = LOCK_SLOT;
        uint256 currentNonce;
        uint256 entered;
        assembly {
            currentNonce := sload(nonceSlot)
            entered := sload(lockSlot)
        }
        if (entered != 0) revert RecoveryReentrant();
        if (nonce != currentNonce) revert InvalidRecoveryRequest();
        bytes32 digest = _recoveryDigest(token, amount, nonce, deadline);
        address signerA = _recoverGuardian(digest, signatureA);
        address signerB = _recoverGuardian(digest, signatureB);
        if (signerA == signerB || !_guardian(signerA) || !_guardian(signerB)) {
            revert RecoverySignature();
        }
        assembly { sstore(nonceSlot, add(currentNonce, 1)) }
    }

    function _transferFull(address token, uint256 amount) private {
        uint256 safeBefore = _balance(token, SAFE);
        if (token == address(0)) {
            (bool success,) = SAFE.call{value: amount}("");
            if (!success) revert RecoveryTransferFailed();
        } else {
            (bool success, bytes memory result) = token.call(abi.encodeWithSelector(
                IERC20RecoveryToken.transfer.selector, SAFE, amount
            ));
            if (!success || (result.length != 0
                && (result.length != 32 || abi.decode(result, (uint256)) != 1))) {
                revert RecoveryTransferFailed();
            }
        }
        if (_balance(token, SAFE) != safeBefore + amount
            || _balance(token, address(this)) != 0) revert RecoveryTransferFailed();
    }

    function _guardian(address signer) private view returns (bool) {
        return signer == GUARDIAN_A || signer == GUARDIAN_B || signer == GUARDIAN_C;
    }

    function _recoveryDigest(address token, uint256 amount, uint256 nonce, uint256 deadline)
        private view returns (bytes32)
    {
        return keccak256(abi.encodePacked(
            "\x19\x01", recoveryDomainSeparator(),
            keccak256(abi.encode(
                RECOVERY_TYPEHASH, address(this), SAFE, token, amount, nonce, deadline
            ))
        ));
    }

    function _balance(address token, address account) private view returns (uint256) {
        if (token == address(0)) return account.balance;
        (bool success, bytes memory result) = token.staticcall(abi.encodeWithSelector(
            IERC20RecoveryToken.balanceOf.selector, account
        ));
        if (!success || result.length != 32) revert RecoveryTransferFailed();
        return abi.decode(result, (uint256));
    }

    function _recoverGuardian(bytes32 digest, bytes calldata signature)
        private pure returns (address signer)
    {
        if (signature.length != 65) revert RecoverySignature();
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        if (uint256(s) > SECP256K1N_HALF || v < 27 || v > 28) {
            revert RecoverySignature();
        }
        signer = ecrecover(digest, v, r, s);
        if (signer == address(0)) revert RecoverySignature();
    }
}
