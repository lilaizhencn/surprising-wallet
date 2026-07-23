import express from 'express';
import { ApiPromise, WsProvider } from '@polkadot/api';
import { Keyring } from '@polkadot/keyring';
import { hexToU8a, u8aToHex } from '@polkadot/util';
import { cryptoWaitReady, decodeAddress, encodeAddress } from '@polkadot/util-crypto';

const PORT = Number(process.env.PORT || process.env.POLKADOT_RUNTIME_PORT || 8787);
const HOST = process.env.HOST || process.env.POLKADOT_RUNTIME_HOST || '127.0.0.1';
const API_KEY = (process.env.POLKADOT_RUNTIME_API_KEY || '').trim();
const DEV_MODE = String(process.env.POLKADOT_RUNTIME_DEV_MODE || '').toLowerCase() === 'true';
const API_CONNECT_TIMEOUT_MS = Number(process.env.POLKADOT_RUNTIME_CONNECT_TIMEOUT_MS || 20000);
const apiCache = new Map();

await cryptoWaitReady();

const app = express();
app.use(express.json({ limit: '256kb' }));
app.use((error, _req, res, next) => {
  if (error instanceof SyntaxError && 'body' in error) {
    return res.status(400).json({ ok: false, error: 'invalid JSON request body' });
  }
  return next(error);
});
app.use((req, res, next) => {
  if (req.path === '/health') {
    return next();
  }
  if (!API_KEY) {
    return next();
  }
  const authorization = req.get('authorization') || '';
  const apiKey = req.get('x-api-key') || '';
  if (authorization === `Bearer ${API_KEY}` || apiKey === API_KEY) {
    return next();
  }
  return res.status(401).json({ ok: false, error: 'unauthorized' });
});

app.get('/health', async (_req, res) => {
  res.json({ ok: true, service: 'polkadot-runtime-service' });
});

app.post('/v1/polkadot/latest-finalized', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  const hash = await api.rpc.chain.getFinalizedHead();
  const header = await api.rpc.chain.getHeader(hash);
  return { hash: hash.toHex(), height: header.number.toNumber() };
}));

app.post('/v1/polkadot/native-balance', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  const account = await api.query.system.account(requireString(req.body.address, 'address'));
  return {
    free: account.data.free.toString(),
    reserved: account.data.reserved.toString(),
    frozen: account.data.frozen ? account.data.frozen.toString() : '0'
  };
}));

app.post('/v1/polkadot/asset-info', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  if (!api.query.assets) {
    throw new Error('assets pallet is not available on this runtime');
  }
  const assetId = requireString(req.body.assetId, 'assetId');
  const asset = await api.query.assets.asset(assetId);
  const metadata = await api.query.assets.metadata(assetId);
  const exists = asset && asset.isSome;
  return {
    assetId,
    exists,
    supply: exists ? asset.unwrap().supply.toString() : '0',
    minBalance: exists ? asset.unwrap().minBalance.toString() : '0',
    isSufficient: exists ? asset.unwrap().isSufficient.toString() === 'true' : false,
    name: metadata.name.toUtf8 ? metadata.name.toUtf8() : metadata.name.toString(),
    symbol: metadata.symbol.toUtf8 ? metadata.symbol.toUtf8() : metadata.symbol.toString(),
    decimals: metadata.decimals.toNumber ? metadata.decimals.toNumber() : Number(metadata.decimals.toString() || 0)
  };
}));

app.post('/v1/polkadot/asset-balance', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  if (!api.query.assets) {
    throw new Error('assets pallet is not available on this runtime');
  }
  const assetId = requireString(req.body.assetId, 'assetId');
  const account = await api.query.assets.account(assetId, requireString(req.body.address, 'address'));
  if (!account || account.isNone) {
    return { assetId, balance: '0', status: 'NONE' };
  }
  const data = account.unwrap();
  return {
    assetId,
    balance: data.balance.toString(),
    status: data.status ? data.status.toString() : 'UNKNOWN'
  };
}));

