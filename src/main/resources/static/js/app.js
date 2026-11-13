// ============================================================
// Smart Campus Parking - frontend logic
// Talks to the Java backend purely over the /api/* JSON endpoints.
// ============================================================

const api = {
  slots: (type) => fetch(`/api/slots${type ? "?type=" + encodeURIComponent(type) : ""}`).then(toJson),
  parked: () => fetch("/api/parked").then(toJson),
  history: (limit = 20) => fetch(`/api/history?limit=${limit}`).then(toJson),
  report: () => fetch("/api/report").then(toJson),
  locate: (vehicleNumber) => fetch(`/api/locate?vehicleNumber=${encodeURIComponent(vehicleNumber)}`).then(toJson),
  park: (payload) => fetch("/api/park", { method: "POST", headers: jsonHeaders, body: JSON.stringify(payload) }).then(toJson),
  unpark: (vehicleNumber) => fetch("/api/unpark", { method: "POST", headers: jsonHeaders, body: JSON.stringify({ vehicleNumber }) }).then(toJson),
};

const jsonHeaders = { "Content-Type": "application/json" };

async function toJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const err = new Error(data.error || "Something went wrong");
    err.status = response.status;
    throw err;
  }
  return data;
}

// ---------- Tab navigation ----------
document.querySelectorAll(".tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".view").forEach((v) => v.classList.remove("active"));
    btn.classList.add("active");
    document.getElementById(`view-${btn.dataset.view}`).classList.add("active");
    if (btn.dataset.view === "dashboard") loadDashboard();
    if (btn.dataset.view === "report") loadReport();
  });
});

function showToast(message, type = "success") {
  const toast = document.getElementById("toast");
  toast.textContent = message;
  toast.className = `toast ${type}`;
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => toast.classList.add("hidden"), 3500);
}

// ---------- Dashboard ----------
async function loadDashboard() {
  try {
    const [slots, parked] = await Promise.all([
      api.slots(document.getElementById("slot-filter").value || undefined),
      api.parked(),
    ]);
    renderStats(slots, parked);
    renderSlots(slots);
    renderParked(parked);
  } catch (e) {
    showToast(e.message, "error");
  }
}

function renderStats(slots, parked) {
  const total = slots.length;
  const occupied = slots.filter((s) => s.occupied).length;
  const row = document.getElementById("stat-row");
  row.innerHTML = "";
  const cards = [
    { label: "Total Slots", value: total },
    { label: "Occupied", value: occupied },
    { label: "Available", value: total - occupied },
    { label: "Vehicles Parked Now", value: parked.length },
  ];
  cards.forEach((c) => {
    const div = document.createElement("div");
    div.className = "stat-card";
    div.innerHTML = `<div class="label">${c.label}</div><div class="value">${c.value}</div>`;
    row.appendChild(div);
  });
}

function renderSlots(slots) {
  const grid = document.getElementById("slot-grid");
  grid.innerHTML = "";
  if (slots.length === 0) {
    grid.innerHTML = `<p style="color:var(--text-dim)">No slots to show.</p>`;
    return;
  }
  slots.forEach((s) => {
    const div = document.createElement("div");
    div.className = `slot ${s.occupied ? "occupied" : ""}`;
    div.innerHTML = `<span class="num">${s.slotNumber}</span><span class="type">${s.vehicleType}</span>`;
    div.title = s.location + (s.occupied ? " - Occupied" : " - Free");
    grid.appendChild(div);
  });
}

