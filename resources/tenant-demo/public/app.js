const state = {
  session: null, me: null, chains: [], refreshing: false,
  platformAddresses: [], addressHistory: { chain: "", page: 1, pageSize: 5 },
  pendingWithdrawal: null,
  ledgerFilters: { entryType: "", txId: "", address: "" }
};
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
const terminalWithdrawalStatuses = new Set(["CONFIRMED", "FAILED", "CANCELLED", "REQUEST_FAILED"]);

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

function decimalParts(value) {
  const text = String(value ?? "").trim();
  const match = /^(\d+)(?:\.(\d+))?$/.exec(text);
  if (!match) return null;
  return { integer: match[1].replace(/^0+(?=\d)/, ""), fraction: match[2] ?? "" };
}

function normalizeAmountInput(value) {
  const text = String(value ?? "").replace(/[^0-9.]/g, "");
  const firstDot = text.indexOf(".");
  if (firstDot < 0) return text;
  return `${text.slice(0, firstDot)}.${text.slice(firstDot + 1).replace(/\./g, "")}`;
}

function compareAmounts(first, second) {
  const left = decimalParts(first);
  const right = decimalParts(second);
  if (!left || !right) return null;
  const scale = Math.max(left.fraction.length, right.fraction.length);
  const leftValue = BigInt(`${left.integer}${left.fraction.padEnd(scale, "0")}`);
  const rightValue = BigInt(`${right.integer}${right.fraction.padEnd(scale, "0")}`);
  return leftValue === rightValue ? 0 : leftValue > rightValue ? 1 : -1;
}

function addAmounts(values) {
  const parts = values.map(decimalParts).filter(Boolean);
  if (!parts.length) return "0";
  const scale = Math.max(...parts.map(part => part.fraction.length));
  const total = parts.reduce((sum, part) =>
    sum + BigInt(`${part.integer}${part.fraction.padEnd(scale, "0")}`), 0n);
  let text = total.toString().padStart(scale + 1, "0");
  if (scale > 0) {
    const integer = text.slice(0, -scale);
    const fraction = text.slice(-scale).replace(/0+$/, "");
    text = fraction ? `${integer}.${fraction}` : integer;
  }
  return text;
}

function selectedChain(chainId) {
  return state.chains.find(row => row.chain === chainId);
}

function selectedBalance() {
  const chain = $("#withdrawalChain")?.value;
  const asset = $("#withdrawalAsset")?.value;
  return state.me?.balances.find(row => row.chain === chain && row.asset === asset) ?? null;
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
  $("#sessionLoading").classList.add("hidden");
  $("#authScreen").classList.remove("hidden");
  $("#appScreen").classList.add("hidden");
  stopRefreshTimer();
}

function showApp() {
  $("#sessionLoading").classList.add("hidden");
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
  const previous = element.value;
  element.innerHTML = rows?.length
    ? rows.map(row => `<option value="${escape(row.id ?? row.chain)}">${escape(label(row))}</option>`).join("")
    : `<option value="">${escape(empty)}</option>`;
  if (rows?.some(row => String(row.id ?? row.chain) === previous)) element.value = previous;
}

