// ============================================================
// Smart Campus Parking - frontend logic
// Talks to the Java backend purely over the /api/* JSON endpoints.
// The browser keeps the login session in a cookie, so fetch() calls
// are automatically "logged in" once the user has signed in.
// ============================================================

const jsonHeaders = { "Content-Type": "application/json" };
let currentUser = null;

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
  slots: (type) => fetch(`/api/slots${type ? "?type=" + encodeURIComponent(type) : ""}`).then(toJson),
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

// Everything that came from a user (vehicle numbers, names, locations) is escaped before it is put
// into the page, so nobody can inject HTML/JavaScript through a form field.
function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

const money = (amount) => "₹" + Number(amount ?? 0).toFixed(2);
const $ = (id) => document.getElementById(id);

function showToast(message, type = "success") {
  const toast = $("toast");
  toast.textContent = message;
  toast.className = `toast ${type}`;
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => toast.classList.add("hidden"), 3500);
}

// If the server says "please log in" (session expired / server restarted), go back to the login screen.
function handleError(e) {
  if (e.status === 401) {
    showAuth();
  } else {
    showToast(e.message, "error");
  }
}

// ============================================================
// Login / sign up / logout
// ============================================================

function showAuth() {
  currentUser = null;
  $("app").classList.add("hidden");
  $("auth-screen").classList.remove("hidden");
}

function showApp(user) {
  currentUser = user;
  $("auth-screen").classList.add("hidden");
  $("app").classList.remove("hidden");

  const isAdmin = user.role === "ADMIN";
  $("user-name").textContent = user.fullName;
  $("user-role").textContent = user.role;
  document.querySelectorAll(".admin-only").forEach((el) => el.classList.toggle("hidden", !isAdmin));
  $("parked-title").textContent = isAdmin ? "Currently Parked (all vehicles)" : "My Parked Vehicles";
  $("history-title").textContent = isAdmin ? "Recent History (all vehicles)" : "My Parking History";

  activateTab("dashboard");
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
  box.textContent = "❌ " + message;
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
// Tab navigation
// ============================================================

function activateTab(name) {
  document.querySelectorAll("#tabs .tab").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("active", v.id === `view-${name}`));
  if (name === "dashboard") loadDashboard();
  if (name === "report") loadReport();
  if (name === "manage") loadManage();
}

document.querySelectorAll("#tabs .tab").forEach((btn) => {
  btn.addEventListener("click", () => activateTab(btn.dataset.view));
});

// ============================================================
// Dashboard
// ============================================================

async function loadDashboard() {
  try {
    const [slots, parked] = await Promise.all([
      api.slots(),
      api.parked(),
    ]);
    const filter = $("slot-filter").value;
    // Stats always cover every slot; the filter only changes which slots are drawn in the grid.
    renderStats(slots, parked);
    renderSlots(filter ? slots.filter((s) => s.vehicleType === filter) : slots);
    renderParked(parked);
  } catch (e) {
    handleError(e);
  }
}

function renderStats(slots, parked) {
  const total = slots.length;
  const occupied = slots.filter((s) => s.occupied).length;
  const cards = [
    { label: "Total Slots", value: total },
    { label: "Occupied", value: occupied },
    { label: "Available", value: total - occupied },
    { label: currentUser.role === "ADMIN" ? "Vehicles Parked Now" : "My Vehicles Parked", value: parked.length },
  ];
  $("stat-row").innerHTML = cards
    .map((c) => `<div class="stat-card"><div class="label">${esc(c.label)}</div><div class="value">${esc(c.value)}</div></div>`)
    .join("");
}

function renderSlots(slots) {
  const grid = $("slot-grid");
  if (slots.length === 0) {
    grid.innerHTML = `<p style="color:var(--text-dim)">No slots to show.</p>`;
    return;
  }
  grid.innerHTML = slots
    .map((s) => `<div class="slot ${s.occupied ? "occupied" : ""}" title="${esc(s.location)} - ${s.occupied ? "Occupied" : "Free"}">
        <span class="num">${esc(s.slotNumber)}</span><span class="type">${esc(s.vehicleType)}</span></div>`)
    .join("");
}

function renderParked(records) {
  const body = $("parked-body");
  if (records.length === 0) {
    body.innerHTML = `<tr class="empty-row"><td colspan="6">No vehicles are currently parked.</td></tr>`;
    return;
  }
  body.innerHTML = records
    .map((r) => `<tr>
      <td>${esc(r.vehicleNumber)}</td>
      <td>${esc(r.slotNumber)}</td>
      <td>${esc(r.location)}</td>
      <td>${esc(r.entryTime)}</td>
      <td>${esc(sinceLabel(r.entryTime))}</td>
      <td><button class="btn-small" data-vehicle="${esc(r.vehicleNumber)}">Unpark</button></td>
    </tr>`)
    .join("");
  body.querySelectorAll(".btn-small").forEach((btn) => {
    btn.addEventListener("click", async () => {
      try {
        const result = await api.unpark(btn.dataset.vehicle);
        showToast(`${result.vehicleNumber} left slot ${result.slotNumber} after ${result.durationMinutes} min. Fee: ${money(result.feeAmount)}`);
        loadDashboard();
      } catch (e) {
        handleError(e);
      }
    });
  });
}

