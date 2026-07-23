// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/** Test-only receiver used to prove per-item native payout retry behavior. */
contract TestPayoutReceiver {
    bool public accepting;

    function setAccepting(bool value) external {
        accepting = value;
    }

    receive() external payable {
        require(accepting, "native payout rejected");
    }
}
