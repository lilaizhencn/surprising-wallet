const state = { users: [], addresses: [], chains: [] };
let refreshBaseRequest = 0;
const $ = selector => document.querySelector(selector);

const CHAIN_LABELS = Object.freeze({
  BTC: "Bitcoin",
  LTC: "Litecoin",
  DOGE: "Dogecoin",
  BCH: "Bitcoin Cash",
  ETH: "Ethereum",
  BNB: "BNB Chain",
  POLYGON: "Polygon",
  ARBITRUM: "Arbitrum One",
  OPTIMISM: "OP Mainnet",
  BASE: "Base",
  AVAX_C: "Avalanche C-Chain",
  HYPEREVM: "HyperEVM",
  MANTLE: "Mantle",
  LINEA: "Linea",
  SCROLL: "Scroll",
  UNICHAIN: "Unichain",
  ZKSYNC: "zkSync Era",
  BERACHAIN: "Berachain",
  GNOSIS: "Gnosis",
  CELO: "Celo",
  MONAD: "Monad",
  WORLD_CHAIN: "World Chain",
  INK: "Ink",
  TAIKO: "Taiko",
  SONEIUM: "Soneium",
  MODE: "Mode",
  LISK: "Lisk",
  KATANA: "Katana",
  MEGAETH: "MegaETH",
  X_LAYER: "X Layer",
  DEGEN: "Degen Chain",
  ROBINHOOD_CHAIN: "Robinhood Chain",
  OKT_CHAIN: "OKT Chain（历史网络）",
  STARKNET: "Starknet",
  ETHERLINK: "Etherlink",
  IOTA_EVM: "IOTA EVM",
  OASIS_EMERALD: "Oasis Emerald",
  CRONOS: "Cronos",
  SONIC: "Sonic",
  PULSECHAIN: "PulseChain",
  ZETACHAIN: "ZetaChain",
  CORE: "Core",
  SOMNIA: "Somnia",
  RONIN: "Ronin",
  CHILIZ: "Chiliz Chain",
  IOTEX: "IoTeX",
  KAIA: "Kaia",
  PLASMA: "Plasma",
  STORY: "Story",
  SEI: "Sei EVM",
  CONFLUX: "Conflux eSpace",
  VECTOR_SMART_CHAIN: "Vector Smart Chain",
  KROWN: "Krown",
  HYPERCORE: "HyperCore",
  TRON: "TRON",
  XRP: "XRP Ledger",
  SOLANA: "Solana",
  TON: "TON",
  APTOS: "Aptos",
  SUI: "Sui",
  ADA: "Cardano",
  DOT: "Polkadot",
  NEAR: "NEAR",
  XMR: "Monero"
});

const FAMILY_LABELS = Object.freeze({
  evm: "EVM",
  utxo: "UTXO",
  account: "账户链",
  starknet: "Starknet",
  solana: "Solana",
  aptos: "Aptos",
  sui: "Sui",
  tron: "TRON",
  ton: "TON",
  xrp: "XRP",
  cardano: "Cardano",
  polkadot: "Polkadot",
  near: "NEAR",
  monero: "Monero",
  hypercore: "HyperCore",
  object: "对象模型",
  privacy: "隐私链"
});

const NETWORK_LABELS = Object.freeze({
  mainnet: "主网",
  testnet: "测试网",
  devnet: "开发网",
  devtest: "开发测试",
  local: "本地",
  regtest: "Regtest",
  nile: "Nile 测试网",
  shadownet: "Shadownet",
  saigon: "Saigon 测试网"
});

const REMOTE_ASSET_LABELS = Object.freeze({
  assetSymbol: "资产",
  asset: "资产",
  chain: "链",
  network: "网络",
  nativeAsset: "原生资产",
  available: "可用余额",
  locked: "冻结余额",
  total: "总余额",
  decimals: "精度",
  status: "状态",
  valueUsd: "USD 估值"
});

function chainLabel(chain) {
  const code = String(chain ?? "").toUpperCase();
  return CHAIN_LABELS[code] ?? code;
}

function networkLabel(network) {
  const value = String(network ?? "");
  return (NETWORK_LABELS[value.toLowerCase()] ?? value) || "未配置网络";
}

function familyLabel(family) {
  const value = String(family ?? "").toLowerCase();
  return (FAMILY_LABELS[value] ?? value) || "未分类";
}

function chainCell(row) {
  return `${escape(chainLabel(row.chain))}<br><code>${escape(row.chain)}</code>`;
}

function networkCell(row) {
  return `${escape(networkLabel(row.network))}<br><code>${escape(row.network)}</code>`;
}

