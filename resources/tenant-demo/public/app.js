const state = {
  session: null, me: null, chains: [], refreshing: false,
  platformAddresses: [], addressHistory: { chain: "", page: 1, pageSize: 5 },
  pendingWithdrawal: null,
  ledgerFilters: { entryType: "", txId: "", address: "", businessOrderNo: "" },
  withdrawalFilters: { businessOrderNo: "", address: "", txId: "" },
  withdrawalFees: {}
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
const withdrawalStatusLabels = Object.freeze({
  CREATED: "已创建", FROZEN: "已冻结", RETRYING: "重试中", SIGNING: "签名中",
  SENT: "已广播", CONFIRMING: "确认中", CONFIRMED: "已完成", FAILED: "失败",
  CANCELLED: "已取消", BROADCAST_UNKNOWN: "广播待核对", REQUEST_FAILED: "请求失败",
  PENDING_REVIEW: "人工审核中"
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

/** 优先从行数据获取网络标签，若缺失则从已加载的链配置中推导。 */
function derivedNetworkLabel(row) {
  if (row.network) return networkLabel(row.network);
  const config = state.chains.find(c => c.chain === row.chain);
  return config?.network ? networkLabel(config.network) : "—";
}

/** 获取当前选中资产的平台提现手续费。 */
function withdrawalFeeForAsset(asset) {
  return state.withdrawalFees?.[String(asset ?? "").toUpperCase()] ?? "0";
}

/** 生成地址的可读摘要，保留更多地址字符以便辨认。 */
function shortAddress(value) {
  const address = String(value ?? "");
  return address.length > 48 ? `${address.slice(0, 16)}…${address.slice(-16)}` : address;
}

/** 输出带完整地址悬浮提示的安全地址单元格。 */
function addressCell(value) {
  const address = String(value ?? "");
  return address
    ? `<span class="inline-value"><code class="address-cell" title="${escape(address)}">${escape(shortAddress(address))}</code><button type="button" class="copy-action" data-copy="${escape(address)}">复制</button></span>`
    : "—";
}

/** 将 ISO 时间转换为租户可读的本地时间。 */
function dateText(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit"
  });
}

/** 输出可复制的交易哈希，避免长字符串撑开租户表格。 */
function txCell(value) {
  const txId = String(value ?? "");
  return txId
    ? `<span class="inline-value"><code title="${escape(txId)}">${escape(shortAddress(txId))}</code><button type="button" class="copy-action" data-copy="${escape(txId)}">复制</button></span>`
    : "等待回调";
}

/** 输出平台 ID 与租户业务订单号，平台 ID 只用于追踪不替代租户订单号。 */
function orderCell(row) {
  const businessOrderNo = row.businessOrderNo ?? row.externalReference ?? "—";
  const platformId = row.platformId ?? row.custodyWithdrawalId ?? row.id ?? "—";
  return `<div class="cell-stack"><code title="${escape(businessOrderNo)}">${escape(businessOrderNo)}</code><small>平台 ID：${escape(platformId)}</small></div>`;
}

/** 输出状态标签并区分处理中、成功和失败。 */
function statusCell(status) {
  const value = String(status ?? "");
  return `<span class="pill status-${escape(value.toLowerCase())}">${escape(withdrawalStatusText(value))}</span>`;
}

function detailButton(type, id) {
  return `<button type="button" class="button-quiet detail-button" data-transaction-detail="${escape(type)}:${escape(id)}">详情</button>`;
}

/** 展示钱包服务返回的提现手续费。 */
function withdrawalFeeText(row) {
  const fee = String(row.fee ?? "0");
  return compareAmounts(fee, "0") === 0 ? escape(fee) : `${escape(fee)} ${escape(row.asset)}`;
}

/** 将钱包服务状态转换成租户可理解的文案。 */
function withdrawalStatusText(status) {
  const value = String(status ?? "");
  return withdrawalStatusLabels[value] ?? (value || "处理中");
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
  const filters = state.withdrawalFilters;
  const allRows = state.me?.withdrawals ?? [];
  const rows = allRows.filter(row => {
    const businessOrderNo = String(row.businessOrderNo ?? row.externalReference ?? "").toLowerCase();
    const address = String(row.toAddress ?? "").toLowerCase();
    const txId = String(row.txHash ?? "").toLowerCase();
    return (!filters.businessOrderNo || businessOrderNo.includes(filters.businessOrderNo.toLowerCase()))
      && (!filters.address || address.includes(filters.address.toLowerCase()))
      && (!filters.txId || txId.includes(filters.txId.toLowerCase()));
  });
  $("#withdrawalFilterSummary").textContent = `显示 ${rows.length} / ${allRows.length} 条提现记录`;
  table("#withdrawalsTable", [
    { key: "createdAt", label: "时间", render: row => escape(dateText(row.createdAt)) },
    { label: "链", render: row => escape(chainLabel(row.chain)) },
    { label: "网络", render: row => escape(derivedNetworkLabel(row)) },
    { label: "目标地址", render: row => addressCell(row.toAddress) },
    { key: "amount", label: "数量" },
    { key: "asset", label: "币种" },
    { label: "手续费", render: row => row.fee && row.fee !== "0" ? `${escape(row.fee)} ${escape(row.asset)}` : "—" },
    { label: "平台ID", render: row => { const pid = row.platformId ?? row.id; return `<code title="${escape(pid)}">${escape(shortAddress(pid))}</code>`; }},
    { label: "业务订单号", render: row => escape(row.businessOrderNo ?? "—") },
    { key: "status", label: "状态", render: row => statusCell(row.status) },
    { label: "TxID", render: row => `${txCell(row.txHash)}<div class="table-actions">${detailButton("withdrawal", row.id)}</div>` }
  ], rows);
}

function renderLedger() {
  const filters = state.ledgerFilters;
  const allRows = state.me.ledger ?? [];
  const rows = allRows.filter(row => {
    const txId = String(row.txHash ?? "").toLowerCase();
    const address = String(row.address ?? row.depositAddress ?? "").toLowerCase();
    const businessOrderNo = String(row.businessOrderNo ?? row.referenceId ?? "").toLowerCase();
    return (!filters.entryType || row.entryType === filters.entryType)
      && (!filters.txId || txId.includes(filters.txId.toLowerCase()))
      && (!filters.address || address.includes(filters.address.toLowerCase()))
      && (!filters.businessOrderNo || businessOrderNo.includes(filters.businessOrderNo.toLowerCase()));
  });
  $("#ledgerFilterSummary").textContent = `显示 ${rows.length} / ${allRows.length} 条流水`;
  table("#ledgerTable", [
    { key: "createdAt", label: "时间", render: row => escape(dateText(row.createdAt)) },
    { key: "entryType", label: "类型", render: row => escape(typeLabels[row.entryType] ?? row.entryType) },
    { key: "asset", label: "资产" }, { label: "链", render: row => `${escape(chainLabel(row.chain))}<br><small>${escape(derivedNetworkLabel(row))}</small>` },
    { key: "direction", label: "方向" }, { key: "amount", label: "金额" },
    { key: "txHash", label: "TxID", render: row => txCell(row.txHash) },
    { key: "address", label: "地址", render: row => addressCell(row.address ?? row.depositAddress) },
    { label: "业务订单号", render: row => escape(row.businessOrderNo ?? "—") },
    { label: "操作", render: row => detailButton("ledger", row.id) }
  ], rows);
}

/** 在充值页展示充值入账记录，避免租户只能在总账本中寻找充值。 */
function renderDepositRecords() {
  const rows = (state.me?.ledger ?? []).filter(row => row.entryType === "DEPOSIT");
  table("#depositRecords", [
    { key: "createdAt", label: "到账时间", render: row => escape(dateText(row.createdAt)) },
    { label: "链", render: row => escape(chainLabel(row.chain)) },
    { label: "网络", render: row => escape(derivedNetworkLabel(row)) },
    { key: "amount", label: "数量" },
    { key: "asset", label: "币种" },
    { label: "TxID", render: row => txCell(row.txHash) },
    { label: "充值地址", render: row => addressCell(row.depositAddress ?? row.address) },
    { label: "操作", render: row => detailButton("ledger", row.id) }
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
  const asset = $("#withdrawalAsset")?.value ?? "";
  const fee = withdrawalFeeForAsset(asset);
  if (!balance) {
    $("#withdrawalAmountHint").textContent = "请选择链和提现资产";
    return;
  }
  const feePart = fee !== "0" ? ` · 手续费：${fee} ${balance.asset}` : "";
  try {
    const totalNeeded = addAmounts([balance.available, fee]); // for display: max you can withdraw
    $("#withdrawalAmountHint").innerHTML = `最大可提现：${escape(balance.available)} ${escape(balance.asset)}<br><small>含平台手续费 ${escape(fee)} ${escape(balance.asset)}，实际到账 = 提现金额 − 手续费</small>`;
  } catch {
    $("#withdrawalAmountHint").textContent = `最大可提现：${balance.available} ${balance.asset} · 手续费：${fee} ${balance.asset}`;
  }
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
    `<button type="button" class="address-hint" data-platform-address="${escape(row.address)}" title="${escape(row.address)}">${escape(row.displayName)} · ${escape(shortAddress(row.address))}</button>`).join("");
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
      { key: "address", label: "地址", render: row => addressCell(row.address) },
      { key: "network", label: "网络", render: networkText },
      { key: "createdAt", label: "获取时间", render: row => escape(dateText(row.createdAt)) },
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
  const fee = withdrawalFeeForAsset(input.assetSymbol);
  $("#withdrawalConfirmDetails").innerHTML = [
    ["提现链", chainLabel(input.chain)], ["网络", networkLabel(chain?.network)],
    ["提现资产", input.assetSymbol], ["租户业务订单号", input.businessOrderNo],
    ["目标地址", input.toAddress], ["提现金额", `${input.amount} ${input.assetSymbol}`],
    ["平台手续费", fee !== "0" ? `${fee} ${input.assetSymbol}` : "无"],
    ["可用上限", `${balance.available} ${balance.asset}`]
  ].map(([label, value]) => `<div class="confirm-row"><span>${escape(label)}</span><strong>${escape(value)}</strong></div>`).join("");
  $("#withdrawalConfirmModal").classList.remove("hidden");
}

function closeWithdrawalConfirmation() {
  $("#withdrawalConfirmModal").classList.add("hidden");
  state.pendingWithdrawal = null;
}

function closeTransactionDetail() {
  $("#transactionDetailModal").classList.add("hidden");
  $("#transactionDetailBody").innerHTML = "";
}

/** 展示账本或提现详情，并把回调状态按时间线呈现给租户。 */
async function openTransactionDetail(type, id) {
  const modal = $("#transactionDetailModal");
  const body = $("#transactionDetailBody");
  modal.classList.remove("hidden");
  body.innerHTML = '<div class="loading">正在读取交易详情…</div>';
  try {
    const result = await request(`/api/me/${type === "ledger" ? "ledger" : "withdrawals"}/${encodeURIComponent(id)}`);
    if (type === "ledger") {
      const entry = result.entry;
      $("#transactionDetailTitle").textContent = entry.entryType === "DEPOSIT" ? "充值详情" : "账本详情";
      body.innerHTML = `<div class="detail-grid">
        <div><span>类型</span><strong>${escape(typeLabels[entry.entryType] ?? entry.entryType)}</strong></div>
        <div><span>状态</span><strong>${escape(entry.status ?? "已入账")}</strong></div>
        <div><span>资产</span><strong>${escape(entry.amount)} ${escape(entry.asset)}</strong></div>
        <div><span>链/网络</span><strong>${escape(chainLabel(entry.chain))} · ${escape(derivedNetworkLabel(entry))}</strong></div>
        <div><span>方向</span><strong>${escape(entry.direction)}</strong></div>
        <div><span>时间</span><strong>${escape(dateText(entry.createdAt))}</strong></div>
        <div class="detail-wide"><span>TxID</span><strong>${txCell(entry.txHash)}</strong></div>
        <div class="detail-wide"><span>地址</span><strong>${addressCell(entry.address ?? entry.depositAddress)}</strong></div>
        <div class="detail-wide"><span>租户业务订单号</span><strong>${escape(entry.businessOrderNo ?? "—")}</strong></div>
      </div>`;
      return;
    }
    const withdrawal = result.withdrawal;
    $("#transactionDetailTitle").textContent = "提现详情";
    body.innerHTML = `<div class="detail-grid">
      <div><span>提现链</span><strong>${escape(chainLabel(withdrawal.chain))}</strong></div>
      <div><span>网络</span><strong>${escape(derivedNetworkLabel(withdrawal))}</strong></div>
      <div><span>资产金额</span><strong>${escape(withdrawal.amount)} ${escape(withdrawal.asset)}</strong></div>
      <div><span>手续费</span><strong>${withdrawalFeeText(withdrawal)}</strong></div>
      <div class="detail-wide"><span>目标地址</span><strong>${addressCell(withdrawal.toAddress)}</strong></div>
      <div><span>平台 ID</span><strong>${escape(withdrawal.platformId ?? withdrawal.custodyWithdrawalId ?? withdrawal.id)}</strong></div>
      <div><span>租户业务订单号</span><strong>${escape(withdrawal.businessOrderNo ?? withdrawal.externalReference ?? "—")}</strong></div>
      <div><span>当前状态</span><strong>${statusCell(withdrawal.status)}</strong></div>
      <div class="detail-wide"><span>TxID</span><strong>${txCell(withdrawal.txHash)}</strong></div>
      ${withdrawal.errorMessage ? `<div class="detail-wide detail-error"><span>处理说明</span><strong>${escape(withdrawal.errorMessage)}</strong></div>` : ""}
    </div>
    <section class="detail-section"><h3>处理时间线</h3><div class="timeline">${(result.timeline ?? []).map(event => `
      <div class="timeline-item"><span class="timeline-dot"></span><div class="timeline-content"><strong>${escape(withdrawalStatusText(event.status ?? event.eventType))}</strong><span>${escape(dateText(event.receivedAt))}</span>${event.txHash ? `<div>${txCell(event.txHash)}</div>` : ""}${event.errorMessage ? `<small class="detail-error">${escape(event.errorMessage)}</small>` : ""}</div></div>`).join("")}</div></section>`;
  } catch (error) {
    body.innerHTML = `<div class="empty">交易详情读取失败：${escape(error.message)}</div>`;
  }
}

async function refreshAll() {
  if (state.refreshing || !state.session?.authenticated) return;
  state.refreshing = true;
  try {
    const [me, chains] = await Promise.all([request("/api/me"), request("/api/chains")]);
    // 异步加载提现手续费配置，非关键路径不阻塞主刷新
    request("/api/status").then(status => {
      if (status?.withdrawalFees) state.withdrawalFees = status.withdrawalFees;
    }).catch(() => {});
    state.me = me;
    state.chains = chains;
    renderSummary();
    renderWalletStatus();
    renderBalances();
    renderChains();
    renderAddresses();
    renderWithdrawals();
    renderLedger();
    renderDepositRecords();
    renderForms();
    $("#refreshMeta").textContent = `最近同步：${dateText(new Date().toISOString())}`;
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
$("#withdrawalBusinessOrderFilter").addEventListener("input", event => {
  state.withdrawalFilters.businessOrderNo = event.target.value.trim();
});
$("#withdrawalAddressFilter").addEventListener("input", event => {
  state.withdrawalFilters.address = event.target.value.trim();
});
$("#withdrawalTxFilter").addEventListener("input", event => {
  state.withdrawalFilters.txId = event.target.value.trim();
});
$("#applyWithdrawalFilters").addEventListener("click", () => renderWithdrawals());
["#withdrawalBusinessOrderFilter", "#withdrawalAddressFilter", "#withdrawalTxFilter"]
  .forEach(selector => $(selector).addEventListener("keydown", event => {
    if (event.key === "Enter") renderWithdrawals();
  }));
$("#clearWithdrawalFilters").addEventListener("click", () => {
  state.withdrawalFilters = { businessOrderNo: "", address: "", txId: "" };
  $("#withdrawalBusinessOrderFilter").value = "";
  $("#withdrawalAddressFilter").value = "";
  $("#withdrawalTxFilter").value = "";
  renderWithdrawals();
});
$("#ledgerTypeFilter").addEventListener("change", event => {
  state.ledgerFilters.entryType = event.target.value;
});
$("#ledgerTxFilter").addEventListener("input", event => {
  state.ledgerFilters.txId = event.target.value.trim();
});
$("#ledgerAddressFilter").addEventListener("input", event => {
  state.ledgerFilters.address = event.target.value.trim();
});
$("#ledgerBusinessOrderFilter").addEventListener("input", event => {
  state.ledgerFilters.businessOrderNo = event.target.value.trim();
});
$("#applyLedgerFilters").addEventListener("click", () => renderLedger());
[
  "#ledgerTxFilter", "#ledgerAddressFilter", "#ledgerBusinessOrderFilter"
].forEach(selector => $(selector).addEventListener("keydown", event => {
  if (event.key === "Enter") renderLedger();
}));
$("#clearLedgerFilters").addEventListener("click", () => {
  state.ledgerFilters = { entryType: "", txId: "", address: "", businessOrderNo: "" };
  $("#ledgerTypeFilter").value = "";
  $("#ledgerTxFilter").value = "";
  $("#ledgerAddressFilter").value = "";
  $("#ledgerBusinessOrderFilter").value = "";
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

document.addEventListener("click", event => {
  const copyButton = event.target.closest("[data-copy]");
  if (copyButton) {
    const copyPromise = navigator.clipboard?.writeText(copyButton.dataset.copy ?? "");
    if (copyPromise) {
      copyPromise.then(() => toast("已复制"))
        .catch(() => toast("复制失败，请手动选择文本", true));
    } else {
      toast("当前浏览器不支持自动复制，请手动选择文本", true);
    }
    return;
  }
  const detail = event.target.closest("[data-transaction-detail]");
  if (detail) {
    const [type, ...idParts] = detail.dataset.transactionDetail.split(":");
    openTransactionDetail(type, idParts.join(":"));
  }
});

document.querySelectorAll("[data-close-address-history]").forEach(element =>
  element.addEventListener("click", closeAddressHistory));
document.querySelectorAll("[data-close-withdrawal-modal]").forEach(element =>
  element.addEventListener("click", closeWithdrawalConfirmation));
document.querySelectorAll("[data-close-transaction-detail]").forEach(element =>
  element.addEventListener("click", closeTransactionDetail));
$("#depositRecordsRefresh").addEventListener("click", () => refreshAll().then(() => toast("充值记录已刷新")));
document.addEventListener("keydown", event => {
  if (event.key !== "Escape") return;
  closeAddressHistory();
  closeWithdrawalConfirmation();
  closeTransactionDetail();
});

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
    input.businessOrderNo = input.businessOrderNo?.trim();
    if (!input.businessOrderNo) throw new Error("请输入租户业务订单号");
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
    state.pendingWithdrawal = { ...input, amount, idempotencyKey: crypto.randomUUID() };
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
      method: "POST",
      headers: { "Idempotency-Key": input.idempotencyKey },
      body: JSON.stringify({
        chain: input.chain, assetSymbol: input.assetSymbol,
        toAddress: input.toAddress.trim(), amount: input.amount,
        businessOrderNo: input.businessOrderNo
      })
    });
    closeWithdrawalConfirmation();
    await refreshAll();
    formMessage("#withdrawalMessage", `提现已提交，状态：${withdrawalStatusText(result.status)}`);
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