app.post('/v1/polkadot/scan-transfers', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  const ss58Prefix = Number(req.body.ss58Prefix ?? 42);
  const fromBlock = Number(req.body.fromBlock);
  const toBlock = Number(req.body.toBlock);
  const includeNative = req.body.includeNative !== false;
  const includeAssets = req.body.includeAssets !== false;
  if (!Number.isSafeInteger(fromBlock) || !Number.isSafeInteger(toBlock) || fromBlock < 0 || toBlock < fromBlock) {
    throw new Error('invalid block range');
  }
  const addressBook = addressBookFrom(req.body.addresses || []);
  const assetIds = new Set((req.body.assetIds || []).map((item) => String(item)));
  const transfers = [];
  for (let height = fromBlock; height <= toBlock; height += 1) {
    const hash = await api.rpc.chain.getBlockHash(height);
    const [block, events] = await Promise.all([
      api.rpc.chain.getBlock(hash),
      api.query.system.events.at(hash)
    ]);
    const extrinsics = block.block.extrinsics;
    for (let eventIndex = 0; eventIndex < events.length; eventIndex += 1) {
      const record = events[eventIndex];
      const { event, phase } = record;
      if (includeNative && event.section === 'balances' && event.method === 'Transfer') {
        const [from, to, amount] = event.data;
        const toKey = accountKey(to.toString());
        const trackedTo = addressBook.get(toKey);
        const extrinsicIndex = applyExtrinsicIndex(phase);
        if (!trackedTo || !extrinsicSucceeded(events, extrinsicIndex)) {
          continue;
        }
        transfers.push({
          txHash: txHashFor(extrinsics, extrinsicIndex, hash, eventIndex),
          from: encodeAddress(decodeAddress(from.toString()), ss58Prefix),
          to: trackedTo,
          amountPlanck: amount.toString(),
          blockHeight: height,
          eventIndex,
          assetId: null
        });
      }
      if (includeNative && event.section === 'balances' && event.method === 'Deposit') {
        const [to, amount] = event.data;
        const toKey = accountKey(to.toString());
        const trackedTo = addressBook.get(toKey);
        const extrinsicIndex = applyExtrinsicIndex(phase);
        if (!trackedTo || !extrinsicSucceeded(events, extrinsicIndex)) {
          continue;
        }
        transfers.push({
          txHash: txHashFor(extrinsics, extrinsicIndex, hash, eventIndex),
          from: '',
          to: trackedTo,
          amountPlanck: amount.toString(),
          blockHeight: height,
          eventIndex,
          assetId: null
        });
      }
      if (includeAssets && event.section === 'assets' && event.method === 'Transferred') {
        const [assetId, from, to, amount] = event.data;
        const normalizedAssetId = assetId.toString();
        if (assetIds.size > 0 && !assetIds.has(normalizedAssetId)) {
          continue;
        }
        const toKey = accountKey(to.toString());
        const trackedTo = addressBook.get(toKey);
        const extrinsicIndex = applyExtrinsicIndex(phase);
        if (!trackedTo || !extrinsicSucceeded(events, extrinsicIndex)) {
          continue;
        }
        transfers.push({
          txHash: txHashFor(extrinsics, extrinsicIndex, hash, eventIndex),
          from: encodeAddress(decodeAddress(from.toString()), ss58Prefix),
          to: trackedTo,
          amountPlanck: amount.toString(),
          blockHeight: height,
          eventIndex,
          assetId: normalizedAssetId
        });
      }
    }
  }
  return { transfers };
}));

app.post('/v1/polkadot/transfer', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  const pair = pairFromSeed(req.body.secretSeedHex, Number(req.body.ss58Prefix ?? 42));
  assertExpectedAddress(pair.address, req.body.expectedFrom);
  const keepAlive = req.body.keepAlive !== false;
  const tx = keepAlive && api.tx.balances.transferKeepAlive
    ? api.tx.balances.transferKeepAlive(requireString(req.body.to, 'to'), requireString(req.body.amountPlanck, 'amountPlanck'))
    : transferAllowDeath(api, requireString(req.body.to, 'to'), requireString(req.body.amountPlanck, 'amountPlanck'));
  return submitAndWait(api, tx, pair, req.body.waitFinalized !== false);
}));

app.post('/v1/polkadot/dev-fund', handler(async (req) => {
  if (!DEV_MODE) {
    throw new Error('dev funding is disabled');
  }
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  const ss58Prefix = Number(req.body.ss58Prefix ?? 42);
  const alice = new Keyring({ type: 'sr25519', ss58Format: ss58Prefix }).addFromUri('//Alice');
  const to = requireString(req.body.to, 'to');
  const amountPlanck = requireString(req.body.amountPlanck, 'amountPlanck');
  const tx = transferAllowDeath(api, to, amountPlanck);
  return submitAndWait(api, tx, alice, true);
}));

app.post('/v1/polkadot/asset-transfer', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  if (!api.tx.assets) {
    throw new Error('assets pallet is not available on this runtime');
  }
  const pair = pairFromSeed(req.body.secretSeedHex, Number(req.body.ss58Prefix ?? 42));
  assertExpectedAddress(pair.address, req.body.expectedFrom);
  const assetId = requireString(req.body.assetId, 'assetId');
  const to = requireString(req.body.to, 'to');
  const amount = requireString(req.body.amount, 'amount');
  const keepAlive = req.body.keepAlive !== false;
  const tx = assetTransfer(api, assetId, to, amount, keepAlive);
  return submitAndWait(api, tx, pair, req.body.waitFinalized !== false);
}));