function renderParked(records) {
  const body = document.getElementById("parked-body");
  body.innerHTML = "";
  if (records.length === 0) {
    body.innerHTML = `<tr class="empty-row"><td colspan="6">No vehicles are currently parked.</td></tr>`;
    return;
  }
  records.forEach((r) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${r.vehicleNumber}</td>
      <td>${r.slotNumber}</td>
      <td>${r.location}</td>
      <td>${r.entryTime}</td>
      <td>${sinceLabel(r.entryTime)}</td>
      <td><button class="btn-small" data-vehicle="${r.vehicleNumber}">Unpark</button></td>
    `;
    body.appendChild(tr);
  });
  body.querySelectorAll(".btn-small").forEach((btn) => {
    btn.addEventListener("click", async () => {
      try {
        const result = await api.unpark(btn.dataset.vehicle);
        showToast(`${result.vehicleNumber} left slot ${result.slotNumber} after ${result.durationMinutes} min.`);
        loadDashboard();
      } catch (e) {
        showToast(e.message, "error");
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

document.getElementById("slot-filter").addEventListener("change", loadDashboard);
document.getElementById("refresh-parked").addEventListener("click", loadDashboard);

// ---------- Park a vehicle ----------
document.getElementById("park-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const payload = {
    vehicleNumber: document.getElementById("p-number").value.trim(),
    vehicleType: document.getElementById("p-type").value,
    ownerName: document.getElementById("p-owner").value.trim(),
    ownerContact: document.getElementById("p-contact").value.trim(),
  };
  const box = document.getElementById("park-result");
  try {
    const record = await api.park(payload);
    box.className = "result-box";
    box.innerHTML = `✅ <strong>${record.vehicleNumber}</strong> has been parked at slot
      <strong>${record.slotNumber}</strong> (${record.location}).<br>Entry time: ${record.entryTime}`;
    e.target.reset();
  } catch (err) {
    box.className = "result-box error";
    box.textContent = "❌ " + err.message;
  }
  box.classList.remove("hidden");
});

// ---------- Vehicle finder ----------
document.getElementById("find-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const vehicleNumber = document.getElementById("f-number").value.trim();
  const box = document.getElementById("find-result");
  try {
    const record = await api.locate(vehicleNumber);
    box.className = "result-box";
    box.innerHTML = `📍 <strong>${record.vehicleNumber}</strong> is parked at slot
      <strong>${record.slotNumber}</strong>, ${record.location}.<br>
      Entry time: ${record.entryTime}<br>Parked for: ${sinceLabel(record.entryTime)}
      <br><br><button class="btn-small" id="finder-unpark">Unpark this vehicle</button>`;
    document.getElementById("finder-unpark").addEventListener("click", async () => {
      try {
        const result = await api.unpark(vehicleNumber);
        showToast(`${result.vehicleNumber} left slot ${result.slotNumber} after ${result.durationMinutes} min.`);
        box.innerHTML = `✅ ${result.vehicleNumber} has exited slot ${result.slotNumber}.`;
      } catch (err) {
        showToast(err.message, "error");
      }
    });
  } catch (err) {
    box.className = "result-box error";
    box.textContent = "❌ " + err.message;
  }
  box.classList.remove("hidden");
});

// ---------- Occupancy report ----------
async function loadReport() {
  try {
    const report = await api.report();
    const grid = document.getElementById("report-grid");
    grid.innerHTML = "";
    Object.keys(report).forEach((key) => {
      const stat = report[key];
      const div = document.createElement("div");
      div.className = "report-card";
      div.innerHTML = `
        <div class="title">${key}</div>
        <div class="pct">${stat.occupancyPercent}%</div>
        <div class="bar"><div class="bar-fill" style="width:${stat.occupancyPercent}%"></div></div>
        <div class="detail">${stat.occupiedSlots} / ${stat.totalSlots} occupied &middot; ${stat.availableSlots} free</div>
      `;
      grid.appendChild(div);
    });

    const history = await api.history(25);
    const body = document.getElementById("history-body");
    body.innerHTML = "";
    if (history.length === 0) {
      body.innerHTML = `<tr class="empty-row"><td colspan="5">No parking history yet.</td></tr>`;
      return;
    }
    history.forEach((r) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td>${r.vehicleNumber}</td>
        <td>${r.slotNumber}</td>
        <td>${r.entryTime}</td>
        <td>${r.exitTime ?? "Still parked"}</td>
        <td>${r.durationMinutes != null ? r.durationMinutes + " min" : "-"}</td>
      `;
      body.appendChild(tr);
    });
  } catch (e) {
    showToast(e.message, "error");
  }
}
document.getElementById("refresh-report").addEventListener("click", loadReport);

// ---------- Init ----------
loadDashboard();
