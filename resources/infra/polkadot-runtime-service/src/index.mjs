import express from 'express';
import { signingDomain } from './signing-domain.mjs';
import { ApiPromise, WsProvider } from '@polkadot/api';
import { blake2AsHex, cryptoWaitReady, decodeAddress, encodeAddress } from '@polkadot/util-crypto';
import { u8aToHex } from '@polkadot/util';

const PORT = Number(process.env.PORT || process.env.POLKADOT_RUNTIME_PORT || 8787);
const HOST = process.env.HOST || process.env.POLKADOT_RUNTIME_HOST || '127.0.0.1';
const API_KEY = (process.env.POLKADOT_RUNTIME_API_KEY || '').trim();
const CONNECT_TIMEOUT = Number(process.env.POLKADOT_RUNTIME_CONNECT_TIMEOUT_MS || 20000);
const apis = new Map();
const providers = new WeakMap();
await cryptoWaitReady();
const app = express();
app.use(express.json({ limit: '256kb' }));
app.use((req, res, next) => {
  if (req.path !== '/health' && API_KEY && req.get('authorization') !== `Bearer ${API_KEY}` && req.get('x-api-key') !== API_KEY) {
    return res.status(401).json({ ok: false, error: 'unauthorized' });
  }
  if (['secretSeedHex', 'seed', 'mnemonic', 'privateKey', 'secretKey'].some(field => Object.hasOwn(req.body || {}, field))) {
    return res.status(400).json({ ok: false, error: 'only public inputs and externally signed transactions are accepted' });
  }
  return next();
});
app.get('/health', (_req, res) => res.json({ ok: true, service: 'polkadot-public-runtime' }));