app.post('/v1/polkadot/asset-create', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  if (!api.tx.assets) {
    throw new Error('assets pallet is not available on this runtime');
  }
  if (!api.tx.utility || !api.tx.utility.batchAll) {
    throw new Error('utility.batchAll is required for atomic asset deployment');
  }
  const ss58Prefix = Number(req.body.ss58Prefix ?? 42);
  const pair = pairFromSeed(req.body.secretSeedHex, ss58Prefix);
  assertExpectedAddress(pair.address, req.body.expectedFrom);
  const assetId = requireString(req.body.assetId, 'assetId');
  const name = requireString(req.body.name, 'name');
  const symbol = requireString(req.body.symbol, 'symbol');
  const decimals = Number(req.body.decimals ?? 0);
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 18) {
    throw new Error('decimals must be between 0 and 18');
  }
  const minBalance = requireString(req.body.minBalance, 'minBalance');
  const initialSupply = String(req.body.initialSupply ?? '0');
  const mintable = req.body.mintable !== false;
  const existing = await api.query.assets.asset(assetId);
  if (existing && existing.isSome) {
    throw new Error(`Asset Hub asset already exists: ${assetId}`);
  }
  const calls = [
    api.tx.assets.create(assetId, pair.address, minBalance),
    api.tx.assets.setMetadata(assetId, name, symbol, decimals)
  ];
  if (BigInt(initialSupply) > 0n) {
    calls.push(api.tx.assets.mint(assetId, pair.address, initialSupply));
  }
  if (!mintable && !api.tx.assets.setTeam) {
    throw new Error('assets.setTeam is required when mintable is false');
  }
  if (!mintable) {
    calls.push(api.tx.assets.setTeam(assetId, deadAddress(ss58Prefix), pair.address, pair.address));
  }
  const tx = api.tx.utility.batchAll(calls);
  const submitted = await submitAndWait(api, tx, pair, req.body.waitFinalized !== false);
  return { ...submitted, assetId, owner: pair.address, name, symbol, decimals, minBalance, initialSupply };
}));

app.post('/v1/polkadot/transaction-status', handler(async (req) => {
  const api = await apiFor(requireString(req.body.rpcUrl, 'rpcUrl'));
  const txHash = requireString(req.body.txHash, 'txHash').toLowerCase();
  const maxRecentBlocks = Math.min(Number(req.body.maxRecentBlocks || 512), 5000);
  const finalizedHash = await api.rpc.chain.getFinalizedHead();
  const finalizedHeader = await api.rpc.chain.getHeader(finalizedHash);
  const latest = finalizedHeader.number.toNumber();
  const min = Math.max(0, latest - maxRecentBlocks + 1);
  for (let height = latest; height >= min; height -= 1) {
    const hash = await api.rpc.chain.getBlockHash(height);
    const block = await api.rpc.chain.getBlock(hash);
    for (const extrinsic of block.block.extrinsics) {
      if (extrinsic.hash.toHex().toLowerCase() === txHash) {
        return { finalized: true, blockHeight: height, blockHash: hash.toHex() };
      }
    }
  }
  return { finalized: false, latestFinalizedHeight: latest };
}));

const server = app.listen(PORT, HOST, () => {
  console.log(`polkadot-runtime-service listening on ${HOST}:${PORT}`);
});

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

async function apiFor(rpcUrl) {
  if (!apiCache.has(rpcUrl)) {
    const provider = new WsProvider(rpcUrl);
    const apiPromise = withTimeout(ApiPromise.create({ provider }), API_CONNECT_TIMEOUT_MS,
      `Polkadot RPC connection timeout: ${rpcUrl}`)
      .catch((error) => {
        apiCache.delete(rpcUrl);
        Promise.resolve(provider.disconnect()).catch(() => {});
        throw error;
      });
    apiCache.set(rpcUrl, apiPromise);
  }
  return apiCache.get(rpcUrl);
}

function withTimeout(promise, timeoutMs, message) {
  let timer = null;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => {
    if (timer) {
      clearTimeout(timer);
    }
  });
}

