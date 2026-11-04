const assert = require("node:assert/strict");
const { ethers, network } = require("hardhat");

const TYPES = {
  Recovery: [
    { name: "authority", type: "address" },
    { name: "safe", type: "address" },
    { name: "token", type: "address" },
    { name: "amount", type: "uint256" },
    { name: "nonce", type: "uint256" },
    { name: "deadline", type: "uint256" }
  ]
};
const PAYOUT_ITEM_TYPES = { PayoutItem: [
  { name: "withdrawalId", type: "bytes32" },
  { name: "itemIndex", type: "uint256" },
  { name: "token", type: "address" },
  { name: "recipient", type: "address" },
  { name: "amount", type: "uint256" },
  { name: "callGasLimit", type: "uint256" }
] };
const PAYOUT_REQUEST_TYPES = { PayoutRequest: [
  { name: "batchId", type: "bytes32" },
  { name: "authority", type: "address" },
  { name: "executor", type: "address" },
  { name: "itemsHash", type: "bytes32" },
  { name: "operationNonce", type: "uint256" },
  { name: "deadline", type: "uint256" }
] };

describe("preinstalled EIP-7702 two-guardian recovery", function () {
  async function fixture() {
    const [executor, safe, guardianA, guardianB, guardianC, outsider, recipient] =
      await ethers.getSigners();
    const hotWallet = ethers.Wallet.createRandom();
    const Delegate = await ethers.getContractFactory("Eip7702PayoutRecoveryDelegate");
    const delegate = await Delegate.deploy(executor.address, safe.address,
      [guardianA.address, guardianB.address, guardianC.address]);
    await delegate.waitForDeployment();
    await network.provider.send("hardhat_setCode", [
      hotWallet.address, `0xef0100${(await delegate.getAddress()).slice(2).toLowerCase()}`
    ]);
    const Token = await ethers.getContractFactory("MockERC20");
    const token = await Token.deploy("Test USD", "TUSD", 6);
    await token.waitForDeployment();
    const account = delegate.attach(hotWallet.address);
    return { executor, safe, guardianA, guardianB, guardianC, outsider, recipient,
      hotWallet, delegate, account, token };
  }

  async function signed(f, token, amount, nonce = 0n, deadlineOffset = 600) {
    const block = await ethers.provider.getBlock("latest");
    const deadline = BigInt(block.timestamp + deadlineOffset);
    const value = { authority: f.hotWallet.address, safe: f.safe.address,
      token, amount, nonce, deadline };
    const domain = { name: "SurprisingWallet7702Recovery", version: "1",
      chainId: (await ethers.provider.getNetwork()).chainId,
      verifyingContract: f.hotWallet.address };
    return { deadline,
      signatureA: await f.guardianA.signTypedData(domain, TYPES, value),
      signatureB: await f.guardianB.signTypedData(domain, TYPES, value),
      outsiderSignature: await f.outsider.signTypedData(domain, TYPES, value) };
  }

  it("moves all native balance and one token to the fixed safe with two guardians", async function () {
    const f = await fixture();
    const nativeAmount = ethers.parseEther("2");
    await network.provider.send("hardhat_setBalance",
      [f.hotWallet.address, ethers.toBeHex(nativeAmount)]);
    await f.token.mint(f.hotWallet.address, 5_000_000n);
    const native = await signed(f, ethers.ZeroAddress, nativeAmount);
    const safeBefore = await ethers.provider.getBalance(f.safe.address);
    await (await f.account.connect(f.outsider).emergencySweep(ethers.ZeroAddress,
      nativeAmount, 0n, native.deadline, native.signatureA, native.signatureB)).wait();
    assert.equal(await ethers.provider.getBalance(f.hotWallet.address), 0n);
    assert.equal(await ethers.provider.getBalance(f.safe.address), safeBefore + nativeAmount);
    assert.equal(await f.account.operationNonce(), ethers.MaxUint256);
    const item = { withdrawalId: ethers.keccak256(ethers.toUtf8Bytes("stale-payout")),
      itemIndex: 0n, token: await f.token.getAddress(), recipient: f.recipient.address,
      amount: 1n, callGasLimit: 120_000n };
    const batchId = ethers.keccak256(ethers.toUtf8Bytes("stale-batch"));
    const itemHash = ethers.TypedDataEncoder.hashStruct("PayoutItem", PAYOUT_ITEM_TYPES, item);
    const batchSignature = await f.hotWallet.signTypedData({
      name: "SurprisingWallet7702Payout", version: "1",
      chainId: (await ethers.provider.getNetwork()).chainId,
      verifyingContract: f.hotWallet.address
    }, PAYOUT_REQUEST_TYPES, {
      batchId, authority: f.hotWallet.address, executor: f.executor.address,
      itemsHash: ethers.keccak256(itemHash), operationNonce: ethers.MaxUint256,
      deadline: native.deadline
    });
    await assert.rejects(f.account.connect(f.executor).payoutBatch(batchId, [item],
      ethers.MaxUint256, native.deadline, batchSignature), /panic code 0x11/);
    const tokenAddress = await f.token.getAddress();
    const tokenApproval = await signed(f, tokenAddress, 5_000_000n, 1n);
    await (await f.account.connect(f.outsider).emergencySweep(tokenAddress, 5_000_000n,
      1n, tokenApproval.deadline, tokenApproval.signatureA, tokenApproval.signatureB)).wait();
    assert.equal(await f.token.balanceOf(f.hotWallet.address), 0n);
    assert.equal(await f.token.balanceOf(f.safe.address), 5_000_000n);
    assert.equal(await f.account.recoveryNonce(), 2n);
  });

  it("rejects one guardian, outsiders, replay, stale amount and altered destination", async function () {
    const f = await fixture();
    await f.token.mint(f.hotWallet.address, 4_000_000n);
    const tokenAddress = await f.token.getAddress();
    const approval = await signed(f, tokenAddress, 4_000_000n);
    const sweep = (amount, nonce, a, b) => f.account.connect(f.outsider).emergencySweep(
      tokenAddress, amount, nonce, approval.deadline, a, b);
    await assert.rejects(sweep(4_000_000n, 0n, approval.signatureA, approval.signatureA));
    await assert.rejects(sweep(4_000_000n, 0n, approval.signatureA, approval.outsiderSignature));
    await assert.rejects(sweep(3_000_000n, 0n, approval.signatureA, approval.signatureB));
    const expired = await signed(f, tokenAddress, 4_000_000n, 0n, -1);
    await assert.rejects(f.account.emergencySweep(tokenAddress, 4_000_000n, 0n,
      expired.deadline, expired.signatureA, expired.signatureB));
    const tooLong = await signed(f, tokenAddress, 4_000_000n, 0n, 3600);
    await assert.rejects(f.account.emergencySweep(tokenAddress, 4_000_000n, 0n,
      tooLong.deadline, tooLong.signatureA, tooLong.signatureB));
    await f.token.mint(f.hotWallet.address, 1n);
    await assert.rejects(sweep(4_000_000n, 0n, approval.signatureA, approval.signatureB));
    assert.equal(await f.account.recoveryNonce(), 0n);
    assert.equal(await f.token.balanceOf(f.hotWallet.address), 4_000_001n);
    const fresh = await signed(f, tokenAddress, 4_000_001n);
    await (await f.account.connect(f.outsider).emergencySweep(tokenAddress, 4_000_001n,
      0n, fresh.deadline, fresh.signatureA, fresh.signatureB)).wait();
    await assert.rejects(f.account.connect(f.outsider).emergencySweep(tokenAddress, 4_000_001n,
      0n, fresh.deadline, fresh.signatureA, fresh.signatureB));
    assert.equal(await f.token.balanceOf(f.recipient.address), 0n);
  });
});
