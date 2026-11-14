// ============================================================
// Smart Campus Parking - frontend logic
// Talks to the Java backend purely over the /api/* JSON endpoints.
// The browser keeps the login session in a cookie, so fetch() calls
// are automatically "logged in" once the user has signed in.
// ============================================================

const jsonHeaders = { "Content-Type": "application/json" };
let currentUser = null;
let allSlots = [];          // last slot list fetched for the Live parking view

async function toJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const err = new Error(data.error || "Something went wrong");
    err.status = response.status;
    throw err;
  }
  return data;
}

const post = (url, payload) =>
  fetch(url, { method: "POST", headers: jsonHeaders, body: JSON.stringify(payload || {}) }).then(toJson);

const api = {
  me: () => fetch("/api/me").then(toJson),
  login: (username, password) => post("/api/login", { username, password }),
  register: (username, password, fullName) => post("/api/register", { username, password, fullName }),
  logout: () => post("/api/logout"),
  slots: () => fetch("/api/slots").then(toJson),
  rates: () => fetch("/api/rates").then(toJson),
  parked: () => fetch("/api/parked").then(toJson),
  history: (limit = 20) => fetch(`/api/history?limit=${limit}`).then(toJson),
  report: () => fetch("/api/report").then(toJson),
  locate: (vehicleNumber) => fetch(`/api/locate?vehicleNumber=${encodeURIComponent(vehicleNumber)}`).then(toJson),
  park: (payload) => post("/api/park", payload),
  unpark: (vehicleNumber) => post("/api/unpark", { vehicleNumber }),
  revenue: () => fetch("/api/admin/revenue").then(toJson),
  addSlot: (payload) => post("/api/admin/slots", payload),
  deleteSlot: (id) => fetch(`/api/admin/slots/${id}`, { method: "DELETE" }).then(toJson),
};

// ---------- Small helpers ----------

// Everything that came from a user (vehicle numbers, names, locations) is escaped before it is put
// into the page, so nobody can inject HTML/JavaScript through a form field.
function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

