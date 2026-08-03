const state = { session: null, me: null, chains: [], refreshing: false };
const $ = selector => document.querySelector(selector);
const chainLabels = Object.freeze({
  BTC: "Bitcoin", LTC: "Litecoin", DOGE: "Dogecoin", BCH: "Bitcoin Cash", ETH: "Ethereum",
  BNB: "BNB Chain", POLYGON: "Polygon", ARBITRUM: "Arbitrum One", OPTIMISM: "OP Mainnet",
  BASE: "Base", AVAX_C: "Avalanche C-Chain", HYPEREVM: "HyperEVM", MANTLE: "Mantle",
  LINEA: "Linea", SCROLL: "Scroll", UNICHAIN: "Unichain", ZKSYNC: "zkSync Era",
  BERACHAIN: "Berachain", GNOSIS: "Gnosis", CELO: "Celo", MONAD: "Monad",
  WORLD_CHAIN: "World Chain", INK: "Ink", TAIKO: "Taiko", SONEIUM: "Soneium", MODE: "Mode",
  LISK: "Lisk", KATANA: "Katana", MEGAETH: "MegaETH", X_LAYER: "X Layer",
  DEGEN: "Degen Chain", ROBINHOOD_CHAIN: "Robinhood Chain", OKT_CHAIN: "OKT Chain",
  STARKNET: "Starknet", ETHERLINK: "Etherlink", IOTA_EVM: "IOTA EVM",
  OASIS_EMERALD: "Oasis Emerald", CRONOS: "Cronos", SONIC: "Sonic", PULSECHAIN: "PulseChain",
  ZETACHAIN: "ZetaChain", CORE: "Core", SOMNIA: "Somnia", RONIN: "Ronin",
  CHILIZ: "Chiliz Chain", IOTEX: "IoTeX", KAIA: "Kaia", PLASMA: "Plasma", STORY: "Story",
  SEI: "Sei EVM", CONFLUX: "Conflux eSpace", VECTOR_SMART_CHAIN: "Vector Smart Chain",
  KROWN: "Krown", HYPERCORE: "HyperCore", TRON: "TRON", XRP: "XRP Ledger", SOLANA: "Solana",
  TON: "TON", APTOS: "Aptos", SUI: "Sui", ADA: "Cardano", DOT: "Polkadot", NEAR: "NEAR",
  XMR: "Monero"
});
const networkLabels = Object.freeze({
  mainnet: "主网", testnet: "测试网", devnet: "开发网", devtest: "开发测试",
  local: "本地", regtest: "Regtest", sepolia: "Sepolia", nile: "Nile 测试网", saigon: "Saigon 测试网"
});
const typeLabels = Object.freeze({
  DEPOSIT: "充值入账", WITHDRAWAL: "提现扣款", WITHDRAWAL_RELEASE: "提现失败释放"
});

function escape(value) {
  return String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);
}

function chainLabel(value) {
  const code = String(value ?? "").toUpperCase();
  return chainLabels[code] ?? code;
}

function networkLabel(value) {
  const code = String(value ?? "");
  return networkLabels[code.toLowerCase()] ?? (code || "未配置网络");
}

function chainText(row) {
  return `${escape(chainLabel(row.chain))}<br><code>${escape(row.chain)}</code>`;
}

function networkText(row) {
  return `${escape(networkLabel(row.network))}<br><code>${escape(row.network)}</code>`;
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const text = await response.text();
  let payload = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = { message: text }; }
  }
  if (response.status === 401) {
    state.session = { authenticated: false };
    showAuth();
    throw new Error(payload?.message ?? "请重新登录");
  }
  if (!response.ok) throw new Error(payload?.message ?? `HTTP ${response.status}`);
  return payload;
}

function toast(message, isError = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = isError ? "show error" : "show";
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { element.className = ""; }, 4200);
}

function formMessage(selector, message, isError = false) {
  const element = $(selector);
  element.textContent = message ?? "";
  element.classList.toggle("error", isError);
}

function showAuth() {
  $("#authScreen").classList.remove("hidden");
  $("#appScreen").classList.add("hidden");
  stopRefreshTimer();
}

function showApp() {
  $("#authScreen").classList.add("hidden");
  $("#appScreen").classList.remove("hidden");
  $("#accountName").textContent = `${state.session.user.displayName} · ${state.session.user.email}`;
  startRefreshTimer();
}