async function shutdown(signal) {
  console.log(`polkadot-runtime-service shutting down after ${signal}`);
  server.close(async () => {
    await Promise.allSettled([...apiCache.values()].map(async (apiPromise) => {
      const api = await apiPromise;
      return api.disconnect();
    }));
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10000).unref();
}

function handler(fn) {
  return async (req, res) => {
    try {
      const result = await fn(req);
      res.json({ ok: true, result });
    } catch (error) {
      res.status(400).json({ ok: false, error: errorMessage(error) });
    }
  };
}

function pairFromSeed(secretSeedHex, ss58Prefix) {
  const keyring = new Keyring({ type: 'ed25519', ss58Format: ss58Prefix });
  return keyring.addFromSeed(hexToU8a(normalizeHex(requireString(secretSeedHex, 'secretSeedHex'))));
}

function assertExpectedAddress(address, expected) {
  if (expected && accountKey(address) !== accountKey(expected)) {
    throw new Error('derived Polkadot address does not match expectedFrom');
  }
}

function transferAllowDeath(api, to, amountPlanck) {
  if (api.tx.balances.transferAllowDeath) {
    return api.tx.balances.transferAllowDeath(to, amountPlanck);
  }
  if (api.tx.balances.transfer) {
    return api.tx.balances.transfer(to, amountPlanck);
  }
  throw new Error('balances transferAllowDeath is not available on this runtime');
}

function assetTransfer(api, assetId, to, amount, keepAlive) {
  if (keepAlive && api.tx.assets.transferKeepAlive) {
    return api.tx.assets.transferKeepAlive(assetId, to, amount);
  }
  if (api.tx.assets.transfer) {
    return api.tx.assets.transfer(assetId, to, amount);
  }
  if (api.tx.assets.transferKeepAlive) {
    return api.tx.assets.transferKeepAlive(assetId, to, amount);
  }
  throw new Error('assets transfer is not available on this runtime');
}

function deadAddress(ss58Prefix) {
  return encodeAddress(new Uint8Array(32), ss58Prefix);
}

async function submitAndWait(api, tx, pair, waitFinalized) {
  return new Promise((resolve, reject) => {
    let unsub = null;
    const timer = setTimeout(() => {
      if (unsub) {
        unsub();
      }
      reject(new Error('transaction finality timeout'));
    }, 120000);
    tx.signAndSend(pair, { nonce: -1 }, async (result) => {
      try {
        if (result.dispatchError) {
          throw new Error(dispatchErrorText(api, result.dispatchError));
        }
        const isReady = waitFinalized ? result.status.isFinalized : result.status.isInBlock;
        if (!isReady) {
          return;
        }
        const blockHash = waitFinalized ? result.status.asFinalized : result.status.asInBlock;
        const header = await api.rpc.chain.getHeader(blockHash);
        clearTimeout(timer);
        if (unsub) {
          unsub();
        }
        resolve({
          txHash: result.txHash.toHex(),
          blockHash: blockHash.toHex(),
          blockHeight: header.number.toNumber(),
          status: waitFinalized ? 'FINALIZED' : 'IN_BLOCK'
        });
      } catch (error) {
        clearTimeout(timer);
        if (unsub) {
          unsub();
        }
        reject(error);
      }
    }).then((unsubscribe) => {
      unsub = unsubscribe;
    }).catch((error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

function dispatchErrorText(api, dispatchError) {
  if (dispatchError.isModule) {
    const decoded = api.registry.findMetaError(dispatchError.asModule);
    return `${decoded.section}.${decoded.name}: ${decoded.docs.join(' ')}`;
  }
  return dispatchError.toString();
}

function addressBookFrom(addresses) {
  const result = new Map();
  for (const address of addresses) {
    result.set(accountKey(address), address);
  }
  return result;
}

function accountKey(address) {
  return u8aToHex(decodeAddress(address)).toLowerCase();
}

function applyExtrinsicIndex(phase) {
  return phase && phase.isApplyExtrinsic ? phase.asApplyExtrinsic.toNumber() : -1;
}

function extrinsicSucceeded(events, extrinsicIndex) {
  if (extrinsicIndex < 0) {
    return true;
  }
  return events.some(({ event, phase }) =>
    applyExtrinsicIndex(phase) === extrinsicIndex
      && event.section === 'system'
      && event.method === 'ExtrinsicSuccess');
}

function txHashFor(extrinsics, extrinsicIndex, blockHash, eventIndex) {
  return extrinsicIndex >= 0 && extrinsics[extrinsicIndex]
    ? extrinsics[extrinsicIndex].hash.toHex()
    : `${blockHash.toHex()}-${eventIndex}`;
}

function requireString(value, label) {
  const text = value == null ? '' : String(value).trim();
  if (!text) {
    throw new Error(`${label} is required`);
  }
  return text;
}

function normalizeHex(value) {
  const text = requireString(value, 'hex');
  return text.startsWith('0x') ? text : `0x${text}`;
}

function errorMessage(error) {
  return error && error.message ? error.message : String(error);
}
