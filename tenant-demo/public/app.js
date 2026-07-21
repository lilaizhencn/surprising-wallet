const state = { users: [], addresses: [], chains: [] };
const $ = selector => document.querySelector(selector);

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
  const [status, users, addresses] = await Promise.all([
    request("/api/status"), request("/api/users"), request("/api/addresses")
  ]);
  state.users = users;
  state.addresses = addresses;
  $("#statusDot").classList.toggle("ok", status.configured);
  $("#statusText").textContent = status.configured ? "钱包 API 已配置" : "等待连接配置";
  $("#summary").innerHTML = [
    [status.users, "交易所用户"], [status.addresses, "充值地址"], [status.events, "已接收回调"]
  ].map(([value, label]) => `<div class="metric"><strong>${value}</strong><span>${label}</span></div>`).join("");
  table("#usersTable", [
    { key: "externalId", label: "用户标识" }, { key: "displayName", label: "名称" },
    { key: "createdAt", label: "创建时间" }
  ], users);
  table("#addressesTable", [
    { key: "externalId", label: "用户" }, { key: "chain", label: "链" },
    { key: "network", label: "网络" }, { label: "地址", render: row => `<code>${escape(row.address)}</code>` },
    { key: "memo", label: "Memo" }, { key: "addressVersion", label: "版本" }
  ], addresses);
  options($("#addressUser"), users, row => `${row.externalId} · ${row.displayName}`);
  options($("#withdrawalAddress"), addresses, row => `${row.externalId} · ${row.chain} · ${row.address.slice(0, 12)}…`);
  if (status.configured) {
    try {
      state.chains = await request("/api/chains");
      options($("#addressChain"), state.chains, row => `${row.chain} · ${row.network}`);
    } catch (error) {
      toast(error.message, true);
    }
  }
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
    { key: "chain", label: "链" }, { key: "available", label: "可用" }, { key: "locked", label: "冻结" }
  ], balances);
  table("#ledgerTable", [
    { key: "createdAt", label: "时间" }, { key: "externalId", label: "用户" },
    { key: "entryType", label: "类型" }, { key: "asset", label: "资产" },
    { key: "chain", label: "链" }, { key: "direction", label: "方向" }, { key: "amount", label: "金额" }
  ], ledger);
  try {
    const remote = await request("/api/wallet/assets");
    table("#walletAssetsTable", Object.keys(remote[0] ?? { asset: "" }).slice(0, 7).map(key => ({ key, label: key })), remote);
  } catch (error) {
    $("#walletAssetsTable").innerHTML = `<div class="empty">${escape(error.message)}</div>`;
  }
}

async function refreshWithdrawals() {
  const rows = await request("/api/withdrawals");
  table("#withdrawalsTable", [
    { key: "createdAt", label: "时间" }, { key: "externalId", label: "用户" },
    { key: "asset", label: "资产" }, { key: "chain", label: "链" },
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
    await request(`/api/users/${encodeURIComponent(address.userId)}/withdrawals`, {
      method: "POST",
      body: JSON.stringify({ ...input, chain: address.chain })
    });
    await Promise.all([refreshWithdrawals(), refreshAssets()]);
    toast("提现请求已提交");
  } catch (error) { toast(error.message, true); }
});

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