function table(target, columns, rows) {
  const element = $(target);
  if (!rows?.length) {
    element.innerHTML = '<div class="empty">暂无记录</div>';
    return;
  }
  element.innerHTML = `<div class="table-wrap"><table><thead><tr>${columns.map(column =>
    `<th>${escape(column.label)}</th>`).join("")}</tr></thead><tbody>${rows.map(row =>
    `<tr>${columns.map(column => `<td>${column.render ? column.render(row) : escape(row[column.key])}</td>`).join("")}</tr>`
  ).join("")}</tbody></table></div>`;
}

function setOptions(selector, rows, label, empty = "暂无可选数据") {
  const element = $(selector);
  element.innerHTML = rows?.length
    ? rows.map(row => `<option value="${escape(row.id ?? row.chain)}">${escape(label(row))}</option>`).join("")
    : `<option value="">${escape(empty)}</option>`;
}

function renderSummary() {
  const balances = state.me.balances ?? [];
  const pending = (state.me.withdrawals ?? []).filter(row =>
    !["CONFIRMED", "FAILED", "CANCELLED", "REQUEST_FAILED"].includes(row.status)).length;
  const summary = [
    [state.me.addresses.length, "充值地址"],
    [balances.length, "资产种类"],
    [pending, "处理中提现"],
    [state.me.ledger.length, "账本流水"]
  ];
  $("#summary").innerHTML = summary.map(([value, label]) =>
    `<div class="metric"><strong>${escape(value)}</strong><span>${label}</span></div>`).join("");
}

function renderWalletStatus() {
  const configured = state.chains.length > 0;
  $("#walletStatusDot").classList.toggle("ok", configured);
  $("#walletStatus").textContent = configured ? "钱包 API 已连接" : "钱包 API 未返回可用链";
}

function renderBalances() {
  table("#balancesTable", [
    { key: "asset", label: "资产" }, { key: "chain", label: "链", render: chainText },
    { key: "available", label: "可用" }, { key: "locked", label: "冻结" }
  ], state.me.balances);
}

function renderChains() {
  table("#chainsTable", [
    { key: "chain", label: "链", render: chainText },
    { key: "network", label: "网络", render: networkText },
    { label: "资产", render: row => escape((row.assetSymbols ?? []).join("、") || "-") }
  ], state.chains);
}

function renderAddresses() {
  table("#addressesTable", [
    { key: "chain", label: "链", render: chainText },
    { key: "network", label: "网络", render: networkText },
    { label: "地址", render: row => `<code>${escape(row.address)}</code>` },
    { key: "addressVersion", label: "版本" }, { key: "status", label: "状态" }
  ], state.me.addresses);
}

function renderWithdrawals() {
  table("#withdrawalsTable", [
    { key: "createdAt", label: "时间" }, { key: "asset", label: "资产" },
    { key: "chain", label: "链", render: chainText }, { key: "amount", label: "金额" },
    { key: "status", label: "状态", render: row => `<span class="pill">${escape(row.status)}</span>` },
    { label: "TxID", render: row => `<code>${escape(row.txHash ?? "等待回调")}</code>` }
  ], state.me.withdrawals);
}

function renderLedger() {
  table("#ledgerTable", [
    { key: "createdAt", label: "时间" },
    { key: "entryType", label: "类型", render: row => escape(typeLabels[row.entryType] ?? row.entryType) },
    { key: "asset", label: "资产" }, { key: "chain", label: "链", render: chainText },
    { key: "direction", label: "方向" }, { key: "amount", label: "金额" },
    { key: "referenceId", label: "参考" }
  ], state.me.ledger);
}

function refreshWithdrawalAssets() {
  const address = state.me?.addresses.find(row => row.id === $("#withdrawalAddress").value);
  const balances = (state.me?.balances ?? []).filter(row => row.chain === address?.chain);
  const select = $("#withdrawalAsset");
  select.innerHTML = balances.length
    ? balances.map(row => `<option value="${escape(row.asset)}">${escape(row.asset)} · 可用 ${escape(row.available)}</option>`).join("")
    : "<option value=\"\">当前地址暂无资产</option>";
  $("#withdrawalAsset").disabled = balances.length === 0;
}

function renderForms() {
  setOptions("#depositChain", state.chains,
    row => `${chainLabel(row.chain)} · ${networkLabel(row.network)} · ${(row.assetSymbols ?? []).join("、")}`);
  setOptions("#withdrawalAddress", state.me.addresses,
    row => `${chainLabel(row.chain)} · ${row.address.slice(0, 18)}…`);
  refreshWithdrawalAssets();
}

