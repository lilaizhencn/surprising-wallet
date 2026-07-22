const assert = require("node:assert/strict");
const { ethers, network } = require("hardhat");

const ITEM_TYPES = {
  PayoutItem: [
    { name: "withdrawalId", type: "bytes32" },
    { name: "itemIndex", type: "uint256" },
    { name: "token", type: "address" },
    { name: "recipient", type: "address" },
    { name: "amount", type: "uint256" },
    { name: "callGasLimit", type: "uint256" }
  ]
};

const REQUEST_TYPES = {
  PayoutRequest: [
    { name: "batchId", type: "bytes32" },
    { name: "authority", type: "address" },
    { name: "executor", type: "address" },
    { name: "itemsHash", type: "bytes32" },
    { name: "operationNonce", type: "uint256" },
    { name: "deadline", type: "uint256" }
  ]
};

function delegationCode(delegateAddress) {
  return `0xef0100${delegateAddress.slice(2).toLowerCase()}`;
}

function withdrawalId(value) {
  return ethers.keccak256(ethers.toUtf8Bytes(value));
}

function itemsHash(items) {
  const hashes = items.map((item) => ethers.TypedDataEncoder.hashStruct("PayoutItem", ITEM_TYPES, item));
  return ethers.keccak256(ethers.concat(hashes));
}