app.post('/v1/polkadot/latest-finalized', route(async (api) => {
  const hash = await api.rpc.chain.getFinalizedHead();
  return { hash: hash.toHex(), height: (await api.rpc.chain.getHeader(hash)).number.toNumber(), genesisHash: api.genesisHash.toHex() };
}));
app.post('/v1/polkadot/native-balance', route(async (api, body) => {
  const account = await api.query.system.account(address(body.address));
  return { free: account.data.free.toString(), reserved: account.data.reserved.toString(), frozen: account.data.frozen.toString() };
}));
app.post('/v1/polkadot/asset-balance', route(async (api, body) => {
  const id = assetId(body.assetId), account = await api.query.assets.account(id, address(body.address));
  return { assetId: id, balance: account.isSome ? account.unwrap().balance.toString() : '0', status: account.isSome ? account.unwrap().status.toString() : 'NONE' };
}));
app.post('/v1/polkadot/asset-info', route(async (api, body) => {
  const id = assetId(body.assetId), [asset, metadata] = await Promise.all([api.query.assets.asset(id), api.query.assets.metadata(id)]);
  return { assetId: id, exists: asset.isSome, supply: asset.isSome ? asset.unwrap().supply.toString() : '0', minBalance: asset.isSome ? asset.unwrap().minBalance.toString() : '0', isSufficient: asset.isSome && asset.unwrap().isSufficient.isTrue,
    name: metadata.name.toUtf8(), symbol: metadata.symbol.toUtf8(), decimals: metadata.decimals.toNumber() };
}));
app.post('/v1/polkadot/network-info', route(async (api, body) => {
  const owner = address(body.address), id = body.assetId == null ? null : assetId(body.assetId);
  const hash = await api.rpc.chain.getFinalizedHead();
  const [header, nonce, account] = await Promise.all([api.rpc.chain.getHeader(hash), api.rpc.system.accountNextIndex(owner), api.query.system.account(owner)]);
  const token = id == null ? null : await api.query.assets.metadata(id);
  // Capture the current execution metadata synchronously after all state queries.
  const keep = id == null ? api.tx.balances.transferKeepAlive : api.tx.assets.transferKeepAlive;
  const death = id == null ? api.tx.balances.transferAllowDeath : api.tx.assets.transfer;
  return { genesisHash: api.genesisHash.toHex(), blockHash: hash.toHex(), blockHeight: header.number.toNumber(), nonce: nonce.toNumber(),
    specName: api.runtimeVersion.specName.toString(), specVersion: api.runtimeVersion.specVersion.toNumber(), transactionVersion: api.runtimeVersion.transactionVersion.toNumber(),
    ...signingDomain(api),
    callIndices: { keepAlive: u8aToHex(keep.callIndex), allowDeath: u8aToHex(death.callIndex) },
    decimals: token == null ? api.registry.chainDecimals[0] : token.decimals.toNumber(), nativeDecimals: api.registry.chainDecimals[0],
    existentialDeposit: api.consts.balances.existentialDeposit.toString(), nativeBalance: account.data.free.toString(), frozenBalance: account.data.frozen.toString(), assetId: id };
}));
app.post('/v1/polkadot/payment-info', route(async (api, body) => {
  signingDomain(api);
  const to = address(body.to), amount = positive(body.amountAtomic), from = address(body.address);
  const call = body.assetId == null ? api.tx.balances.transferKeepAlive(to, amount) : api.tx.assets.transfer(assetId(body.assetId), to, amount);
  // SDK fee estimation uses a public dummy witness, never a keypair or a signing callback.
  const fee = await call.paymentInfo(from);
  return { feePlanck: fee.partialFee.toString(), nativeDecimals: api.registry.chainDecimals[0] };
}));
app.post('/v1/polkadot/broadcast-signed', route(async (api, body) => {
  const raw = hex(body.extrinsicHex, 16384);
  return { txHash: (await api.rpc.author.submitExtrinsic(raw)).toHex() };
}));
app.post('/v1/polkadot/extrinsic-proof', route(async (api, body) => {
  const txHash = hash32(body.txHash), start = height(body.fromBlock), end = height(body.validUntilHeight);
  if (end < start || end - start > 4096) throw new RangeError('original mortal era search exceeds bounds');
  const finalized = await api.rpc.chain.getFinalizedHead(), latest = (await api.rpc.chain.getHeader(finalized)).number.toNumber();
  for (let number = start; number <= Math.min(end, latest); number++) {
    const hash = await api.rpc.chain.getBlockHash(number);
    const raw = await providers.get(api).send('chain_getBlock', [hash.toHex()]);
    const index = raw.block.extrinsics.findIndex(value => blake2AsHex(value) === txHash);
    if (index < 0) continue;
    return { found: true, ...await executionProof(api, raw.block, hash.toHex(), index, latest) };
  }
  const pending = await api.rpc.author.pendingExtrinsics();
  const candidate = pending.find(value => value.hash.toHex() === txHash);
  return { found: false, pending: Boolean(candidate), pendingExtrinsicHex: candidate?.toHex() ?? null, finalizedHeight: latest, genesisHash: api.genesisHash.toHex() };
}));
app.post('/v1/polkadot/scan-transfers', route(async (api, body) => {
  const first = height(body.fromBlock), last = height(body.toBlock), prefix = Number(body.ss58Prefix ?? 42);
  if (last < first || last - first >= 500) throw new RangeError('scan range exceeds bounds');
  const finalHash = await api.rpc.chain.getFinalizedHead(), latest = (await api.rpc.chain.getHeader(finalHash)).number.toNumber();
  if (last > latest) throw new RangeError('scan range is not finalized');
  const tracked = new Map((body.addresses || []).map(value => [accountKey(address(value)), value]));
  const assets = new Set((body.assetIds || []).map(assetId)), transfers = [];
  for (let number = first; number <= last; number++) {
    if (number === 0) continue; // Genesis allocations are not external signed deposits.
    const hash = await api.rpc.chain.getBlockHash(number), raw = await providers.get(api).send('chain_getBlock', [hash.toHex()]);
    const { records } = await eventState(api, raw.block.header.parentHash, hash.toHex());
    for (let eventIndex = 0; eventIndex < records.length; eventIndex++) {
      const record = records[eventIndex];
      if (!record.phase.isApplyExtrinsic) continue;
      const index = record.phase.asApplyExtrinsic.toNumber();
      if (!succeeded(records, index)) continue;
      const event = record.event;
      let from, to, amount, id = null;
      if (body.includeNative !== false && event.section === 'balances' && event.method === 'Transfer') [from, to, amount] = event.data;
      else if (body.includeAssets !== false && event.section === 'assets' && event.method === 'Transferred') {
        [id, from, to, amount] = event.data; id = id.toString();
        if (assets.size && !assets.has(id)) continue;
      } else continue;
      // Deposit/Endowed are also emitted by fee refunds and are not external transfers.
      const target = tracked.get(accountKey(to.toString()));
      if (!target) continue;
      transfers.push({ txHash: blake2AsHex(raw.block.extrinsics[index]), from: encodeAddress(decodeAddress(from.toString()), prefix), to: target,
        amountPlanck: amount.toString(), assetId: id, blockHeight: number, blockHash: hash.toHex(), eventIndex });
    }
  }
  return { transfers, genesisHash: api.genesisHash.toHex() };
}));