async function refreshAll() {
  if (state.refreshing || !state.session?.authenticated) return;
  state.refreshing = true;
  try {
    const [me, chains] = await Promise.all([request("/api/me"), request("/api/chains")]);
    state.me = me;
    state.chains = chains;
    renderSummary();
    renderWalletStatus();
    renderBalances();
    renderChains();
    renderAddresses();
    renderWithdrawals();
    renderLedger();
    renderForms();
  } catch (error) {
    if (state.session?.authenticated) toast(error.message, true);
  } finally {
    state.refreshing = false;
  }
}

let refreshTimer;
function startRefreshTimer() {
  stopRefreshTimer();
  refreshTimer = setInterval(() => refreshAll(), 10_000);
}
function stopRefreshTimer() {
  if (refreshTimer) clearInterval(refreshTimer);
  refreshTimer = undefined;
}

document.querySelectorAll(".auth-tab").forEach(button => button.addEventListener("click", () => {
  document.querySelectorAll(".auth-tab").forEach(item => item.classList.toggle("active", item === button));
  $("#loginForm").classList.toggle("hidden", button.dataset.auth !== "login");
  $("#registerForm").classList.toggle("hidden", button.dataset.auth !== "register");
  formMessage("#authMessage", "");
}));

$("#loginForm").addEventListener("submit", async event => {
  event.preventDefault();
  formMessage("#authMessage", "正在登录…");
  try {
    const result = await request("/api/auth/login", {
      method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(event.target)))
    });
    state.session = { authenticated: true, user: result.user };
    showApp();
    await refreshAll();
    toast("登录成功");
  } catch (error) {
    formMessage("#authMessage", error.message, true);
  }
});

$("#registerForm").addEventListener("submit", async event => {
  event.preventDefault();
  formMessage("#authMessage", "正在创建账户…");
  try {
    const result = await request("/api/auth/register", {
      method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(event.target)))
    });
    state.session = { authenticated: true, user: result.user };
    showApp();
    await refreshAll();
    toast("账户已创建");
  } catch (error) {
    formMessage("#authMessage", error.message, true);
  }
});

$("#logoutButton").addEventListener("click", async () => {
  await request("/api/auth/logout", { method: "POST" }).catch(() => {});
  state.session = { authenticated: false };
  state.me = null;
  showAuth();
  toast("已退出登录");
});

document.querySelectorAll(".main-nav button").forEach(button => button.addEventListener("click", () => {
  document.querySelectorAll(".main-nav button").forEach(item => item.classList.toggle("active", item === button));
  document.querySelectorAll(".tab").forEach(tab => tab.classList.toggle("active", tab.id === `tab-${button.dataset.tab}`));
}));
document.querySelectorAll(".jump-button").forEach(button => button.addEventListener("click", () => {
  const target = $(`.main-nav button[data-tab="${button.dataset.tab}"]`);
  target?.click();
}));

$("#refreshButton").addEventListener("click", () => refreshAll().then(() => toast("数据已刷新")));
$("#depositChain").addEventListener("change", () => formMessage("#depositMessage", ""));
$("#withdrawalAddress").addEventListener("change", refreshWithdrawalAssets);

$("#addressForm").addEventListener("submit", async event => {
  event.preventDefault();
  formMessage("#depositMessage", "正在调用钱包服务…");
  try {
    const input = Object.fromEntries(new FormData(event.target));
    const address = await request("/api/me/addresses", {
      method: "POST", body: JSON.stringify({ chain: input.chain, addressVersion: Number(input.addressVersion) })
    });
    await refreshAll();
    formMessage("#depositMessage", `地址已生成：${address.address}`);
    toast("充值地址已生成，等待 devfaucet 自动充值");
  } catch (error) {
    formMessage("#depositMessage", error.message, true);
  }
});

$("#withdrawalForm").addEventListener("submit", async event => {
  event.preventDefault();
  formMessage("#withdrawalMessage", "正在发起提现…");
  try {
    const input = Object.fromEntries(new FormData(event.target));
    const address = state.me.addresses.find(row => row.id === input.custodyAddressId);
    if (!address) throw new Error("请选择资产来源地址");
    const result = await request("/api/me/withdrawals", {
      method: "POST",
      body: JSON.stringify({ ...input, chain: address.chain })
    });
    await refreshAll();
    formMessage("#withdrawalMessage", `提现已提交，状态：${result.status}`);
    toast("提现请求已提交，等待钱包回调");
  } catch (error) {
    formMessage("#withdrawalMessage", error.message, true);
  }
});

async function boot() {
  try {
    state.session = await request("/api/session");
    if (!state.session.authenticated) {
      showAuth();
      return;
    }
    showApp();
    await refreshAll();
  } catch (error) {
    showAuth();
    formMessage("#authMessage", error.message, true);
  }
}

boot();