describe("EIP-7702 batch payout", function () {
  async function fixture() {
    const [admin, relayer, recipientA, recipientB, recipientC, outsider] = await ethers.getSigners();
    const Delegate = await ethers.getContractFactory("Eip7702PayoutDelegate");
    const delegate = await Delegate.deploy(relayer.address);
    await delegate.waitForDeployment();
    const Token = await ethers.getContractFactory("MockERC20");
    const token = await Token.deploy("Test USD", "TUSD", 6);
    await token.waitForDeployment();
    const hotWallet = ethers.Wallet.createRandom();
    await network.provider.send("hardhat_setCode", [
      hotWallet.address,
      delegationCode(await delegate.getAddress())
    ]);
    return { admin, relayer, recipientA, recipientB, recipientC, outsider, hotWallet, delegate, token };
  }

  async function signBatch(hotWallet, relayer, batchId, items, nonce = 0n) {
    const latest = await ethers.provider.getBlock("latest");
    const deadline = BigInt(latest.timestamp + 600);
    const chainId = (await ethers.provider.getNetwork()).chainId;
    const request = {
      batchId,
      authority: hotWallet.address,
      executor: relayer.address,
      itemsHash: itemsHash(items),
      operationNonce: nonce,
      deadline
    };
    const signature = await hotWallet.signTypedData({
      name: "SurprisingWallet7702Payout",
      version: "1",
      chainId,
      verifyingContract: hotWallet.address
    }, REQUEST_TYPES, request);
    return { deadline, signature };
  }

  it("pays native currency and tokens from one tenant hot wallet in one transaction", async function () {
    const { relayer, recipientA, recipientB, recipientC, hotWallet, delegate, token } = await fixture();
    await network.provider.send("hardhat_setBalance", [hotWallet.address, ethers.toBeHex(ethers.parseEther("5"))]);
    await token.mint(hotWallet.address, 50_000_000n);
    const batchId = ethers.keccak256(ethers.toUtf8Bytes("tenant-a:payout-1"));
    const items = [
      {
        withdrawalId: withdrawalId("withdrawal-a"), itemIndex: 0n, token: ethers.ZeroAddress,
        recipient: recipientA.address, amount: ethers.parseEther("1.25"), callGasLimit: 100_000n
      },
      {
        withdrawalId: withdrawalId("withdrawal-b"), itemIndex: 1n, token: await token.getAddress(),
        recipient: recipientB.address, amount: 20_000_000n, callGasLimit: 120_000n
      },
      {
        withdrawalId: withdrawalId("withdrawal-c"), itemIndex: 2n, token: ethers.ZeroAddress,
        recipient: recipientC.address, amount: ethers.parseEther("0.75"), callGasLimit: 100_000n
      }
    ];
    const signed = await signBatch(hotWallet, relayer, batchId, items);
    const aBefore = await ethers.provider.getBalance(recipientA.address);
    const cBefore = await ethers.provider.getBalance(recipientC.address);
    const receipt = await (await delegate.attach(hotWallet.address).connect(relayer).payoutBatch(
      batchId, items, 0n, signed.deadline, signed.signature
    )).wait();

    assert.equal(receipt.status, 1);
    assert.equal(await ethers.provider.getBalance(recipientA.address), aBefore + ethers.parseEther("1.25"));
    assert.equal(await ethers.provider.getBalance(recipientC.address), cBefore + ethers.parseEther("0.75"));
    assert.equal(await token.balanceOf(recipientB.address), 20_000_000n);
    assert.equal(await token.balanceOf(hotWallet.address), 30_000_000n);
    assert.equal(await delegate.attach(hotWallet.address).operationNonce(), 1n);
    const events = receipt.logs
      .map((log) => { try { return delegate.interface.parseLog(log); } catch { return null; } })
      .filter((event) => event && event.name === "PayoutItemResult");
    assert.equal(events.length, 3);
    assert(events.every((event) => event.args.success));
  });

  it("isolates a failed item, continues later payouts, and rejects replay", async function () {
    const { relayer, recipientA, recipientB, recipientC, outsider, hotWallet, delegate, token } = await fixture();
    await network.provider.send("hardhat_setBalance", [hotWallet.address, ethers.toBeHex(ethers.parseEther("1"))]);
    await token.mint(hotWallet.address, 10_000_000n);
    const batchId = ethers.keccak256(ethers.toUtf8Bytes("tenant-a:payout-partial"));
    const items = [
      {
        withdrawalId: withdrawalId("withdrawal-good-token"), itemIndex: 0n, token: await token.getAddress(),
        recipient: recipientA.address, amount: 10_000_000n, callGasLimit: 120_000n
      },
      {
        withdrawalId: withdrawalId("withdrawal-bad-token"), itemIndex: 1n, token: await token.getAddress(),
        recipient: recipientB.address, amount: 1n, callGasLimit: 120_000n
      },
      {
        withdrawalId: withdrawalId("withdrawal-good-native"), itemIndex: 2n, token: ethers.ZeroAddress,
        recipient: recipientC.address, amount: ethers.parseEther("0.5"), callGasLimit: 100_000n
      }
    ];
    const signed = await signBatch(hotWallet, relayer, batchId, items);
    await assert.rejects(delegate.attach(hotWallet.address).connect(outsider).payoutBatch(
      batchId, items, 0n, signed.deadline, signed.signature
    ));
    const nativeBefore = await ethers.provider.getBalance(recipientC.address);
    const receipt = await (await delegate.attach(hotWallet.address).connect(relayer).payoutBatch(
      batchId, items, 0n, signed.deadline, signed.signature
    )).wait();
    const events = receipt.logs
      .map((log) => { try { return delegate.interface.parseLog(log); } catch { return null; } })
      .filter((event) => event && event.name === "PayoutItemResult");
    assert.deepEqual(events.map((event) => event.args.success), [true, false, true]);
    assert.equal(await token.balanceOf(recipientA.address), 10_000_000n);
    assert.equal(await token.balanceOf(recipientB.address), 0n);
    assert.equal(await ethers.provider.getBalance(recipientC.address), nativeBefore + ethers.parseEther("0.5"));
    assert.equal(await delegate.attach(hotWallet.address).operationNonce(), 1n);
    await assert.rejects(delegate.attach(hotWallet.address).connect(relayer).payoutBatch(
      batchId, items, 0n, signed.deadline, signed.signature
    ));
  });
});
