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
  register: (payload) => post("/api/register", payload),   // { fullName, email, username, password, role, rollNo }
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
  dashboard: () => fetch("/api/admin/dashboard").then(toJson),
  setMaintenance: (slotId, outOfService) => post("/api/admin/maintenance", { slotId: String(slotId), outOfService: String(outOfService) }),
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

const REMEMBER_KEY = "scp.rememberedUsername";   // only the username is remembered, never the password
const EMAIL_RE = /^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$/;
const USERNAME_RE = /^[A-Za-z0-9_.]{3,30}$/;
const ROLL_NO_RE = /^[A-Za-z0-9\/_\-]{3,30}$/;

const ROLE_LABEL = { ADMIN: "Administrator", FACULTY: "Faculty", STUDENT: "Student" };
const roleLabel = (user) => ROLE_LABEL[user.role] || "Student";

// localStorage can be unavailable (private mode, blocked cookies), so every access is guarded.
function readRemembered() {
  try { return localStorage.getItem(REMEMBER_KEY) || ""; } catch (e) { return ""; }
}
function writeRemembered(username) {
  try {
    if (username) localStorage.setItem(REMEMBER_KEY, username); else localStorage.removeItem(REMEMBER_KEY);
  } catch (e) { /* not fatal */ }
}

function showAuth() {
  currentUser = null;
  $("app").classList.add("hidden");
  $("auth-screen").classList.remove("hidden");
  resetAuthForms();
  setAuthMode("login");   // always start on the log-in tab, e.g. after a session expires
}

function showApp(user) {
  currentUser = user;
  $("auth-screen").classList.add("hidden");
  $("app").classList.remove("hidden");

  $("user-name").textContent = user.fullName;
  $("user-name").title = user.email || "";
  $("user-role").textContent = roleLabel(user);
  // Students also see their roll number (accounts made before roll numbers existed just don't have one).
  const roll = user.role === "STUDENT" && user.rollNo ? "Roll no " + user.rollNo : "";
  $("user-roll").textContent = roll;
  $("user-roll").classList.toggle("hidden", !roll);
  $("avatar").textContent = (user.fullName || user.username || "?").trim().charAt(0).toUpperCase();
  $("today").textContent = new Date().toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  document.querySelectorAll(".admin-only").forEach((el) => el.classList.toggle("hidden", !isAdmin()));
  // Admins supervise only: no parking or vehicle-finder screens for them.
  document.querySelectorAll(".user-only").forEach((el) => el.classList.toggle("hidden", isAdmin()));
  $("parked-title").textContent = isAdmin() ? "Parked now (all vehicles)" : "My parked vehicles";
  $("history-title").textContent = isAdmin() ? "Parking history (all vehicles)" : "My parking history";

  navigate(isAdmin() ? "dashboard" : "overview");
  if (!isAdmin()) loadRates();
}

function resetAuthForms() {
  $("login-form").reset();
  $("register-form").reset();
  setSignupRole("STUDENT");
  document.querySelectorAll(".pw-toggle").forEach((btn) => setPasswordVisible(btn, false));
  const remembered = readRemembered();
  if (remembered) {
    $("l-username").value = remembered;
    $("l-remember").checked = true;
  }
}

function setAuthMode(mode) {
  const login = mode === "login";
  $("login-form").classList.toggle("hidden", !login);
  $("register-form").classList.toggle("hidden", login);
  $("foot-login").classList.toggle("hidden", !login);
  $("foot-register").classList.toggle("hidden", login);
  $("auth-hint").classList.toggle("hidden", !login);
  $("show-login").classList.toggle("active", login);
  $("show-register").classList.toggle("active", !login);
  $("show-login").setAttribute("aria-selected", String(login));
  $("show-register").setAttribute("aria-selected", String(!login));
  $("auth-title").textContent = login ? "Welcome Back!" : "Create Your Account";
  $("auth-sub").textContent = login ? "Sign in to your Smart Campus Parking account" : "Join Smart Campus Parking today";
  $("auth-error").classList.add("hidden");
}
document.querySelectorAll("[data-auth-mode]").forEach((el) => el.addEventListener("click", () => setAuthMode(el.dataset.authMode)));