const $ = (id) => document.getElementById(id);
const money = (amount) => "₹" + Number(amount ?? 0).toFixed(2);
const icon = (name) => `<svg class="ico" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const TYPE_LABEL = { CAR: "Car", BIKE: "Bike", BUS: "Bus", EV: "EV" };
const TYPE_COLOR = { CAR: "var(--t-car)", BIKE: "var(--t-bike)", BUS: "var(--t-bus)", EV: "var(--t-ev)" };
const typeLabel = (t) => TYPE_LABEL[t] || t;

function plate(number) {
  return `<span class="plate">${esc(number)}</span>`;
}

function parseTime(str) {
  return new Date(String(str).replace(" ", "T"));
}

function sinceLabel(entryTimeStr) {
  const minutes = Math.max(0, Math.round((Date.now() - parseTime(entryTimeStr).getTime()) / 60000));
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

function timeAgo(str) {
  const d = parseTime(str);
  if (isNaN(d)) return esc(str);
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  if (mins < 1440) return `${Math.floor(mins / 60)} h ago`;
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}

function showToast(message, type = "success") {
  const toast = $("toast");
  toast.textContent = message;
  toast.className = `toast ${type}`;
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => toast.classList.add("hidden"), 3800);
}

// If the server says "please log in" (session expired / server restarted), go back to the login screen.
function handleError(e) {
  if (e.status === 401) {
    showAuth();
  } else {
    showToast(e.message, "error");
  }
}

function setNotice(box, kind, html) {
  box.className = `notice notice-${kind}`;
  box.innerHTML = html;
}

const isAdmin = () => currentUser && currentUser.role === "ADMIN";

// ============================================================
// Login / sign up / logout
// ============================================================

function showAuth() {
  currentUser = null;
  $("app").classList.add("hidden");
  $("auth-screen").classList.remove("hidden");
  setAuthMode("login");   // always start on the log-in tab, e.g. after a session expires
}

function showApp(user) {
  currentUser = user;
  $("auth-screen").classList.add("hidden");
  $("app").classList.remove("hidden");

  $("user-name").textContent = user.fullName;
  $("user-role").textContent = isAdmin() ? "Administrator" : "Student";
  $("avatar").textContent = (user.fullName || user.username || "?").trim().charAt(0).toUpperCase();
  $("today").textContent = new Date().toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  document.querySelectorAll(".admin-only").forEach((el) => el.classList.toggle("hidden", !isAdmin()));
  $("parked-title").textContent = isAdmin() ? "Parked now (all vehicles)" : "My parked vehicles";
  $("history-title").textContent = isAdmin() ? "Parking history (all vehicles)" : "My parking history";

  navigate("overview");
  loadRates();
}

function setAuthMode(mode) {
  const login = mode === "login";
  $("login-form").classList.toggle("hidden", !login);
  $("register-form").classList.toggle("hidden", login);
  $("show-login").classList.toggle("active", login);
  $("show-register").classList.toggle("active", !login);
  $("auth-error").classList.add("hidden");
}
$("show-login").addEventListener("click", () => setAuthMode("login"));
$("show-register").addEventListener("click", () => setAuthMode("register"));

function showAuthError(message) {
  const box = $("auth-error");
  box.textContent = message;
  box.classList.remove("hidden");
}

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const user = await api.login($("l-username").value.trim(), $("l-password").value);
    e.target.reset();
    showApp(user);
  } catch (err) {
    showAuthError(err.message);
  }
});

$("register-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const user = await api.register($("r-username").value.trim(), $("r-password").value, $("r-fullname").value.trim());
    e.target.reset();
    showApp(user);
  } catch (err) {
    showAuthError(err.message);
  }
});

$("logout-btn").addEventListener("click", async () => {
  try {
    await api.logout();
  } finally {
    showAuth();
    setAuthMode("login");
  }
});

// ============================================================
// Navigation
// ============================================================

function greeting() {
  const h = new Date().getHours();
  const part = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
  const name = isAdmin() ? "Admin" : (currentUser.fullName || "").trim().split(/\s+/)[0];
  return `${part}, ${name}`;
}

const VIEWS = {
  overview: {
    title: () => greeting(),
    sub: () => (isAdmin() ? "Here is what is happening in campus parking today." : "Here is today's availability and your parked vehicles."),
    load: () => loadOverview(),
  },
  live: { title: () => "Live parking", sub: () => "Every slot on campus. Select a free slot to park that type of vehicle.", load: () => loadLive() },
  park: { title: () => "Park a vehicle", sub: () => "A free slot that matches the vehicle type is allocated automatically.", load: () => {} },
  finder: { title: () => "Find my vehicle", sub: () => "See where a vehicle is parked, or leave and see the fee.", load: () => {} },
  reports: { title: () => "Reports", sub: () => (isAdmin() ? "Occupancy, arrivals and parking history." : "Your parking visits, time parked and fees."), load: () => loadReports() },
  manage: { title: () => "Manage slots", sub: () => "Add or remove parking slots and see the fees collected.", load: () => loadManage() },
};

function navigate(name) {
  if (name === "manage" && !isAdmin()) name = "overview";
  const view = VIEWS[name];
  document.querySelectorAll("#tabs .nav-item").forEach((b) => {
    const on = b.dataset.view === name;
    b.classList.toggle("active", on);
    if (on) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("active", v.id === `view-${name}`));
  $("page-title").textContent = view.title();
  $("page-sub").textContent = view.sub();
  view.load();
}

document.querySelectorAll("#tabs .nav-item").forEach((btn) => btn.addEventListener("click", () => navigate(btn.dataset.view)));
document.querySelectorAll("[data-goto]").forEach((btn) => btn.addEventListener("click", () => navigate(btn.dataset.goto)));

// ============================================================
// Overview
// ============================================================

async function loadOverview() {
  try {
    const [slots, parked, history] = await Promise.all([api.slots(), api.parked(), api.history(30)]);
    renderOverviewStats(slots);
    $("zone-grid").innerHTML = renderZoneCards(slots);
    renderActivity(history);
    renderParked(parked);
    renderDonut(occupancyByType(slots));
  } catch (e) {
    handleError(e);
  }
}

function statCard(tint, iconName, label, value) {
  return `<div class="stat tint-${tint}">
    <div class="stat-icon">${icon(iconName)}</div>
    <div><div class="stat-label">${esc(label)}</div><div class="stat-value">${esc(value)}</div></div>
  </div>`;
}

function renderOverviewStats(slots) {
  const total = slots.length;
  const busy = slots.filter((s) => s.occupied).length;
  const pct = total ? Math.round((busy / total) * 100) : 0;
  $("stat-row").innerHTML =
    statCard("blue", "parking", "Total slots", total) +
    statCard("green", "check", "Available", total - busy) +
    statCard("red", "car", "Occupied", busy) +
    statCard("purple", "pie", "Occupancy", pct + "%");
}

function groupByLocation(slots) {
  const groups = new Map();
  slots.forEach((s) => {
    if (!groups.has(s.location)) groups.set(s.location, []);
    groups.get(s.location).push(s);
  });
  return groups;
}

function renderZoneCards(slots) {
  if (slots.length === 0) {
    return `<p class="muted">No slots yet. An admin can add slots under Manage slots.</p>`;
  }
  return [...groupByLocation(slots)]
    .map(([location, list]) => {
      const busy = list.filter((s) => s.occupied).length;
      const free = list.length - busy;
      const pct = Math.round((busy / list.length) * 100);
      const level = pct >= 90 ? "high" : pct >= 70 ? "mid" : "";
      const types = [...new Set(list.map((s) => typeLabel(s.vehicleType)))].join(", ");
      return `<div class="zone">
        <div class="zone-top">
          <div><div class="zone-name">${esc(location)}</div><div class="zone-type">${esc(types)}</div></div>
          <div class="zone-free ${free === 0 ? "full" : ""}">${free === 0 ? "Full" : free + " free"}</div>
        </div>
        <div class="meter" role="img" aria-label="${pct}% occupied"><i class="${level}" style="width:${pct}%"></i></div>
        <div class="mini">${list.map((s) => `<i class="${s.occupied ? "busy" : ""}" title="${esc(s.slotNumber)}"></i>`).join("")}</div>
      </div>`;
    })
    .join("");
}

function renderActivity(history) {
  const events = [];
  history.forEach((r) => {
    events.push({ time: r.entryTime, kind: "in", r });
    if (r.exitTime) events.push({ time: r.exitTime, kind: "out", r });
  });
  events.sort((a, b) => b.time.localeCompare(a.time));
  const list = events.slice(0, 6);
  if (list.length === 0) {
    $("activity").innerHTML = `<li><div class="act-main"><div class="act-title">Nothing yet</div><div class="act-sub">Parked and departed vehicles will show up here.</div></div></li>`;
    return;
  }
  $("activity").innerHTML = list
    .map(({ time, kind, r }) => {
      const title = kind === "in" ? `${esc(r.vehicleNumber)} parked` : `${esc(r.vehicleNumber)} left`;
      const sub = kind === "in"
        ? `Slot ${esc(r.slotNumber)}, ${esc(r.location)}`
        : `Slot ${esc(r.slotNumber)} freed, fee ${money(r.feeAmount)}`;
      return `<li>
        <i class="dot ${kind === "in" ? "dot-in" : "dot-out"}"></i>
        <div class="act-main"><div class="act-title">${title}</div><div class="act-sub">${sub}</div></div>
        <div class="act-time">${timeAgo(time)}</div>
      </li>`;
    })
    .join("");
}

function renderParked(records) {
  const body = $("parked-body");
  if (records.length === 0) {
    body.innerHTML = `<tr><td class="empty" colspan="6">No vehicles are parked right now. Use Park a vehicle to add one.</td></tr>`;
    return;
  }
  body.innerHTML = records
    .map((r) => `<tr>
      <td>${plate(r.vehicleNumber)}</td>
      <td>${esc(r.slotNumber)}</td>
      <td>${esc(r.location)}</td>
      <td>${esc(r.entryTime)}</td>
      <td>${esc(sinceLabel(r.entryTime))}</td>
      <td class="act"><button class="btn btn-danger btn-sm" data-vehicle="${esc(r.vehicleNumber)}">Unpark</button></td>
    </tr>`)
    .join("");
  body.querySelectorAll("button[data-vehicle]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      try {
        const result = await api.unpark(btn.dataset.vehicle);
        showToast(`${result.vehicleNumber} left slot ${result.slotNumber} after ${result.durationMinutes} min. Fee: ${money(result.feeAmount)}`);
        loadOverview();
      } catch (e) {
        handleError(e);
      }
    });
  });
}

// Per-type occupied counts built from the slot list, so students never need the admin-only report.
function occupancyByType(slots) {
  const out = {};
  slots.forEach((s) => {
    const row = out[s.vehicleType] || (out[s.vehicleType] = { totalSlots: 0, occupiedSlots: 0 });
    row.totalSlots += 1;
    if (s.occupied) row.occupiedSlots += 1;
  });
  return out;
}

function renderDonut(report) {
  const types = Object.keys(report).filter((k) => k !== "OVERALL");
  const total = types.reduce((sum, t) => sum + report[t].occupiedSlots, 0);
  let offset = 0;
  const arcs = total === 0 ? "" : types
    .filter((t) => report[t].occupiedSlots > 0)
    .map((t) => {
      const share = (report[t].occupiedSlots / total) * 100;
      const arc = `<circle cx="18" cy="18" r="15.9155" stroke="${TYPE_COLOR[t] || "#999"}" stroke-dasharray="${share} ${100 - share}" stroke-dashoffset="${-offset}"/>`;
      offset += share;
      return arc;
    })
    .join("");
  $("donut").innerHTML = `
    <div class="donut">
      <svg viewBox="0 0 36 36" role="img" aria-label="${total} vehicles parked"><circle cx="18" cy="18" r="15.9155" stroke="#edf1f7"/>${arcs}</svg>
      <div class="donut-center"><div><b>${total}</b><span>parked</span></div></div>
    </div>
    <ul class="donut-legend">
      ${types.map((t) => `<li><i style="background:${TYPE_COLOR[t] || "#999"}"></i>${esc(typeLabel(t))}<b>${report[t].occupiedSlots}</b></li>`).join("")}
    </ul>`;
}

$("refresh-parked").addEventListener("click", loadOverview);

// ============================================================
// Live parking
// ============================================================

async function loadLive() {
  try {
    allSlots = await api.slots();
    renderLive();
  } catch (e) {
    handleError(e);
  }
}

function renderLive() {
  const type = $("slot-filter").value;
  const status = $("status-filter").value;
  const query = $("slot-search").value.trim().toLowerCase();
  const slots = allSlots.filter((s) =>
    (!type || s.vehicleType === type) &&
    (!status || (status === "busy") === s.occupied) &&
    (!query || s.slotNumber.toLowerCase().includes(query)));

  const box = $("live-zones");
  if (slots.length === 0) {
    box.innerHTML = `<div class="card"><p class="muted">No slots match these filters.</p></div>`;
    return;
  }
  box.innerHTML = [...groupByLocation(slots)]
    .map(([location, list]) => {
      const free = list.filter((s) => !s.occupied).length;
      const tiles = list
        .map((s) => s.occupied
          ? `<div class="tile busy"><b>${esc(s.slotNumber)}</b><span>${esc(typeLabel(s.vehicleType))}, occupied</span></div>`
          : `<button type="button" class="tile free" data-type="${esc(s.vehicleType)}" title="Park a ${esc(typeLabel(s.vehicleType))} here"><b>${esc(s.slotNumber)}</b><span>${esc(typeLabel(s.vehicleType))}, free</span></button>`)
        .join("");
      return `<div class="card"><div class="zone-section-head"><h2>${esc(location)}</h2><span>${free} of ${list.length} free</span></div><div class="tiles">${tiles}</div></div>`;
    })
    .join("");
  box.querySelectorAll("button.tile.free").forEach((btn) => btn.addEventListener("click", () => goToPark(btn.dataset.type)));
}

["slot-filter", "status-filter"].forEach((id) => $(id).addEventListener("change", renderLive));
$("slot-search").addEventListener("input", renderLive);

function goToPark(type) {
  const radio = document.querySelector(`input[name="vtype"][value="${type}"]`);
  if (radio) radio.checked = true;
  navigate("park");
  $("p-number").focus();
}

// ============================================================
// Park a vehicle
// ============================================================

async function loadRates() {
  try {
    const rates = await api.rates();
    $("rates-box").innerHTML = Object.keys(rates)
      .map((type) => `<li><span>${esc(typeLabel(type))}</span><b>${money(rates[type])} / hour</b></li>`)
      .join("");
  } catch (e) {
    $("rates-box").innerHTML = "";
  }
}

$("park-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const payload = {
    vehicleNumber: $("p-number").value.trim(),
    vehicleType: document.querySelector('input[name="vtype"]:checked').value,
    ownerName: $("p-owner").value.trim(),
    ownerContact: $("p-contact").value.trim(),
  };
  const box = $("park-result");
  try {
    const record = await api.park(payload);
    setNotice(box, "ok", `<strong>${esc(record.vehicleNumber)}</strong> is parked at slot <strong>${esc(record.slotNumber)}</strong> (${esc(record.location)}).<br>Entry time: ${esc(record.entryTime)}`);
    e.target.reset();
  } catch (err) {
    if (err.status === 401) return handleError(err);
    setNotice(box, "error", esc(err.message));
  }
});

// ============================================================
// Vehicle finder
// ============================================================

$("find-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const vehicleNumber = $("f-number").value.trim();
  const box = $("find-result");
  try {
    const record = await api.locate(vehicleNumber);
    box.className = "find-result";
    box.innerHTML = `
      <div class="card">
        <div class="card-head"><h2>Vehicle details</h2></div>
        ${plate(record.vehicleNumber).replace('class="plate"', 'class="plate plate-lg"')}
        <dl class="detail-list">
          <div><dt>${icon("parking")}Slot</dt><dd>${esc(record.slotNumber)}</dd></div>
          <div><dt>${icon("pin")}Location</dt><dd>${esc(record.location)}</dd></div>
          <div><dt>${icon("clock")}Parked at</dt><dd>${esc(record.entryTime)}</dd></div>
          <div><dt>${icon("clock")}Parked for</dt><dd>${esc(sinceLabel(record.entryTime))}</dd></div>
        </dl>
      </div>
      <div class="card" id="leave-card">
        <div class="card-head"><h2>Leaving?</h2></div>
        <p class="muted">Unpark to end the session and free the slot. The fee is worked out from the time parked.</p>
        <p style="margin-top:16px"><button class="btn btn-primary btn-block" id="finder-unpark">Unpark and show fee</button></p>
      </div>`;
    $("finder-unpark").addEventListener("click", async () => {
      try {
        const result = await api.unpark(record.vehicleNumber);
        showToast(`${result.vehicleNumber} left slot ${result.slotNumber} after ${result.durationMinutes} min. Fee: ${money(result.feeAmount)}`);
        $("leave-card").innerHTML = `
          <div class="card-head"><h2>Fee to pay</h2></div>
          <div class="pay-line"><span class="pay-amount">${money(result.feeAmount)}</span><span class="muted">${esc(result.durationMinutes)} min parked</span></div>
          <div class="notice notice-ok" style="margin-top:0">Slot ${esc(result.slotNumber)} is free again.</div>`;
      } catch (err) {
        handleError(err);
      }
    });
  } catch (err) {
    if (err.status === 401) return handleError(err);
    box.className = "find-result single";
    box.innerHTML = `<div class="notice notice-error">${esc(err.message)}</div>`;
  }
});

// ============================================================
// Reports
// ============================================================

async function loadReports() {
  try {
    $("type-title").textContent = isAdmin() ? "Occupancy by vehicle type" : "My visits by vehicle type";
    $("hour-title").textContent = isAdmin() ? "Arrivals today, by hour" : "My arrivals today, by hour";
    if (isAdmin()) {
      const [report, history, revenue] = await Promise.all([api.report(), api.history(200), api.revenue()]);
      const overall = report.OVERALL;
      const parkedNow = history.filter((r) => r.active).length;
      $("report-stats").innerHTML =
        statCard("purple", "pie", "Overall occupancy", overall.occupancyPercent + "%") +
        statCard("green", "check", "Free slots", overall.availableSlots) +
        statCard("blue", "car", "Vehicles parked now", parkedNow) +
        statCard("amber", "rupee", "Fees collected", money(revenue.totalRevenue));
      renderTypeBars(report);
      renderHourChart(history);
      renderHistory(history);
    } else {
      // Students only get figures about their own vehicles (the history API is already scoped to them).
      const [slots, history] = await Promise.all([api.slots(), api.history(200)]);
      const parkedNow = history.filter((r) => r.active).length;
      const minutes = history.reduce((sum, r) => sum + Number(r.durationMinutes || 0), 0);
      const fees = history.reduce((sum, r) => sum + Number(r.feeAmount || 0), 0);
      $("report-stats").innerHTML =
        statCard("blue", "car", "My vehicles parked now", parkedNow) +
        statCard("purple", "pie", "My total visits", history.length) +
        statCard("green", "clock", "Time parked", fmtMinutes(minutes)) +
        statCard("amber", "rupee", "Fees paid", money(fees));
      renderMyTypeBars(history, slots);
      renderHourChart(history);
      renderHistory(history);
    }
  } catch (e) {
    handleError(e);
  }
}

function fmtMinutes(total) {
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h ? `${h} h ${m} min` : `${m} min`;
}

function renderMyTypeBars(history, slots) {
  const typeOfSlot = {};
  slots.forEach((s) => { typeOfSlot[s.slotNumber] = s.vehicleType; });
  const counts = {};
  history.forEach((r) => {
    const t = typeOfSlot[r.slotNumber];
    if (t) counts[t] = (counts[t] || 0) + 1;
  });
  const types = Object.keys(counts);
  if (types.length === 0) {
    $("report-grid").innerHTML = `<div class="chart-empty">No visits yet.</div>`;
    return;
  }
  const max = Math.max(...Object.values(counts));
  $("report-grid").innerHTML = types
    .map((t) => `<div class="bar-col">
        <div class="bar-value">${counts[t]}</div>
        <div class="bar-track"><div class="bar-fill" style="height:${Math.max(8, (counts[t] / max) * 100)}%;background:${TYPE_COLOR[t] || "#999"}"></div></div>
        <div class="bar-label">${esc(typeLabel(t))}</div>
        <div class="bar-sub">${counts[t] === 1 ? "1 visit" : counts[t] + " visits"}</div>
      </div>`)
    .join("");
}

function renderTypeBars(report) {
  const types = Object.keys(report).filter((k) => k !== "OVERALL");
  $("report-grid").innerHTML = types
    .map((t) => {
      const s = report[t];
      return `<div class="bar-col">
        <div class="bar-value">${esc(s.occupancyPercent)}%</div>
        <div class="bar-track"><div class="bar-fill" style="height:${Number(s.occupancyPercent)}%;background:${TYPE_COLOR[t] || "#999"}"></div></div>
        <div class="bar-label">${esc(typeLabel(t))}</div>
        <div class="bar-sub">${esc(s.occupiedSlots)} of ${esc(s.totalSlots)}</div>
      </div>`;
    })
    .join("");
}

function renderHourChart(history) {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  const counts = {};
  history.forEach((r) => {
    if (r.entryTime.startsWith(today)) {
      const hour = Number(r.entryTime.slice(11, 13));
      counts[hour] = (counts[hour] || 0) + 1;
    }
  });
  const hours = Object.keys(counts).map(Number);
  const box = $("hour-chart");
  if (hours.length === 0) {
    box.innerHTML = `<div class="chart-empty">No arrivals yet today.</div>`;
    return;
  }
  const from = Math.min(8, ...hours);
  const to = Math.max(18, ...hours);
  const max = Math.max(...Object.values(counts));
  let cols = "";
  for (let h = from; h <= to; h++) {
    const n = counts[h] || 0;
    cols += `<div class="bar-col">
      <div class="bar-value">${n || ""}</div>
      <div class="bar-track"><div class="bar-fill" style="height:${n ? Math.max(8, (n / max) * 100) : 0}%;background:var(--accent)"></div></div>
      <div class="bar-label">${pad(h)}</div>
    </div>`;
  }
  box.innerHTML = cols;
}

function renderHistory(history) {
  const body = $("history-body");
  if (history.length === 0) {
    body.innerHTML = `<tr><td class="empty" colspan="6">No parking history yet.</td></tr>`;
    return;
  }
  body.innerHTML = history
    .slice(0, 25)
    .map((r) => `<tr>
      <td>${plate(r.vehicleNumber)}</td>
      <td>${esc(r.slotNumber)}</td>
      <td>${esc(r.entryTime)}</td>
      <td>${r.exitTime ? esc(r.exitTime) : `<span class="badge badge-busy">Still parked</span>`}</td>
      <td>${r.durationMinutes != null ? esc(r.durationMinutes) + " min" : "-"}</td>
      <td class="num">${r.feeAmount != null ? money(r.feeAmount) : "-"}</td>
    </tr>`)
    .join("");
}
$("refresh-report").addEventListener("click", loadReports);

// ============================================================
// Manage slots (admin only)
// ============================================================

async function loadManage() {
  try {
    const [slots, revenue] = await Promise.all([api.slots(), api.revenue()]);
    const busy = slots.filter((s) => s.occupied).length;
    $("revenue-row").innerHTML =
      statCard("amber", "rupee", "Total fees collected", money(revenue.totalRevenue)) +
      statCard("blue", "parking", "Total slots", slots.length) +
      statCard("green", "check", "Free slots", slots.length - busy);

    $("manage-body").innerHTML = slots.length === 0
      ? `<tr><td class="empty" colspan="5">No slots yet. Add the first one above.</td></tr>`
      : slots.map((s) => `<tr>
          <td><strong>${esc(s.slotNumber)}</strong></td>
          <td>${esc(typeLabel(s.vehicleType))}</td>
          <td>${esc(s.location)}</td>
          <td><span class="badge ${s.occupied ? "badge-busy" : "badge-free"}">${s.occupied ? "Occupied" : "Free"}</span></td>
          <td class="act"><button class="btn btn-danger btn-sm" data-slot-id="${esc(s.slotId)}">${icon("trash")}Delete</button></td>
        </tr>`).join("");

    $("manage-body").querySelectorAll("button[data-slot-id]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        try {
          await api.deleteSlot(btn.dataset.slotId);
          showToast("Slot deleted.");
          loadManage();
        } catch (e) {
          handleError(e);
        }
      });
    });
  } catch (e) {
    handleError(e);
  }
}

$("slot-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const slot = await api.addSlot({
      slotNumber: $("s-number").value.trim(),
      vehicleType: $("s-type").value,
      location: $("s-location").value.trim(),
    });
    showToast(`Slot ${slot.slotNumber} added.`);
    e.target.reset();
    loadManage();
  } catch (err) {
    handleError(err);
  }
});
$("refresh-manage").addEventListener("click", loadManage);

// ============================================================
// Start: are we already logged in (e.g. page refresh)?
// ============================================================

api.me().then(showApp).catch(showAuth);