function chainCapabilities(row) {
  return [
    row.scanEnabled ? "充值扫描" : "",
    row.withdrawalEnabled ? "提现" : "",
    row.transferEnabled ? "转账" : "",
    row.eip7702Enabled ? "EIP-7702" : ""
  ].filter(Boolean).join("、") || "仅配置";
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.message ?? `HTTP ${response.status}`);
  return payload;
}

function toast(message, error = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = error ? "show error" : "show";
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.className = "", 3500);
}

function escape(value) {
  return String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);
}

function table(target, columns, rows) {
  if (!rows.length) {
    $(target).innerHTML = '<div class="empty">暂无数据</div>';
    return;
  }
  $(target).innerHTML = `<div class="table-wrap"><table><thead><tr>${columns.map(column =>
    `<th>${escape(column.label)}</th>`).join("")}</tr></thead><tbody>${rows.map(row =>
    `<tr>${columns.map(column => `<td>${column.render ? column.render(row) : escape(row[column.key])}</td>`).join("")}</tr>`
  ).join("")}</tbody></table></div>`;
}

function options(select, rows, label) {
  select.innerHTML = rows.length
    ? rows.map(row => `<option value="${escape(row.id ?? row.chain)}">${escape(label(row))}</option>`).join("")
    : '<option value="">暂无可选数据</option>';
}

async function refreshBase() {
  const requestId = ++refreshBaseRequest;
  const [status, users, addresses] = await Promise.all([
    request("/api/status"), request("/api/users"), request("/api/addresses")
  ]);
  let chains = [];
  let chainError;
  if (status.configured) {
    try {
      chains = await request("/api/chains");
    } catch (error) {
      chainError = error;
    }
  }
  if (requestId !== refreshBaseRequest) return;
  state.users = users;
  state.addresses = addresses;
  state.chains = chains;
  $("#statusDot").classList.toggle("ok", status.configured);
  $("#statusText").textContent = status.configured ? "钱包 API 已配置" : "等待连接配置";
  table("#usersTable", [
    { key: "externalId", label: "用户标识" }, { key: "displayName", label: "名称" },
    { key: "createdAt", label: "创建时间" }
  ], users);
  table("#addressesTable", [
    { key: "externalId", label: "用户" }, { key: "chain", label: "链", render: chainCell },
    { key: "network", label: "网络", render: networkCell }, { label: "地址", render: row => `<code>${escape(row.address)}</code>` },
    { key: "memo", label: "Memo" }, { key: "addressVersion", label: "版本" }
  ], addresses);
  options($("#addressUser"), users, row => `${row.externalId} · ${row.displayName}`);
  options($("#withdrawalAddress"), addresses, row => `${row.externalId} · ${chainLabel(row.chain)} · ${row.address.slice(0, 12)}…`);
  options($("#addressChain"), state.chains,
    row => `${chainLabel(row.chain)} · ${networkLabel(row.network)} · ${(row.assetSymbols ?? []).join("、")}`);
  if (chainError) toast(chainError.message, true);
  table("#chainsTable", [
    { key: "chain", label: "链", render: chainCell },
    { key: "network", label: "网络", render: networkCell },
    { key: "family", label: "链族", render: row => escape(familyLabel(row.family)) },
    { key: "nativeSymbol", label: "原生资产" },
    { label: "可用资产", render: row => escape((row.assetSymbols ?? []).join("、") || "-") },
    { label: "能力", render: row => escape(chainCapabilities(row)) }
  ], state.chains);
  $("#summary").innerHTML = [
    [status.users, "交易所用户"], [status.addresses, "充值地址"],
    [state.chains.length, "已启用链"], [status.events, "已接收回调"]
  ].map(([value, label]) => `<div class="metric"><strong>${escape(value)}</strong><span>${label}</span></div>`).join("");
  refreshWithdrawalAssets();
  const config = status;
  $("#configForm [name=walletBaseUrl]").value = config.walletBaseUrl ?? "";
  $("#configForm [name=walletKeyId]").value = config.walletKeyId ?? "";
  $("#configForm [name=walletApiSecret]").value = config.walletApiSecret ?? "";
  $("#configForm [name=webhookSecret]").value = config.webhookSecret ?? "";
  $("#webhookUrl").value = config.webhookUrl;
}

async function refreshAssets() {
  const [balances, ledger] = await Promise.all([request("/api/assets"), request("/api/ledger")]);
  table("#balancesTable", [
    { key: "externalId", label: "用户" }, { key: "asset", label: "资产" },
    { key: "chain", label: "链", render: chainCell }, { key: "available", label: "可用" }, { key: "locked", label: "冻结" }
  ], balances);
  table("#ledgerTable", [
    { key: "createdAt", label: "时间" }, { key: "externalId", label: "用户" },
    { key: "entryType", label: "类型" }, { key: "asset", label: "资产" },
    { key: "chain", label: "链", render: chainCell }, { key: "direction", label: "方向" }, { key: "amount", label: "金额" }
  ], ledger);
  try {
    const remote = await request("/api/wallet/assets");
    table("#walletAssetsTable", Object.keys(remote[0] ?? { asset: "" }).slice(0, 7).map(key => ({
      key, label: REMOTE_ASSET_LABELS[key] ?? key
    })), remote);
  } catch (error) {
    $("#walletAssetsTable").innerHTML = `<div class="empty">${escape(error.message)}</div>`;
  }
}