// Student / Faculty switch: faculty have no roll number, so that field disappears (and is not submitted).
function setSignupRole(role) {
  const student = role !== "FACULTY";
  const radio = document.querySelector(`input[name="r-role"][value="${student ? "STUDENT" : "FACULTY"}"]`);
  if (radio) radio.checked = true;
  $("r-roll-field").classList.toggle("hidden", !student);
  $("r-rollno").disabled = !student;
  if (!student) $("r-rollno").value = "";
}
document.querySelectorAll('input[name="r-role"]').forEach((radio) =>
  radio.addEventListener("change", () => { setSignupRole(radio.value); $("auth-error").classList.add("hidden"); }));

function setPasswordVisible(btn, visible) {
  $(btn.dataset.target).type = visible ? "text" : "password";
  btn.setAttribute("aria-pressed", String(visible));
  btn.setAttribute("aria-label", visible ? "Hide password" : "Show password");
  btn.querySelector("use").setAttribute("href", visible ? "#i-eye-off" : "#i-eye");
}
document.querySelectorAll(".pw-toggle").forEach((btn) =>
  btn.addEventListener("click", () => setPasswordVisible(btn, btn.getAttribute("aria-pressed") !== "true")));

// These two are not wired to a backend yet, so say so instead of silently doing nothing.
$("forgot-btn").addEventListener("click", () => showToast("Password reset isn't available yet. Please ask the campus admin to reset it.", "info"));
$("google-btn").addEventListener("click", () => showToast("Google sign-in isn't set up yet. Please log in with your username and password.", "info"));

function showAuthError(message) {
  const box = $("auth-error");
  box.textContent = message;
  box.classList.remove("hidden");
}

// Show a validation message and put the cursor in the field that needs fixing.
function failField(inputId, message) {
  showAuthError(message);
  $(inputId).focus();
}

// Disables the submit button while a request is in flight so a double click can't send it twice.
async function withBusy(form, work) {
  const btn = form.querySelector('button[type="submit"]');
  btn.disabled = true;
  try {
    await work();
  } finally {
    btn.disabled = false;
  }
}

$("login-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const username = $("l-username").value.trim();
  const password = $("l-password").value;
  if (!username) return failField("l-username", "Enter your username");
  if (!password) return failField("l-password", "Enter your password");
  withBusy(e.target, async () => {
    try {
      const user = await api.login(username, password);
      writeRemembered($("l-remember").checked ? username : "");
      e.target.reset();
      showApp(user);
    } catch (err) {
      showAuthError(err.message);
    }
  });
});

$("register-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const role = document.querySelector('input[name="r-role"]:checked').value;
  const student = role === "STUDENT";
  const fullName = $("r-fullname").value.trim();
  const email = $("r-email").value.trim();
  const rollNo = $("r-rollno").value.trim();
  const username = $("r-username").value.trim();
  const password = $("r-password").value;
  const confirm = $("r-confirm").value;

  // Same rules the server enforces (AuthService), checked here first for instant feedback.
  if (!fullName) return failField("r-fullname", "Enter your full name");
  if (!EMAIL_RE.test(email)) return failField("r-email", "Enter a valid email address");
  if (student && !rollNo) return failField("r-rollno", "Enter your roll number");
  if (student && !ROLL_NO_RE.test(rollNo)) return failField("r-rollno", "Roll number must be 3-30 characters: letters, digits, '-', '/' or '_'");
  if (!USERNAME_RE.test(username)) return failField("r-username", "Username must be 3-30 characters: letters, digits, '_' or '.'");
  if (password.length < 6) return failField("r-password", "Password must be at least 6 characters");
  if (password !== confirm) return failField("r-confirm", "Passwords do not match");

  withBusy(e.target, async () => {
    try {
      const user = await api.register({ role, fullName, email, rollNo: student ? rollNo : "", username, password });
      e.target.reset();
      showApp(user);
    } catch (err) {
      showAuthError(err.message);
    }
  });
});