async function eventState(api, parentHash, blockHash) {
  // Calls in a runtime-upgrade block execute the parent's runtime; decode events with that registry.
  const at = await api.at(parentHash), key = at.query.system.events.key();
  const encoded = await providers.get(api).send('state_getStorage', [key, blockHash]);
  if (encoded == null) throw new Error('original block event storage is unavailable');
  return { records: at.registry.createType('Vec<EventRecord>', encoded), encoded, version: await api.rpc.state.getRuntimeVersion(parentHash) };
}
async function executionProof(api, block, hash, index, latest) {
  const { records, encoded, version } = await eventState(api, block.header.parentHash, hash);
  const header = api.registry.createType('Header', block.header);
  if (header.hash.toHex() !== hash) throw new Error('original block header hash differs');
  return { genesisHash: api.genesisHash.toHex(), finalizedHeight: latest, blockHeight: header.number.toNumber(), blockHash: hash,
    headerHex: header.toHex(), parentHash: block.header.parentHash, txHash: blake2AsHex(block.extrinsics[index]), extrinsicHex: block.extrinsics[index], extrinsicIndex: index,
    specVersion: version.specVersion.toNumber(), transactionVersion: version.transactionVersion.toNumber(), eventsHex: encoded,
    events: records.filter(value => value.phase.isApplyExtrinsic && value.phase.asApplyExtrinsic.toNumber() === index).map(({ event, phase }) => ({ index: phase.asApplyExtrinsic.toNumber(), section: event.section, method: event.method, data: event.data.map(value => value.toString()) })) };
}
function succeeded(records, index) {
  const results = records.filter(value => value.phase.isApplyExtrinsic && value.phase.asApplyExtrinsic.toNumber() === index && value.event.section === 'system' && ['ExtrinsicSuccess', 'ExtrinsicFailed'].includes(value.event.method));
  return results.length === 1 && results[0].event.method === 'ExtrinsicSuccess';
}
function route(action) {
  return async (req, res) => {
    try { res.json({ ok: true, result: await action(await apiFor(req.body.rpcUrl), req.body) }); }
    catch (error) { res.status(error instanceof RangeError || error instanceof TypeError ? 400 : 502).json({ ok: false, error: String(error.message || error).replace(/wss?:\/\/[^\s"']+/g, '[upstream]').slice(0, 1000) }); }
  };
}
async function apiFor(value) {
  const url = new URL(value);
  if (!['ws:', 'wss:'].includes(url.protocol)) throw new TypeError('Substrate websocket URL is required');
  if (!apis.has(value)) {
    const provider = new WsProvider(value);
    let timer;
    const connection = Promise.race([ApiPromise.create({ provider, noInitWarn: true }), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Substrate connection timeout')), CONNECT_TIMEOUT); })])
      .then(api => { clearTimeout(timer); providers.set(api, provider); return api; }).catch(async error => { clearTimeout(timer); apis.delete(value); await provider.disconnect(); throw error; });
    apis.set(value, connection);
  }
  const api = await apis.get(value);
  if ((await api.rpc.chain.getBlockHash(0)).toHex() !== api.genesisHash.toHex()) {
    apis.delete(value); await api.disconnect(); throw new Error('upstream genesis changed; reconnect and reconcile the fixed intent');
  }
  return api;
}
function hex(value, max) { if (typeof value !== 'string' || !/^0x(?:[0-9a-f]{2})+$/.test(value) || value.length > max) throw new TypeError('bounded lowercase hex is required'); return value; }
function hash32(value) { if (hex(value, 66).length !== 66) throw new TypeError('32-byte hash is required'); return value; }
function height(value) { const result = Number(value); if (!Number.isSafeInteger(result) || result < 0 || result > 0xffffffff) throw new RangeError('block height exceeds u32'); return result; }
function assetId(value) { const result = String(value); if (!/^(0|[1-9][0-9]{0,9})$/.test(result) || BigInt(result) > 0xffffffffn) throw new RangeError('asset id exceeds u32'); return result; }
function positive(value) { const result = String(value); if (!/^[1-9][0-9]{0,38}$/.test(result) || BigInt(result) >= (1n << 128n)) throw new RangeError('positive u128 amount is required'); return result; }
function address(value) { if (typeof value !== 'string' || value.length > 50 || decodeAddress(value).length !== 32) throw new TypeError('SS58 AccountId32 is required'); return value; }
function accountKey(value) { return u8aToHex(decodeAddress(value)); }
app.use((error, _req, res, _next) => res.status(400).json({ ok: false, error: error instanceof SyntaxError ? 'invalid JSON request body' : 'invalid public request' }));
const server = app.listen(PORT, HOST, () => console.log(`polkadot public runtime listening on ${HOST}:${PORT}`));
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => {
  server.close(); await Promise.allSettled([...apis.values()].map(async connection => (await connection).disconnect())); process.exit(0);
});