async function refreshWithdrawals() {
  const rows = await request("/api/withdrawals");
  table("#withdrawalsTable", [
    { key: "createdAt", label: "时间" }, { key: "externalId", label: "用户" },
    { key: "asset", label: "资产" }, { key: "chain", label: "链", render: chainCell },
    { key: "amount", label: "金额" }, { key: "status", label: "状态", render: row => `<span class="pill">${escape(row.status)}</span>` },
    { label: "TxID", render: row => `<code>${escape(row.txHash ?? "-")}</code>` }
  ], rows);
}

async function refreshEvents() {
  const rows = await request("/api/events");
  table("#eventsTable", [
    { key: "receivedAt", label: "接收时间" }, { key: "eventType", label: "事件" },
    { label: "Event ID", render: row => `<code>${escape(row.eventId)}</code>` },
    { label: "签名", render: row => `<span class="pill ${row.signatureValid ? "" : "bad"}">${row.signatureValid ? "通过" : "失败"}</span>` },
    { label: "处理", render: row => `<span class="pill ${row.processed ? "" : "bad"}">${row.processed ? "完成" : escape(row.errorMessage ?? "失败")}</span>` }
  ], rows);
}

document.querySelectorAll("nav button").forEach(button => button.addEventListener("click", () => {
  document.querySelectorAll("nav button").forEach(item => item.classList.toggle("active", item === button));
  document.querySelectorAll(".tab").forEach(tab => tab.classList.toggle("active", tab.id === `tab-${button.dataset.tab}`));
  if (button.dataset.tab === "assets") refreshAssets().catch(error => toast(error.message, true));
  if (button.dataset.tab === "withdrawals") refreshWithdrawals().catch(error => toast(error.message, true));
  if (button.dataset.tab === "events") refreshEvents().catch(error => toast(error.message, true));
}));

$("#userForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    await request("/api/users", { method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(event.target))) });
    event.target.reset();
    await refreshBase();
    toast("用户已创建");
  } catch (error) { toast(error.message, true); }
});

function refreshWithdrawalAssets() {
  const select = $("#withdrawalAsset");
  const address = state.addresses.find(row => row.id === $("#withdrawalAddress").value);
  const chain = state.chains.find(row => row.chain === address?.chain);
  const assets = chain?.assetSymbols ?? [];
  select.innerHTML = assets.length
    ? `<option value="">请选择资产</option>${assets.map(asset =>
      `<option value="${escape(asset)}">${escape(asset)}</option>`).join("")}`
    : '<option value="">当前链暂无可提现资产</option>';
  select.disabled = assets.length === 0;
}

$("#addressForm").addEventListener("submit", async event => {
  event.preventDefault();
  const input = Object.fromEntries(new FormData(event.target));
  try {
    await request(`/api/users/${encodeURIComponent(input.userId)}/addresses`, {
      method: "POST", body: JSON.stringify({ chain: input.chain, addressVersion: Number(input.addressVersion) })
    });
    await refreshBase();
    toast("充值地址已生成");
  } catch (error) { toast(error.message, true); }
});

$("#withdrawalForm").addEventListener("submit", async event => {
  event.preventDefault();
  const input = Object.fromEntries(new FormData(event.target));
  const address = state.addresses.find(row => row.id === input.custodyAddressId);
  try {
    if (!address) throw new Error("请选择有效的充值地址");
    if (!input.assetSymbol) throw new Error("请选择提现资产");
    await request(`/api/users/${encodeURIComponent(address.userId)}/withdrawals`, {
      method: "POST",
      body: JSON.stringify({ ...input, chain: address.chain })
    });
    await Promise.all([refreshWithdrawals(), refreshAssets()]);
    toast("提现请求已提交");
  } catch (error) { toast(error.message, true); }
});

$("#withdrawalAddress").addEventListener("change", refreshWithdrawalAssets);

$("#configForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    await request("/api/config", { method: "PUT", body: JSON.stringify(Object.fromEntries(new FormData(event.target))) });
    await refreshBase();
    toast("连接配置已保存");
  } catch (error) { toast(error.message, true); }
});

$("#refreshAssets").addEventListener("click", () => refreshAssets().catch(error => toast(error.message, true)));
$("#refreshEvents").addEventListener("click", () => refreshEvents().catch(error => toast(error.message, true)));

refreshBase().catch(error => toast(error.message, true));