function renderSummary() {
  const balances = state.me.balances ?? [];
  const pending = (state.me.withdrawals ?? []).filter(row =>
    !terminalWithdrawalStatuses.has(row.status)).length;
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
  const grouped = new Map();
  for (const row of state.me.balances ?? []) {
    const current = grouped.get(row.asset) ?? { available: [], locked: [], total: [] };
    current.available.push(row.available);
    current.locked.push(row.locked);
    current.total.push(addAmounts([row.available, row.locked]));
    grouped.set(row.asset, current);
  }
  $("#assetTotals").innerHTML = grouped.size
    ? [...grouped.entries()].map(([asset, value]) => `
      <div class="asset-card"><strong>${escape(asset)}</strong>
        <span>总额 ${escape(addAmounts(value.total))}</span>
        <span>可提现 ${escape(addAmounts(value.available))} · 冻结 ${escape(addAmounts(value.locked))}</span>
      </div>`).join("")
    : '<div class="empty">暂无资产</div>';
  table("#balancesTable", [
    { key: "asset", label: "资产" }, { key: "chain", label: "链", render: chainText },
    { key: "available", label: "可用" }, { key: "locked", label: "冻结" },
    { label: "总额", render: row => escape(addAmounts([row.available, row.locked])) }
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
  const latest = new Map();
  for (const row of state.me.addresses ?? []) {
    const current = latest.get(row.chain);
    if (!current || Number(row.addressVersion) > Number(current.addressVersion)
      || (Number(row.addressVersion) === Number(current.addressVersion)
        && row.createdAt > current.createdAt)) {
      latest.set(row.chain, row);
    }
  }
  const rows = [...latest.values()].flatMap(row =>
    (selectedChain(row.chain)?.assetSymbols ?? [row.chain]).map(asset => ({ ...row, asset })));
  table("#addressesTable", [
    { key: "asset", label: "币种" },
    { key: "chain", label: "链", render: chainText },
    { key: "network", label: "网络", render: networkText },
    { label: "地址", render: row => `<code>${escape(row.address)}</code>` },
    { key: "status", label: "状态" },
    { label: "历史地址", render: row => `<button type="button" class="button-quiet" data-history-chain="${escape(row.chain)}">查看历史</button>` }
  ], rows);
  document.querySelectorAll("[data-history-chain]").forEach(button => {
    button.addEventListener("click", () => openAddressHistory(button.dataset.historyChain));
  });
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
  const filters = state.ledgerFilters;
  const allRows = state.me.ledger ?? [];
  const rows = allRows.filter(row => {
    const txId = String(row.txHash ?? "").toLowerCase();
    const address = String(row.address ?? row.depositAddress ?? "").toLowerCase();
    return (!filters.entryType || row.entryType === filters.entryType)
      && (!filters.txId || txId.includes(filters.txId.toLowerCase()))
      && (!filters.address || address.includes(filters.address.toLowerCase()));
  });
  $("#ledgerFilterSummary").textContent = `显示 ${rows.length} / ${allRows.length} 条流水`;
  table("#ledgerTable", [
    { key: "createdAt", label: "时间" },
    { key: "entryType", label: "类型", render: row => escape(typeLabels[row.entryType] ?? row.entryType) },
    { key: "asset", label: "资产" }, { key: "chain", label: "链", render: chainText },
    { key: "direction", label: "方向" }, { key: "amount", label: "金额" },
    { key: "txHash", label: "TxID", render: row => `<code>${escape(row.txHash ?? "—")}</code>` },
    { key: "depositAddress", label: "充值地址", render: row => `<code>${escape(row.depositAddress ?? "—")}</code>` }
  ], rows);
}

function refreshWithdrawalAssets() {
  const chain = $("#withdrawalChain").value;
  const balances = (state.me?.balances ?? []).filter(row => row.chain === chain
    && compareAmounts(row.available, "0") > 0);
  const select = $("#withdrawalAsset");
  const previous = select.value;
  select.innerHTML = balances.length
    ? balances.map(row => `<option value="${escape(row.asset)}">${escape(row.asset)} · 可用 ${escape(row.available)}</option>`).join("")
    : "<option value=\"\">当前链暂无可提现资产</option>";
  if (balances.some(row => row.asset === previous)) select.value = previous;
  $("#withdrawalAsset").disabled = balances.length === 0;
  refreshWithdrawalAmountHint();
}

function renderForms() {
  renderDepositOptions();
  setOptions("#withdrawalChain", state.chains.filter(row => row.withdrawalEnabled !== false),
    row => `${chainLabel(row.chain)} · ${networkLabel(row.network)}`);
  refreshWithdrawalAssets();
  refreshPlatformAddresses();
  refreshWithdrawalFunds();
}

function renderDepositOptions() {
  const assets = [...new Set(state.chains.flatMap(row => row.assetSymbols ?? []))]
    .sort((left, right) => left.localeCompare(right));
  const assetSelect = $("#depositAsset");
  const previousAsset = assetSelect.value;
  assetSelect.innerHTML = assets.length
    ? `<option value="">请选择充值币种</option>${assets.map(asset => `<option value="${escape(asset)}">${escape(asset)}</option>`).join("")}`
    : '<option value="">暂无可充值币种</option>';
  if (assets.includes(previousAsset)) assetSelect.value = previousAsset;
  updateDepositChainOptions();
}

function updateDepositChainOptions() {
  const asset = $("#depositAsset").value;
  const chains = state.chains.filter(row => (row.assetSymbols ?? []).includes(asset));
  const chainSelect = $("#depositChain");
  const previousChain = chainSelect.value;
  chainSelect.innerHTML = chains.length
    ? `<option value="">请选择充值链</option>${chains.map(row => `<option value="${escape(row.chain)}">${escape(chainLabel(row.chain))} · ${escape(networkLabel(row.network))}</option>`).join("")}`
    : '<option value="">请先选择充值币种</option>';
  chainSelect.disabled = chains.length === 0;
  if (chains.some(row => row.chain === previousChain)) chainSelect.value = previousChain;
  $("#depositAddressButton").disabled = !asset || !chainSelect.value;
  updateDepositAddressPanel();
}

function depositMinimum(asset, chain) {
  const config = selectedChain(chain);
  if (!config) return null;
  if (config.nativeSymbol === asset) return "0";
  return config.tokens?.find(token => token.symbol === asset)?.minDeposit ?? null;
}

function updateDepositAddressPanel() {
  const asset = $("#depositAsset").value;
  const chain = $("#depositChain").value;
  const address = state.me?.addresses
    .filter(row => row.chain === chain && row.status === "ACTIVE")
    .sort((left, right) => Number(right.addressVersion) - Number(left.addressVersion)
      || String(right.createdAt).localeCompare(String(left.createdAt)))[0];
  const panel = $("#depositAddressPanel");
  const rule = $("#depositRuleHint");
  if (!asset || !chain) {
    panel.classList.add("hidden");
    rule.textContent = "请选择币种和链查看最小充值数量。";
    return;
  }
  const config = selectedChain(chain);
  const minimum = depositMinimum(asset, chain);
  rule.textContent = `请使用 ${chainLabel(chain)} 链充值 ${asset}，否则不会到账。最小充值数量：${minimum ?? "平台未配置"} ${asset}。`;
  panel.classList.remove("hidden");
  $("#depositAddress").textContent = address?.address ?? "暂无地址，请点击获取充值地址";
  $("#depositAddressMeta").textContent = address
    ? `${chainLabel(chain)} · ${networkLabel(config?.network)} · 最小充值 ${minimum ?? "平台未配置"} ${asset}`
    : `选择的链为 ${chainLabel(chain)}，点击下方按钮生成首个充值地址。`;
}

function refreshWithdrawalFunds() {
  const grouped = new Map();
  for (const row of state.me?.balances ?? []) {
    const current = grouped.get(row.asset) ?? { available: [], locked: [] };
    current.available.push(row.available);
    current.locked.push(row.locked);
    grouped.set(row.asset, current);
  }
  $("#withdrawalFunds").innerHTML = grouped.size
    ? `<span class="funds-title">可提现资金与冻结资金（按币种汇总）</span>${[...grouped.entries()].map(([asset, value]) => `
      <span><strong>${escape(asset)}</strong> · 可提现 ${escape(addAmounts(value.available))} · 冻结 ${escape(addAmounts(value.locked))}</span>`).join("")}`
    : '<span>暂无资产</span>';
}

function refreshWithdrawalAmountHint() {
  const balance = selectedBalance();
  $("#withdrawalAmountHint").textContent = balance
    ? `最大可提现：${balance.available} ${balance.asset}`
    : "请选择链和提现资产";
}

async function refreshPlatformAddresses() {
  const chain = $("#withdrawalChain")?.value ?? "";
  if (!chain || !state.session?.authenticated) {
    state.platformAddresses = [];
    renderPlatformAddressHints();
    return;
  }
  try {
    state.platformAddresses = await request(`/api/me/platform-addresses?chain=${encodeURIComponent(chain)}&limit=100`);
    renderPlatformAddressHints();
  } catch (error) {
    if (state.session?.authenticated) toast(error.message, true);
  }
}

function renderPlatformAddressHints() {
  const options = state.platformAddresses ?? [];
  $("#platformAddressOptions").innerHTML = options.map(row =>
    `<option value="${escape(row.address)}">${escape(row.displayName)} · ${escape(row.address)}</option>`).join("");
  $("#platformAddressHints").innerHTML = options.slice(0, 8).map(row =>
    `<button type="button" class="address-hint" data-platform-address="${escape(row.address)}">${escape(row.displayName)} · ${escape(row.address.slice(0, 18))}…</button>`).join("");
  document.querySelectorAll("[data-platform-address]").forEach(button => {
    button.addEventListener("click", () => { $("#withdrawalTarget").value = button.dataset.platformAddress; });
  });
}

async function openAddressHistory(chain) {
  state.addressHistory = { chain, page: 1, pageSize: 5 };
  $("#addressHistoryTitle").textContent = `${chainLabel(chain)} 历史充值地址`;
  $("#addressHistoryModal").classList.remove("hidden");
  await loadAddressHistory();
}

async function loadAddressHistory() {
  const { chain, page, pageSize } = state.addressHistory;
  try {
    const result = await request(`/api/me/address-history?chain=${encodeURIComponent(chain)}&page=${page}&pageSize=${pageSize}`);
    table("#addressHistoryTable", [
      { key: "address", label: "地址", render: row => `<code>${escape(row.address)}</code>` },
      { key: "network", label: "网络", render: networkText },
      { key: "createdAt", label: "获取时间", render: row => escape(new Date(row.createdAt).toLocaleString()) },
      { key: "status", label: "状态" }
    ], result.items);
    $("#addressHistoryPagination").innerHTML = `
      <div class="pagination"><span>第 ${result.page} / ${result.pages} 页，共 ${result.total} 个地址</span>
        <div class="pagination-actions"><button type="button" class="button-quiet" id="historyPrevious" ${result.page <= 1 ? "disabled" : ""}>上一页</button>
        <button type="button" class="button-quiet" id="historyNext" ${result.page >= result.pages ? "disabled" : ""}>下一页</button></div></div>`;
    $("#historyPrevious").addEventListener("click", () => { state.addressHistory.page -= 1; loadAddressHistory(); });
    $("#historyNext").addEventListener("click", () => { state.addressHistory.page += 1; loadAddressHistory(); });
  } catch (error) {
    toast(error.message, true);
  }
}

function closeAddressHistory() {
  $("#addressHistoryModal").classList.add("hidden");
}

function showWithdrawalConfirmation(input, balance) {
  const chain = selectedChain(input.chain);
  $("#withdrawalConfirmDetails").innerHTML = [
    ["提现链", `${chainLabel(input.chain)} · ${networkLabel(chain?.network)}`],
    ["提现资产", input.assetSymbol], ["目标地址", input.toAddress], ["提现金额", `${input.amount} ${input.assetSymbol}`],
    ["可用上限", `${balance.available} ${balance.asset}`]
  ].map(([label, value]) => `<div class="confirm-row"><span>${escape(label)}</span><strong>${escape(value)}</strong></div>`).join("");
  $("#withdrawalConfirmModal").classList.remove("hidden");
}

function closeWithdrawalConfirmation() {
  $("#withdrawalConfirmModal").classList.add("hidden");
  state.pendingWithdrawal = null;
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
$("#ledgerTypeFilter").addEventListener("change", event => {
  state.ledgerFilters.entryType = event.target.value;
  renderLedger();
});
$("#ledgerTxFilter").addEventListener("input", event => {
  state.ledgerFilters.txId = event.target.value.trim();
  renderLedger();
});
$("#ledgerAddressFilter").addEventListener("input", event => {
  state.ledgerFilters.address = event.target.value.trim();
  renderLedger();
});
$("#clearLedgerFilters").addEventListener("click", () => {
  state.ledgerFilters = { entryType: "", txId: "", address: "" };
  $("#ledgerTypeFilter").value = "";
  $("#ledgerTxFilter").value = "";
  $("#ledgerAddressFilter").value = "";
  renderLedger();
});
$("#depositAsset").addEventListener("change", () => {
  formMessage("#depositMessage", "");
  updateDepositChainOptions();
});
$("#depositChain").addEventListener("change", () => {
  formMessage("#depositMessage", "");
  updateDepositAddressPanel();
});
$("#withdrawalChain").addEventListener("change", () => {
  refreshWithdrawalAssets();
  refreshPlatformAddresses();
});
$("#withdrawalAsset").addEventListener("change", refreshWithdrawalAmountHint);
$("#withdrawalAmount").addEventListener("input", event => {
  event.target.value = normalizeAmountInput(event.target.value);
  refreshWithdrawalAmountHint();
});

document.querySelectorAll("[data-close-address-history]").forEach(element =>
  element.addEventListener("click", closeAddressHistory));
document.querySelectorAll("[data-close-withdrawal-modal]").forEach(element =>
  element.addEventListener("click", closeWithdrawalConfirmation));

$("#addressForm").addEventListener("submit", async event => {
  event.preventDefault();
  formMessage("#depositMessage", "正在调用钱包服务获取新地址…");
  try {
    const input = Object.fromEntries(new FormData(event.target));
    const address = await request("/api/me/addresses", {
      method: "POST", body: JSON.stringify({ chain: input.chain })
    });
    await refreshAll();
    updateDepositAddressPanel();
    formMessage("#depositMessage", `充值地址已获取：${address.address}`);
    toast("充值地址已获取，请使用对应链充值");
  } catch (error) {
    formMessage("#depositMessage", error.message, true);
  }
});

$("#newDepositAddressButton").addEventListener("click", () => $("#addressForm").requestSubmit());

$("#withdrawalForm").addEventListener("submit", async event => {
  event.preventDefault();
  formMessage("#withdrawalMessage", "");
  try {
    const input = Object.fromEntries(new FormData(event.target));
    if (!input.chain) throw new Error("请选择提现链");
    if (!input.assetSymbol) throw new Error("请选择提现资产");
    const amount = normalizeAmountInput(input.amount);
    if (!decimalParts(amount) || compareAmounts(amount, "0") <= 0) {
      throw new Error("金额只能输入正数和小数点");
    }
    if (!input.toAddress?.trim()) throw new Error("请输入目标地址");
    const balance = selectedBalance();
    if (!balance) throw new Error("请选择有可用余额的提现资产");
    if (compareAmounts(amount, balance.available) > 0) {
      throw new Error(`提现金额不能超过最大可提现数量 ${balance.available}`);
    }
    state.pendingWithdrawal = { ...input, amount };
    showWithdrawalConfirmation(state.pendingWithdrawal, balance);
  } catch (error) {
    formMessage("#withdrawalMessage", error.message, true);
  }
});

$("#confirmWithdrawalButton").addEventListener("click", async () => {
  if (!state.pendingWithdrawal) return;
  const input = state.pendingWithdrawal;
  const button = $("#confirmWithdrawalButton");
  button.disabled = true;
  formMessage("#withdrawalMessage", "正在发起提现…");
  try {
    const result = await request("/api/me/withdrawals", {
      method: "POST", body: JSON.stringify({
        chain: input.chain, assetSymbol: input.assetSymbol,
        toAddress: input.toAddress.trim(), amount: input.amount
      })
    });
    closeWithdrawalConfirmation();
    await refreshAll();
    formMessage("#withdrawalMessage", `提现已提交，状态：${result.status}`);
    toast("提现请求已提交，处理中提现已更新");
  } catch (error) {
    formMessage("#withdrawalMessage", error.message, true);
    toast(error.message, true);
  } finally {
    button.disabled = false;
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