function sinceLabel(entryTimeStr) {
  const entry = new Date(entryTimeStr.replace(" ", "T"));
  const minutes = Math.max(0, Math.round((Date.now() - entry.getTime()) / 60000));
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} hr ${minutes % 60} min`;
}

$("slot-filter").addEventListener("change", loadDashboard);
$("refresh-parked").addEventListener("click", loadDashboard);

// ============================================================
// Park a vehicle
// ============================================================

async function loadRates() {
  try {
    const rates = await api.rates();
    const items = Object.keys(rates)
      .map((type) => `<span class="rate-pill">${esc(type)} <strong>${money(rates[type])}</strong>/hr</span>`)
      .join("");
    $("rates-box").innerHTML = `<div class="rates-title">Parking rates (charged per started hour, minimum 1 hour)</div>${items}`;
  } catch (e) {
    $("rates-box").innerHTML = "";
  }
}

$("park-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const payload = {
    vehicleNumber: $("p-number").value.trim(),
    vehicleType: $("p-type").value,
    ownerName: $("p-owner").value.trim(),
    ownerContact: $("p-contact").value.trim(),
  };
  const box = $("park-result");
  try {
    const record = await api.park(payload);
    box.className = "result-box";
    box.innerHTML = `✅ <strong>${esc(record.vehicleNumber)}</strong> has been parked at slot
      <strong>${esc(record.slotNumber)}</strong> (${esc(record.location)}).<br>Entry time: ${esc(record.entryTime)}`;
    e.target.reset();
  } catch (err) {
    if (err.status === 401) return handleError(err);
    box.className = "result-box error";
    box.textContent = "❌ " + err.message;
  }
  box.classList.remove("hidden");
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
    box.className = "result-box";
    box.innerHTML = `📍 <strong>${esc(record.vehicleNumber)}</strong> is parked at slot
      <strong>${esc(record.slotNumber)}</strong>, ${esc(record.location)}.<br>
      Entry time: ${esc(record.entryTime)}<br>Parked for: ${esc(sinceLabel(record.entryTime))}
      <br><br><button class="btn-small" id="finder-unpark">Unpark this vehicle &amp; pay</button>`;
    $("finder-unpark").addEventListener("click", async () => {
      try {
        const result = await api.unpark(record.vehicleNumber);
        showToast(`${result.vehicleNumber} left slot ${result.slotNumber} after ${result.durationMinutes} min. Fee: ${money(result.feeAmount)}`);
        box.innerHTML = `✅ <strong>${esc(result.vehicleNumber)}</strong> has exited slot ${esc(result.slotNumber)}.<br>
          Parked for ${esc(result.durationMinutes)} min &middot; <strong>Fee: ${money(result.feeAmount)}</strong>`;
      } catch (err) {
        handleError(err);
      }
    });
  } catch (err) {
    if (err.status === 401) return handleError(err);
    box.className = "result-box error";
    box.textContent = "❌ " + err.message;
  }
  box.classList.remove("hidden");
});

// ============================================================
// Occupancy report + history
// ============================================================

async function loadReport() {
  try {
    const report = await api.report();
    $("report-grid").innerHTML = Object.keys(report)
      .map((key) => {
        const stat = report[key];
        return `<div class="report-card">
          <div class="title">${esc(key)}</div>
          <div class="pct">${esc(stat.occupancyPercent)}%</div>
          <div class="bar"><div class="bar-fill" style="width:${Number(stat.occupancyPercent)}%"></div></div>
          <div class="detail">${esc(stat.occupiedSlots)} / ${esc(stat.totalSlots)} occupied &middot; ${esc(stat.availableSlots)} free</div>
        </div>`;
      })
      .join("");

    const history = await api.history(25);
    const body = $("history-body");
    if (history.length === 0) {
      body.innerHTML = `<tr class="empty-row"><td colspan="6">No parking history yet.</td></tr>`;
      return;
    }
    body.innerHTML = history
      .map((r) => `<tr>
        <td>${esc(r.vehicleNumber)}</td>
        <td>${esc(r.slotNumber)}</td>
        <td>${esc(r.entryTime)}</td>
        <td>${esc(r.exitTime ?? "Still parked")}</td>
        <td>${r.durationMinutes != null ? esc(r.durationMinutes) + " min" : "-"}</td>
        <td>${r.feeAmount != null ? money(r.feeAmount) : "-"}</td>
      </tr>`)
      .join("");
  } catch (e) {
    handleError(e);
  }
}
$("refresh-report").addEventListener("click", loadReport);

// ============================================================
// Manage slots (admin only)
// ============================================================

async function loadManage() {
  try {
    const [slots, revenue] = await Promise.all([api.slots(), api.revenue()]);
    $("revenue-row").innerHTML = `
      <div class="stat-card"><div class="label">Total Fees Collected</div><div class="value">${money(revenue.totalRevenue)}</div></div>
      <div class="stat-card"><div class="label">Total Slots</div><div class="value">${slots.length}</div></div>`;

    $("manage-body").innerHTML = slots
      .map((s) => `<tr>
        <td>${esc(s.slotNumber)}</td>
        <td>${esc(s.vehicleType)}</td>
        <td>${esc(s.location)}</td>
        <td>${s.occupied ? "Occupied" : "Free"}</td>
        <td><button class="btn-small" data-slot-id="${esc(s.slotId)}">Delete</button></td>
      </tr>`)
      .join("");

    $("manage-body").querySelectorAll(".btn-small").forEach((btn) => {
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
