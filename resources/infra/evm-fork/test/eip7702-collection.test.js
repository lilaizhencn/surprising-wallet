const assert = require("node:assert/strict");
const { ethers, network } = require("hardhat");

const REQUEST_TYPES = {
  CollectionRequest: [
    { name: "batchId", type: "bytes32" },
    { name: "itemIndex", type: "uint256" },
    { name: "authority", type: "address" },
    { name: "collector", type: "address" },
    { name: "token", type: "address" },
    { name: "recipient", type: "address" },
    { name: "amount", type: "uint256" },
    { name: "operationNonce", type: "uint256" },
    { name: "deadline", type: "uint256" },
    { name: "callGasLimit", type: "uint256" }
  ]
};

function delegationCode(delegateAddress) {
  return `0xef0100${delegateAddress.slice(2).toLowerCase()}`;
}

describe("EIP-7702 native and ERC-20 collection", function () {
  async function fixture() {
    const [admin, relayer, hotWallet, outsider] = await ethers.getSigners();
    const Collector = await ethers.getContractFactory("Eip7702BatchCollector");
    const collector = await Collector.deploy(admin.address, relayer.address);
    await collector.waitForDeployment();
    const Delegate = await ethers.getContractFactory("Eip7702CollectionDelegate");
    const delegate = await Delegate.deploy(await collector.getAddress());
    await delegate.waitForDeployment();
    const Token = await ethers.getContractFactory("MockERC20");
    const token = await Token.deploy("Test USD", "TUSD", 6);
    await token.waitForDeployment();
    return { admin, relayer, hotWallet, outsider, collector, delegate, token };
  }

  it("collects several zero-ETH EOAs in one batch transaction", async function () {
    const { relayer, hotWallet, collector, delegate, token } = await fixture();
    const authorities = [ethers.Wallet.createRandom(), ethers.Wallet.createRandom(), ethers.Wallet.createRandom()];
    const amounts = [11_000_000n, 22_000_000n, 33_000_000n];
    const delegateAddress = await delegate.getAddress();
    const collectorAddress = await collector.getAddress();
    const tokenAddress = await token.getAddress();
    for (let i = 0; i < authorities.length; i++) {
      await token.mint(authorities[i].address, amounts[i]);
      await network.provider.send("hardhat_setCode", [authorities[i].address, delegationCode(delegateAddress)]);
      assert.equal(await ethers.provider.getBalance(authorities[i].address), 0n);
    }

    const latest = await ethers.provider.getBlock("latest");
    const batchId = ethers.keccak256(ethers.toUtf8Bytes("tenant-a:batch-1"));
    const deadline = BigInt(latest.timestamp + 600);
    const chainId = (await ethers.provider.getNetwork()).chainId;
    const requests = authorities.map((authority, index) => ({
      batchId,
      itemIndex: BigInt(index),
      authority: authority.address,
      collector: collectorAddress,
      token: tokenAddress,
      recipient: hotWallet.address,
      amount: amounts[index],
      operationNonce: 0n,
      deadline,
      callGasLimit: 180_000n
    }));
    const signatures = [];
    for (let i = 0; i < authorities.length; i++) {
      signatures.push(await authorities[i].signTypedData({
        name: "SurprisingWallet7702Collection",
        version: "1",
        chainId,
        verifyingContract: authorities[i].address
      }, REQUEST_TYPES, requests[i]));
    }

    const tx = await collector.connect(relayer).collectBatch(requests, signatures);
    const receipt = await tx.wait();
    assert.equal(receipt.status, 1);
    assert.equal(await token.balanceOf(hotWallet.address), amounts.reduce((a, b) => a + b, 0n));
    for (const authority of authorities) {
      assert.equal(await token.balanceOf(authority.address), 0n);
      assert.equal(await delegate.attach(authority.address).operationNonce(), 1n);
      assert.equal(await ethers.provider.getBalance(authority.address), 0n);
    }
    const itemEvents = receipt.logs
      .map((log) => { try { return collector.interface.parseLog(log); } catch { return null; } })
      .filter((event) => event && event.name === "CollectionItemResult");
    assert.equal(itemEvents.length, authorities.length);
    assert(itemEvents.every((event) => event.args.success));
  });

  it("collects native assets from several EOAs while the relayer pays gas", async function () {
    const { relayer, hotWallet, collector, delegate } = await fixture();
    const authorities = [ethers.Wallet.createRandom(), ethers.Wallet.createRandom(), ethers.Wallet.createRandom()];
    const amounts = [ethers.parseEther("1.25"), ethers.parseEther("2.5"), ethers.parseEther("3.75")];
    const delegateAddress = await delegate.getAddress();
    const collectorAddress = await collector.getAddress();
    for (let i = 0; i < authorities.length; i++) {
      await network.provider.send("hardhat_setBalance", [authorities[i].address, ethers.toBeHex(amounts[i])]);
      await network.provider.send("hardhat_setCode", [authorities[i].address, delegationCode(delegateAddress)]);
    }

    const latest = await ethers.provider.getBlock("latest");
    const batchId = ethers.keccak256(ethers.toUtf8Bytes("tenant-a:native-batch-1"));
    const deadline = BigInt(latest.timestamp + 600);
    const chainId = (await ethers.provider.getNetwork()).chainId;
    const requests = authorities.map((authority, index) => ({
      batchId,
      itemIndex: BigInt(index),
      authority: authority.address,
      collector: collectorAddress,
      token: ethers.ZeroAddress,
      recipient: hotWallet.address,
      amount: amounts[index],
      operationNonce: 0n,
      deadline,
      callGasLimit: 180_000n
    }));
    const signatures = await Promise.all(authorities.map((authority, index) => authority.signTypedData({
      name: "SurprisingWallet7702Collection",
      version: "1",
      chainId,
      verifyingContract: authority.address
    }, REQUEST_TYPES, requests[index])));

    const hotBalanceBefore = await ethers.provider.getBalance(hotWallet.address);
    const receipt = await (await collector.connect(relayer).collectBatch(requests, signatures)).wait();
    const expected = amounts.reduce((left, right) => left + right, 0n);
    assert.equal(receipt.status, 1);
    assert.equal(await ethers.provider.getBalance(hotWallet.address), hotBalanceBefore + expected);
    for (const authority of authorities) {
      assert.equal(await ethers.provider.getBalance(authority.address), 0n);
      assert.equal(await delegate.attach(authority.address).operationNonce(), 1n);
    }
    const itemEvents = receipt.logs
      .map((log) => { try { return collector.interface.parseLog(log); } catch { return null; } })
      .filter((event) => event && event.name === "CollectionItemResult");
    assert.equal(itemEvents.length, authorities.length);
    assert(itemEvents.every((event) => event.args.token === ethers.ZeroAddress));
    assert(itemEvents.every((event) => event.args.success));
  });

  it("isolates one failed item and rejects unauthorized relayers", async function () {
    const { relayer, hotWallet, outsider, collector, delegate, token } = await fixture();
    const good = ethers.Wallet.createRandom();
    const bad = ethers.Wallet.createRandom();
    for (const authority of [good, bad]) {
      await network.provider.send("hardhat_setCode", [authority.address, delegationCode(await delegate.getAddress())]);
    }
    await token.mint(good.address, 10n);
    await token.mint(bad.address, 5n);
    const latest = await ethers.provider.getBlock("latest");
    const chainId = (await ethers.provider.getNetwork()).chainId;
    const batchId = ethers.keccak256(ethers.toUtf8Bytes("tenant-a:partial"));
    const requests = [good, bad].map((authority, index) => ({
      batchId,
      itemIndex: BigInt(index),
      authority: authority.address,
      collector: collector.target,
      token: token.target,
      recipient: hotWallet.address,
      amount: 10n,
      operationNonce: 0n,
      deadline: BigInt(latest.timestamp + 600),
      callGasLimit: 180_000n
    }));
    const signatures = await Promise.all([good, bad].map((authority, index) => authority.signTypedData({
      name: "SurprisingWallet7702Collection",
      version: "1",
      chainId,
      verifyingContract: authority.address
    }, REQUEST_TYPES, requests[index])));

    await assert.rejects(collector.connect(outsider).collectBatch(requests, signatures));
    const receipt = await (await collector.connect(relayer).collectBatch(requests, signatures)).wait();
    const itemEvents = receipt.logs
      .map((log) => { try { return collector.interface.parseLog(log); } catch { return null; } })
      .filter((event) => event && event.name === "CollectionItemResult");
    assert.deepEqual(itemEvents.map((event) => event.args.success), [true, false]);
    assert.equal(await token.balanceOf(hotWallet.address), 10n);
    assert.equal(await token.balanceOf(good.address), 0n);
    assert.equal(await token.balanceOf(bad.address), 5n);
  });
});