$("logout-btn").addEventListener("click", async () => {
  try {
    await api.logout();
  } finally {
    showAuth();
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
  dashboard: {
    title: () => greeting(),
    sub: () => "Live occupancy, revenue and alerts for campus parking.",
    load: () => loadDashboard(),
  },
  map3d: { title: () => "3D parking map", sub: () => "Every slot on campus, live. Click a slot to see who is parked there.", load: () => loadMap3d() },
  overview: {
    title: () => greeting(),
    sub: () => (isAdmin() ? "Here is what is happening in campus parking today." : "Here is today's availability and your parked vehicles."),
    load: () => loadOverview(),
  },
  live: { title: () => "Live parking", sub: () => (isAdmin() ? "Every slot on campus and whether it is free, occupied or closed." : "Every slot on campus. Select a free slot to park that type of vehicle."), load: () => loadLive() },
  park: { title: () => "Park a vehicle", sub: () => "A free slot that matches the vehicle type is allocated automatically.", load: () => {} },
  finder: { title: () => "Find my vehicle", sub: () => "See where a vehicle is parked, or leave and see the fee.", load: () => {} },
  reports: { title: () => "Reports", sub: () => (isAdmin() ? "Occupancy, arrivals and parking history." : "Your parking visits, time parked and fees."), load: () => loadReports() },
  manage: { title: () => "Manage slots", sub: () => "Add or remove slots, and close slots for maintenance.", load: () => loadManage() },
};

function navigate(name) {
  if (!isAdmin() && ["manage", "dashboard", "map3d"].includes(name)) name = "overview";
  if (isAdmin() && ["overview", "park", "finder"].includes(name)) name = "dashboard";
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

function renderOverviewStats(all) {
  const slots = all.filter((s) => !s.outOfService);   // closed slots are not capacity
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
      const usable = list.filter((s) => !s.outOfService);
      const busy = usable.filter((s) => s.occupied).length;
      const free = usable.length - busy;
      const pct = usable.length ? Math.round((busy / usable.length) * 100) : 100;
      const level = pct >= 90 ? "high" : pct >= 70 ? "mid" : "";
      const types = [...new Set(list.map((s) => typeLabel(s.vehicleType)))].join(", ");
      return `<div class="zone">
        <div class="zone-top">
          <div><div class="zone-name">${esc(location)}</div><div class="zone-type">${esc(types)}</div></div>
          <div class="zone-free ${free === 0 ? "full" : ""}">${free === 0 ? "Full" : free + " free"}</div>
        </div>
        <div class="meter" role="img" aria-label="${pct}% occupied"><i class="${level}" style="width:${pct}%"></i></div>
        <div class="mini">${list.map((s) => `<i class="${s.outOfService ? "off" : s.occupied ? "busy" : ""}" title="${esc(s.slotNumber)}"></i>`).join("")}</div>
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
  slots.filter((s) => !s.outOfService).forEach((s) => {
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
    (!status || (status === "maint" ? s.outOfService : !s.outOfService && (status === "busy") === s.occupied)) &&
    (!query || s.slotNumber.toLowerCase().includes(query)));

  const box = $("live-zones");
  if (slots.length === 0) {
    box.innerHTML = `<div class="card"><p class="muted">No slots match these filters.</p></div>`;
    return;
  }
  box.innerHTML = [...groupByLocation(slots)]
    .map(([location, list]) => {
      const free = list.filter((s) => !s.occupied && !s.outOfService).length;
      const tiles = list
        .map((s) => s.outOfService
          ? `<div class="tile maint"><b>${esc(s.slotNumber)}</b><span>${esc(typeLabel(s.vehicleType))}, closed</span></div>`
          : s.occupied
          ? `<div class="tile busy"><b>${esc(s.slotNumber)}</b><span>${esc(typeLabel(s.vehicleType))}, occupied</span></div>`
          : isAdmin()
          ? `<div class="tile free admin-tile"><b>${esc(s.slotNumber)}</b><span>${esc(typeLabel(s.vehicleType))}, free</span></div>`
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
    const closed = slots.filter((s) => s.outOfService).length;
    $("revenue-row").innerHTML =
      statCard("amber", "rupee", "Total fees collected", money(revenue.totalRevenue)) +
      statCard("blue", "parking", "Total slots", slots.length) +
      statCard("green", "check", "Free slots", slots.length - busy - closed);

    $("manage-body").innerHTML = slots.length === 0
      ? `<tr><td class="empty" colspan="5">No slots yet. Add the first one above.</td></tr>`
      : slots.map((s) => `<tr>
          <td><strong>${esc(s.slotNumber)}</strong></td>
          <td>${esc(typeLabel(s.vehicleType))}</td>
          <td>${esc(s.location)}</td>
          <td><span class="badge ${s.outOfService ? "badge-maint" : s.occupied ? "badge-busy" : "badge-free"}">${s.outOfService ? "Maintenance" : s.occupied ? "Occupied" : "Free"}</span></td>
          <td class="act">
            <button class="btn btn-ghost btn-sm" data-maint-id="${esc(s.slotId)}" data-maint-to="${s.outOfService ? "false" : "true"}" ${s.occupied && !s.outOfService ? "disabled title=\"Occupied - wait for the vehicle to leave\"" : ""}>${icon("wrench")}${s.outOfService ? "Reopen" : "Close for maintenance"}</button>
            <button class="btn btn-danger btn-sm" data-slot-id="${esc(s.slotId)}">${icon("trash")}Delete</button>
          </td>
        </tr>`).join("");

    $("manage-body").querySelectorAll("button[data-maint-id]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        try {
          const slot = await api.setMaintenance(btn.dataset.maintId, btn.dataset.maintTo === "true");
          showToast(slot.outOfService ? `Slot ${slot.slotNumber} closed for maintenance.` : `Slot ${slot.slotNumber} reopened.`);
          loadManage();
        } catch (e) {
          handleError(e);
        }
      });
    });

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
// Admin dashboard (own SVG/CSS charts - no chart library)
// ============================================================

const SVG_NS = "http://www.w3.org/2000/svg";
const rupees = (n) => "₹" + Math.round(Number(n || 0)).toLocaleString("en-IN");

async function loadDashboard() {
  try {
    const [d, parked] = await Promise.all([api.dashboard(), api.parked()]);
    renderDashAlerts(d.alerts);
    renderDashStats(d.summary);
    $("dash-zones").innerHTML = d.byZone.map((z) => hbar(z.zone, z)).join("") || `<p class="muted">No slots yet.</p>`;
    $("dash-types").innerHTML = d.byType.map((t) => hbar(typeLabel(t.type), t, TYPE_COLOR[t.type])).join("");
    renderRevenue(d);
    $("dash-hours").innerHTML = barChart(d.peakHours.map((h) => ({ label: String(h.hour).padStart(2, "0"), value: h.count })), { highlightMax: true, every: 2, unit: "arrivals" });
    $("dash-days").innerHTML = barChart(d.peakWeekdays.map((x) => ({ label: x.day, value: x.count })), { highlightMax: true, unit: "arrivals" });
    renderDashLive(parked, d.summary.overstayHours);
  } catch (e) {
    handleError(e);
  }
}

function renderDashAlerts(alerts) {
  $("dash-alerts").innerHTML = alerts.map((a) =>
    `<div class="alert alert-${esc(a.level)}" role="alert">${icon(a.level === "info" ? "wrench" : "alert")}<span>${esc(a.message)}</span></div>`).join("");
}

function renderDashStats(s) {
  $("dash-stats").innerHTML =
    statCard("blue", "parking", "Total slots", s.totalSlots) +
    statCard("red", "car", "Occupied", s.occupied) +
    statCard("green", "check", "Available", s.available) +
    statCard("purple", "pie", "Occupancy", s.occupancyPercent + "%") +
    statCard("amber", "rupee", "Revenue today", rupees(s.revenueToday));
}

function hbar(name, row, color) {
  const level = row.percent >= 90 ? "full" : row.percent >= 70 ? "warn" : "ok";
  const usable = row.total - row.outOfService;
  const note = row.outOfService ? ` · ${row.outOfService} closed` : "";
  return `<div class="hbar">
    <div class="hbar-top"><b>${esc(name)}</b><span>${row.occupied}/${usable} in use · ${row.percent}%${esc(note)}</span></div>
    <div class="hbar-track" role="img" aria-label="${esc(name)} ${row.percent}% occupied"><div class="hbar-fill ${color ? "" : level}" style="width:${Math.min(100, row.percent)}%${color ? ";background:" + color : ""}"></div></div>
  </div>`;
}

// A bar chart as inline SVG. items: [{label, value}]
function barChart(items, opts = {}) {
  const W = 560, H = 210, padL = 30, padB = 24, padT = 12;
  const max = Math.max(1, ...items.map((i) => i.value));
  const top = niceCeil(max);
  const bw = (W - padL) / items.length;
  const peak = opts.highlightMax ? items.reduce((m, i, idx) => (i.value > items[m].value ? idx : m), 0) : -1;
  let g = "";
  for (let k = 0; k <= 4; k++) {
    const y = padT + (H - padT - padB) * (1 - k / 4);
    g += `<line class="grid" x1="${padL}" x2="${W}" y1="${y}" y2="${y}"/><text class="axis" x="${padL - 6}" y="${y + 3}" text-anchor="end">${Math.round((top * k) / 4)}</text>`;
  }
  items.forEach((it, idx) => {
    const h = ((H - padT - padB) * it.value) / top;
    const x = padL + idx * bw + bw * 0.16;
    const y = H - padB - h;
    g += `<rect class="bar ${idx === peak ? "peak" : ""}" x="${x}" y="${y}" width="${bw * 0.68}" height="${Math.max(h, 0)}" rx="3"><title>${esc(it.label)}: ${it.value}${opts.unit ? " " + opts.unit : ""}</title></rect>`;
    if (!opts.every || idx % opts.every === 0) g += `<text class="axis" x="${x + bw * 0.34}" y="${H - 7}" text-anchor="middle">${esc(it.label)}</text>`;
  });
  return `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Bar chart">${g}</svg>`;
}

function niceCeil(v) {
  if (v <= 4) return 4;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p;
}

function renderRevenue(d) {
  const s = d.summary;
  $("dash-rev-pills").innerHTML =
    `<span class="rev-pill"><small>Today</small>${rupees(s.revenueToday)}</span>` +
    `<span class="rev-pill"><small>7 days</small>${rupees(s.revenueWeek)}</span>` +
    `<span class="rev-pill"><small>30 days</small>${rupees(s.revenueMonth)}</span>`;

  const pts = d.revenueDaily;
  const W = 560, H = 210, padL = 44, padB = 24, padT = 14;
  const top = niceCeil(Math.max(1, ...pts.map((p) => p.amount)));
  const step = (W - padL - 10) / Math.max(1, pts.length - 1);
  const xy = pts.map((p, i) => [padL + i * step, padT + (H - padT - padB) * (1 - p.amount / top)]);
  let g = `<defs><linearGradient id="revGrad" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2563eb" stop-opacity=".28"/><stop offset="1" stop-color="#2563eb" stop-opacity="0"/></linearGradient></defs>`;
  for (let k = 0; k <= 4; k++) {
    const y = padT + (H - padT - padB) * (1 - k / 4);
    g += `<line class="grid" x1="${padL}" x2="${W}" y1="${y}" y2="${y}"/><text class="axis" x="${padL - 6}" y="${y + 3}" text-anchor="end">${rupees((top * k) / 4)}</text>`;
  }
  const line = xy.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + " " + p[1].toFixed(1)).join(" ");
  g += `<path class="area" d="${line} L${xy[xy.length - 1][0]} ${H - padB} L${xy[0][0]} ${H - padB} Z"/><path class="line" d="${line}"/>`;
  pts.forEach((p, i) => {
    const dt = new Date(p.date + "T00:00:00");
    g += `<circle class="pt" cx="${xy[i][0]}" cy="${xy[i][1]}" r="3.4"><title>${dt.toLocaleDateString("en-IN", { day: "numeric", month: "short" })}: ${rupees(p.amount)}</title></circle>`;
    if (i % 2 === 0 || i === pts.length - 1) g += `<text class="axis" x="${xy[i][0]}" y="${H - 7}" text-anchor="middle">${dt.getDate()}/${dt.getMonth() + 1}</text>`;
  });
  $("dash-rev-chart").innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Daily revenue, last 14 days">${g}</svg>`;

  const total = d.revenueByType.reduce((a, r) => a + Number(r.amount), 0) || 1;
  $("dash-rev-type").innerHTML = `<div class="hbars">${d.revenueByType.map((r) => {
    const pct = Math.round((Number(r.amount) / total) * 1000) / 10;
    return `<div class="hbar"><div class="hbar-top"><b>${esc(typeLabel(r.type))}</b><span>${rupees(r.amount)} · ${pct}%</span></div>
      <div class="hbar-track"><div class="hbar-fill" style="width:${pct}%;background:${TYPE_COLOR[r.type] || "#999"}"></div></div></div>`;
  }).join("") || `<p class="muted">No revenue yet.</p>`}</div>`;
}

let dashShowAll = false;
let dashLastParked = [];
function renderDashLive(records, overHours) {
  dashLastParked = records;
  const limit = (overHours || 8) * 60;
  const over = records.filter((r) => r.liveMinutes >= limit).length;
  $("dash-live-sub").dataset.h = overHours;
  $("dash-live-sub").textContent = `${records.length} vehicles` + (over ? ` · ${over} over ${overHours} h` : "");
  const all = [...records].sort((a, b) => b.liveMinutes - a.liveMinutes);
  const LIMIT = 12;
  const sorted = dashShowAll ? all : all.slice(0, LIMIT);
  $("dash-live-more").classList.toggle("hidden", all.length <= LIMIT);
  $("dash-live-more").textContent = dashShowAll ? "Show fewer" : `Show all ${all.length}`;
  $("dash-live-body").innerHTML = sorted.length === 0
    ? `<tr><td class="empty" colspan="8">No vehicles are parked right now.</td></tr>`
    : sorted.map((r) => {
        const isOver = r.liveMinutes >= limit;
        return `<tr class="${isOver ? "overstay" : ""}">
          <td>${plate(r.vehicleNumber)}</td><td>${esc(r.ownerName)}</td><td>${esc(typeLabel(r.vehicleType))}</td>
          <td>${esc(r.slotNumber)}</td><td>${esc(r.location)}</td><td>${esc(r.entryTime)}</td>
          <td>${esc(fmtMinutes(r.liveMinutes))} ${isOver ? `<span class="badge badge-over">Overstay</span>` : ""}</td>
          <td class="act"><button class="btn btn-ghost btn-sm" data-show-slot="${esc(r.slotId)}">${icon("cube")}Show on map</button></td>
        </tr>`;
      }).join("");
  $("dash-live-body").querySelectorAll("button[data-show-slot]").forEach((b) =>
    b.addEventListener("click", () => { pendingFocusSlot = Number(b.dataset.showSlot); navigate("map3d"); }));
}
$("refresh-dash").addEventListener("click", loadDashboard);
$("dash-live-more").addEventListener("click", () => { dashShowAll = !dashShowAll; renderDashLive(dashLastParked, Number($("dash-live-sub").dataset.h || 8)); });

// ============================================================
// 3D parking map (admin)
// ============================================================

let pendingFocusSlot = null;
let map3dStarted = false;

async function loadMap3d() {
  if (!map3dStarted) {
    map3dStarted = true;
    const ok = window.THREE && window.Map3D && Map3D.init($("map3d"), { onSelect: showSlotInfo });
    if (!ok) {
      $("m3-fallback").classList.remove("hidden");
      return;
    }
  }
  if (Map3D.failed) return;
  try {
    const [slots, records, dash] = await Promise.all([api.slots(), api.parked(), api.dashboard()]);
    Map3D.setData({ slots, records, overstayMinutes: dash.summary.overstayHours * 60 });
    Map3D.resize();
    if (pendingFocusSlot != null) {
      Map3D.select(pendingFocusSlot);
      const p = slots.find((s) => s.slotId === pendingFocusSlot);
      if (p) showSlotInfo({ slot: p, rec: records.find((r) => r.slotId === p.slotId), state: "" });
      pendingFocusSlot = null;
    }
  } catch (e) {
    handleError(e);
  }
}

function showSlotInfo(p) {
  const box = $("m3-info");
  if (!p) { box.classList.add("hidden"); return; }
  const s = p.slot, r = p.rec;
  const status = s.outOfService ? "Under maintenance" : s.occupied ? "Occupied" : "Free";
  const badge = s.outOfService ? "badge-maint" : s.occupied ? "badge-busy" : "badge-free";
  let rows = `<dt>Status</dt><dd><span class="badge ${badge}">${status}</span></dd><dt>Type</dt><dd>${esc(typeLabel(s.vehicleType))}</dd>`;
  if (r) {
    rows += `<dt>Vehicle</dt><dd>${esc(r.vehicleNumber)}</dd><dt>Owner</dt><dd>${esc(r.ownerName)}</dd>
      <dt>Parked since</dt><dd>${esc(r.entryTime)}</dd><dt>Duration</dt><dd>${esc(fmtMinutes(r.liveMinutes))}</dd>`;
  }
  const canToggle = s.outOfService || !s.occupied;
  box.innerHTML = `<button class="info-close" aria-label="Close">×</button>
    <h3>Slot ${esc(s.slotNumber)}</h3><div class="sub">${esc(s.location)}</div>
    <dl>${rows}</dl>
    ${canToggle ? `<button class="btn ${s.outOfService ? "btn-primary" : "btn-ghost"} btn-sm" id="m3-toggle">${icon("wrench")}${s.outOfService ? "Reopen slot" : "Close for maintenance"}</button>` : `<span class="muted">Can be closed once the vehicle leaves.</span>`}`;
  box.classList.remove("hidden");
  box.querySelector(".info-close").addEventListener("click", () => { Map3D.select(null); box.classList.add("hidden"); });
  const t = $("m3-toggle");
  if (t) t.addEventListener("click", async () => {
    try {
      const updated = await api.setMaintenance(s.slotId, !s.outOfService);
      showToast(updated.outOfService ? `Slot ${updated.slotNumber} closed for maintenance.` : `Slot ${updated.slotNumber} reopened.`);
      box.classList.add("hidden");
      loadMap3d();
    } catch (e) {
      handleError(e);
    }
  });
}

$("m3-reset").addEventListener("click", () => Map3D.resetView());
$("m3-top").addEventListener("click", () => Map3D.topView());
$("m3-refresh").addEventListener("click", loadMap3d);
$("m3-rotate").addEventListener("click", (e) => {
  const on = e.currentTarget.getAttribute("aria-pressed") !== "true";
  e.currentTarget.setAttribute("aria-pressed", String(on));
  Map3D.setAutoRotate(on);
});

// ============================================================
// Start: are we already logged in (e.g. page refresh)?
// ============================================================

api.me().then(showApp).catch(showAuth);
