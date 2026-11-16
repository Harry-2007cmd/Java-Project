// ============================================================
// 3D parking map (Three.js). Draws every parking slot from the live data:
//   green = free, red = occupied (with a little vehicle on it),
//   grey + cones = closed for maintenance, amber beacon = overstay.
//
// The zone names (Turing Block, Gate 3, rear of Picasso Block, two-wheeler parking, bus bay,
// EV zone) follow the parking areas Chitkara University lists publicly; the geometry itself is a
// generic, illustrative campus layout, not a surveyed plan. Any zone name the layout does not
// know about is placed automatically in an extra row, so newly added slots always show up.
// ============================================================
(function () {
  "use strict";

  const COLORS = {
    ground: 0x16301f, road: 0x2a303c, line: 0xdfe5ef,
    free: 0x35c46b, busy: 0xe05252, maint: 0x8b95a8, over: 0xf59e0b,
    hover: 0xffffff, select: 0xffe14d,
  };
  const BAY = {            // bay width (x), bay length (z), vehicle body width and length
    CAR:  { w: 2.7, l: 5.2, vw: 1.9, vl: 4.1 },
    EV:   { w: 2.7, l: 5.2, vw: 1.9, vl: 4.1 },
    BIKE: { w: 1.4, l: 2.8, vw: 0.5, vl: 2.0 },
    BUS:  { w: 3.6, l: 11,  vw: 2.8, vl: 9.6 },
  };
  const CAR_COLORS = [0xe8eaf0, 0x2e4a8f, 0xb23b3b, 0x1f2937, 0xc9ced8, 0x3b7a57, 0xd9a441, 0x6b4ea3];

  // Where each known zone sits on the campus (x, z in world units, rows of bays) and which building stands behind it.
  const ZONES = [
    { test: /turing/i,            cx: -34, cz: -14, rows: 2, building: { name: "Turing Block",  x: -34, z: -36, w: 26, d: 9, h: 11 } },
    { test: /\bev\b|charg/i,      cx: 0,   cz: -14, rows: 1 },
    { test: /picasso/i,           cx: 34,  cz: -14, rows: 2, building: { name: "Picasso Block", x: 34,  z: -36, w: 22, d: 9, h: 13 } },
    { test: /two.?wheel|bike|cycle/i, cx: -34, cz: 19, rows: 2 },
    { test: /gate/i,              cx: 0,   cz: 21,  rows: 2, gate: true },
    { test: /\bbus\b/i,           cx: 34,  cz: 20,  rows: 1 },
  ];

  let renderer, scene, camera, controls, raycaster, container, tooltip;
  let dyn;                          // group holding everything that is rebuilt from data
  let ready = false, failed = false;
  let handlers = {};
  let pickables = [], plates = new Map();     // slotId -> { mesh, base, slot }
  let beacons = [], selectedId = null, hoverId = null, selRing = null;
  let camGoal = null, lastData = null;
  const mouse = new THREE.Vector2();
  let downAt = null;

  const shared = {};
  function mat(color, opts) { return new THREE.MeshStandardMaterial(Object.assign({ color, roughness: 0.7, metalness: 0.05 }, opts || {})); }

  function init(el, h) {
    container = el;
    handlers = h || {};
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "high-performance" });
    } catch (e) {
      failed = true;
      return false;
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.setClearColor(0x0b1b3a);
    container.appendChild(renderer.domElement);

    scene = new THREE.Scene();
    scene.fog = new THREE.Fog(0x0b1b3a, 140, 260);
    camera = new THREE.PerspectiveCamera(45, 1, 0.5, 600);
    resetView(true);

    controls = new THREE.OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.maxPolarAngle = Math.PI / 2 - 0.03;
    controls.minDistance = 18;
    controls.maxDistance = 190;
    controls.autoRotateSpeed = 0.8;
    controls.target.set(0, 0, 8);

    scene.add(new THREE.HemisphereLight(0xcfe0ff, 0x1b2a1d, 0.75));
    const sun = new THREE.DirectionalLight(0xffffff, 0.85);
    sun.position.set(-50, 90, 40);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    const sc = sun.shadow.camera;
    sc.left = -85; sc.right = 85; sc.top = 85; sc.bottom = -85; sc.near = 10; sc.far = 260;
    sun.shadow.bias = -0.0005;
    scene.add(sun);

    buildStatic();
    dyn = new THREE.Group();
    scene.add(dyn);

    raycaster = new THREE.Raycaster();
    tooltip = document.createElement("div");
    tooltip.style.cssText = "position:absolute;pointer-events:none;background:rgba(10,20,45,.92);color:#fff;font-size:12px;padding:5px 9px;border-radius:7px;white-space:nowrap;display:none;z-index:3";
    container.parentElement.appendChild(tooltip);

    const dom = renderer.domElement;
    dom.addEventListener("pointermove", onMove);
    dom.addEventListener("pointerleave", () => { setHover(null); tooltip.style.display = "none"; });
    dom.addEventListener("pointerdown", (e) => { downAt = { x: e.clientX, y: e.clientY }; });
    dom.addEventListener("pointerup", onUp);
    new ResizeObserver(resize).observe(container);
    resize();
    ready = true;
    requestAnimationFrame(loop);
    return true;
  }

  // ---------------- static world: ground, roads, buildings, trees, gate ----------------
  function buildStatic() {
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(150, 140), mat(COLORS.ground, { roughness: 1 }));
    ground.rotation.x = -Math.PI / 2; ground.position.set(0, -0.02, 8); ground.receiveShadow = true;
    scene.add(ground);

    const roadMat = mat(COLORS.road, { roughness: 0.95 });
    const road = (x, z, w, d) => {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(w, d), roadMat);
      m.rotation.x = -Math.PI / 2; m.position.set(x, 0.01, z); m.receiveShadow = true; scene.add(m);
    };
    road(0, 4, 140, 8);            // main east-west road
    road(0, 38, 140, 8);           // lower road
    road(-17, 20, 7, 34);          // links
    road(17, 20, 7, 34);
    road(0, 52, 9, 28);            // entrance road to the gate
    // dashed centre line
    const dash = mat(COLORS.line, { roughness: 1 });
    for (let x = -66; x <= 66; x += 6) {
      const d = new THREE.Mesh(new THREE.PlaneGeometry(2.6, 0.22), dash);
      d.rotation.x = -Math.PI / 2; d.position.set(x, 0.02, 4); scene.add(d);
      const d2 = d.clone(); d2.position.z = 38; scene.add(d2);
    }

    // gate arch
    const pillar = mat(0xe9edf5);
    [-6, 6].forEach((x) => {
      const p = new THREE.Mesh(new THREE.BoxGeometry(1.6, 7, 1.6), pillar);
      p.position.set(x, 3.5, 60); p.castShadow = true; scene.add(p);
    });
    const beam = new THREE.Mesh(new THREE.BoxGeometry(14, 1.6, 1.8), mat(0x0c2a5b));
    beam.position.set(0, 7.4, 60); beam.castShadow = true; scene.add(beam);
    const gl = textSprite("MAIN GATE", 1.6, "#ffffff", "rgba(0,0,0,0)");
    gl.position.set(0, 7.4, 61.1); scene.add(gl);

    // trees
    const trunk = mat(0x5b3a22), leaf = mat(0x2f7d46);
    const tree = (x, z, s) => {
      const g = new THREE.Group();
      const t = new THREE.Mesh(new THREE.CylinderGeometry(0.3 * s, 0.4 * s, 2 * s, 6), trunk); t.position.y = s; g.add(t);
      const c = new THREE.Mesh(new THREE.ConeGeometry(1.6 * s, 4 * s, 8), leaf); c.position.y = 3.6 * s; c.castShadow = true; g.add(c);
      g.position.set(x, 0, z); scene.add(g);
    };
    [[-62, -10], [-62, 12], [-62, 40], [62, -10], [62, 12], [62, 40], [-10, -28], [10, -28], [-12, 30], [12, 30],
     [-54, 50], [54, 50], [-24, 52], [24, 52], [-58, -30], [58, -30]].forEach(([x, z], i) => tree(x, z, 0.9 + (i % 3) * 0.15));

    // legend on the ground
    ["Turing Block", "Picasso Block"].forEach(() => {});
  }

  function addBuilding(b) {
    const body = new THREE.Mesh(new THREE.BoxGeometry(b.w, b.h, b.d), mat(0xcfd6e4, { roughness: 0.6 }));
    body.position.set(b.x, b.h / 2, b.z); body.castShadow = true; body.receiveShadow = true; dyn.add(body);
    const win = mat(0x35507f, { roughness: 0.3, metalness: 0.4 });
    const rows = Math.floor(b.h / 3);
    for (let r = 0; r < rows; r++) {
      const strip = new THREE.Mesh(new THREE.BoxGeometry(b.w * 0.92, 1.1, 0.2), win);
      strip.position.set(b.x, 2.2 + r * 3, b.z + b.d / 2 + 0.05); dyn.add(strip);
    }
    const roof = new THREE.Mesh(new THREE.BoxGeometry(b.w + 0.6, 0.5, b.d + 0.6), mat(0x0c2a5b));
    roof.position.set(b.x, b.h + 0.25, b.z); dyn.add(roof);
    const label = textSprite(b.name, 5, "#ffffff", "rgba(12,42,91,.85)");
    label.position.set(b.x, b.h + 4, b.z); dyn.add(label);
  }

  // ---------------- text sprites ----------------
  function textSprite(text, height, fg, bg) {
    const c = document.createElement("canvas");
    const ctx = c.getContext("2d");
    const font = "600 44px Inter, Segoe UI, Arial, sans-serif";
    ctx.font = font;
    const w = Math.ceil(ctx.measureText(text).width) + 44;
    c.width = w; c.height = 76;
    ctx.font = font;
    if (bg && bg !== "rgba(0,0,0,0)") {
      ctx.fillStyle = bg;
      roundRect(ctx, 0, 0, c.width, c.height, 18); ctx.fill();
    }
    ctx.fillStyle = fg; ctx.textBaseline = "middle"; ctx.textAlign = "center";
    ctx.fillText(text, c.width / 2, c.height / 2 + 2);
    const tex = new THREE.CanvasTexture(c);
    tex.anisotropy = 4;
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
    sp.scale.set((height * c.width) / c.height, height, 1);
    sp.userData.disposable = true;
    return sp;
  }
  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }

  // ---------------- vehicles ----------------
  function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }

  function makeVehicle(type, plate) {
    const g = new THREE.Group();
    const wheelMat = mat(0x111418, { roughness: 0.9 });
    const glass = mat(0x1d2c44, { roughness: 0.2, metalness: 0.5 });
    const color = type === "BUS" ? 0xf2b705 : type === "EV" ? 0x1fb86a : CAR_COLORS[hash(plate || "x") % CAR_COLORS.length];
    const paint = mat(color, { roughness: 0.4, metalness: 0.35 });
    const wheel = (x, y, z, r, w) => {
      const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, w, 14), wheelMat);
      m.rotation.z = Math.PI / 2; m.position.set(x, y, z); m.castShadow = true; g.add(m);
    };
    if (type === "BIKE") {
      const frame = new THREE.Mesh(new THREE.BoxGeometry(0.34, 0.45, 1.5), paint); frame.position.y = 0.75; g.add(frame);
      const tank = new THREE.Mesh(new THREE.BoxGeometry(0.4, 0.3, 0.6), mat(0x222222)); tank.position.set(0, 1.05, 0.15); g.add(tank);
      const bar = new THREE.Mesh(new THREE.BoxGeometry(0.9, 0.08, 0.08), wheelMat); bar.position.set(0, 1.2, 0.6); g.add(bar);
      wheel(0, 0.4, 0.8, 0.4, 0.16); wheel(0, 0.4, -0.8, 0.4, 0.16);
      g.children.forEach((m) => { m.castShadow = true; });
    } else if (type === "BUS") {
      const body = new THREE.Mesh(new THREE.BoxGeometry(2.7, 2.7, 9.2), paint); body.position.y = 1.9; g.add(body);
      const band = new THREE.Mesh(new THREE.BoxGeometry(2.74, 0.9, 8.4), glass); band.position.set(0, 2.4, -0.1); g.add(band);
      [-3, 3].forEach((z) => { wheel(-1.3, 0.55, z, 0.55, 0.3); wheel(1.3, 0.55, z, 0.55, 0.3); });
      g.children.forEach((m) => { m.castShadow = true; });
    } else {
      const body = new THREE.Mesh(new THREE.BoxGeometry(1.85, 0.7, 4.1), paint); body.position.y = 0.65; g.add(body);
      const cabin = new THREE.Mesh(new THREE.BoxGeometry(1.6, 0.62, 2.1), glass); cabin.position.set(0, 1.28, -0.2); g.add(cabin);
      [-1.25, 1.25].forEach((z) => { wheel(-0.95, 0.32, z, 0.32, 0.2); wheel(0.95, 0.32, z, 0.32, 0.2); });
      g.children.forEach((m) => { m.castShadow = true; });
    }
    return g;
  }

  function makeCone() {
    const g = new THREE.Group();
    const c = new THREE.Mesh(new THREE.ConeGeometry(0.4, 1, 10), mat(0xff7a1a)); c.position.y = 0.5; c.castShadow = true; g.add(c);
    const s = new THREE.Mesh(new THREE.CylinderGeometry(0.28, 0.32, 0.12, 10), mat(0xffffff)); s.position.y = 0.55; g.add(s);
    return g;
  }

  function makeCharger() {
    const g = new THREE.Group();
    const post = new THREE.Mesh(new THREE.BoxGeometry(0.5, 1.5, 0.4), mat(0xeaf7ef)); post.position.y = 0.75; post.castShadow = true; g.add(post);
    const led = new THREE.Mesh(new THREE.BoxGeometry(0.3, 0.3, 0.05), new THREE.MeshBasicMaterial({ color: 0x35e07f })); led.position.set(0, 1.1, 0.23); g.add(led);
    return g;
  }

  // ---------------- build the dynamic scene from data ----------------
  function clearDyn() {
    for (let i = dyn.children.length - 1; i >= 0; i--) {
      const ch = dyn.children[i];
      ch.traverse((o) => {
        if (o.userData && o.userData.disposable) { o.material.map && o.material.map.dispose(); o.material.dispose(); }
        if (o.userData && o.userData.ownGeometry) o.geometry.dispose();
      });
      dyn.remove(ch);
    }
    pickables = []; plates = new Map(); beacons = []; selRing = null;
  }

  function zoneFor(name, unknownIndex) {
    const known = ZONES.find((z) => z.test.test(name));
    if (known) return known;
    return null;
  }

  function setData(data) {
    lastData = data;
    if (!ready) return;
    clearDyn();
    const { slots, records, overstayMinutes } = data;
    const recBySlot = new Map();
    (records || []).forEach((r) => recBySlot.set(r.slotId, r));

    const byZone = new Map();
    slots.forEach((s) => { if (!byZone.has(s.location)) byZone.set(s.location, []); byZone.get(s.location).push(s); });

    // known zones first; unknown ones get a row of their own further south
    let unknownX = -50;
    const usedBuildings = new Set();
    for (const [name, list] of byZone) {
      list.sort((a, b) => a.slotNumber.localeCompare(b.slotNumber, undefined, { numeric: true }));
      let z = ZONES.find((k) => k.test.test(name));
      let cfg;
      if (z) {
        cfg = z;
        if (z.building && !usedBuildings.has(z)) { usedBuildings.add(z); addBuilding(z.building); }
      } else {
        const widthEstimate = Math.ceil(list.length / 2) * 3.4 + 6;
        cfg = { cx: unknownX + widthEstimate / 2, cz: 62, rows: 2 };
        unknownX += widthEstimate + 4;
      }
      layoutZone(name, list, cfg, recBySlot, overstayMinutes || 480);
    }
    if (selectedId != null) select(selectedId, true);
  }

  function layoutZone(name, list, cfg, recBySlot, overMin) {
    const type0 = list[0].vehicleType;
    const dim = BAY[type0] || BAY.CAR;
    const rows = Math.min(cfg.rows, list.length);
    const cols = Math.ceil(list.length / rows);
    const pitch = dim.w + (type0 === "BIKE" ? 0.5 : 0.7);
    const aisle = type0 === "BUS" ? 0 : 7;
    const width = cols * pitch;
    const depth = rows * dim.l + (rows - 1) * aisle;

    // tarmac pad for the lot
    const padMat = mat(0x3a4150, { roughness: 1 });
    const pad = new THREE.Mesh(new THREE.BoxGeometry(width + 4, 0.12, depth + 4), padMat);
    pad.position.set(cfg.cx, 0.05, cfg.cz); pad.receiveShadow = true; pad.userData.ownGeometry = true; dyn.add(pad);

    const label = textSprite(name, 3.2, "#ffffff", "rgba(12,42,91,.88)");
    label.position.set(cfg.cx, 5.2, cfg.cz - depth / 2 - 1.5);
    dyn.add(label);

    list.forEach((slot, i) => {
      const r = Math.floor(i / cols), c = i % cols;
      const x = cfg.cx - width / 2 + pitch * (c + 0.5);
      const z = cfg.cz - depth / 2 + dim.l / 2 + r * (dim.l + aisle);
      const rec = recBySlot.get(slot.slotId);
      const over = rec && rec.liveMinutes != null && rec.liveMinutes >= overMin;
      const state = slot.outOfService ? "maint" : slot.occupied ? (over ? "over" : "busy") : "free";
      const color = COLORS[state];

      const plate = new THREE.Mesh(new THREE.BoxGeometry(dim.w - 0.25, 0.14, dim.l - 0.3),
        new THREE.MeshStandardMaterial({ color, roughness: 0.5, emissive: color, emissiveIntensity: state === "free" ? 0.35 : 0.18, transparent: true, opacity: 0.92 }));
      plate.position.set(x, 0.17, z); plate.receiveShadow = true;
      plate.userData = { slotId: slot.slotId, ownGeometry: true };
      dyn.add(plate); pickables.push(plate);
      plates.set(slot.slotId, { mesh: plate, slot, rec, state });

      // white bay edges
      const edge = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.05, dim.l - 0.2), mat(COLORS.line));
      edge.position.set(x - pitch / 2, 0.14, z); edge.userData.ownGeometry = true; dyn.add(edge);
      if (c === cols - 1) { const e2 = edge.clone(); e2.position.x = x + pitch / 2; dyn.add(e2); }

      if (slot.occupied) {
        const v = makeVehicle(slot.vehicleType, rec ? rec.vehicleNumber : slot.slotNumber);
        v.position.set(x, 0.24, z);
        if (slot.vehicleType !== "BUS") v.rotation.y = (hash(slot.slotNumber) % 5 - 2) * 0.015;
        v.traverse((o) => { if (o.isMesh) { o.userData.slotId = slot.slotId; pickables.push(o); } });
        dyn.add(v);
        if (over) {
          const b = new THREE.Mesh(new THREE.SphereGeometry(0.42, 14, 14), new THREE.MeshBasicMaterial({ color: COLORS.over }));
          b.position.set(x, slot.vehicleType === "BUS" ? 4.6 : 3.1, z); b.userData.ownGeometry = true;
          dyn.add(b); beacons.push(b);
        }
      } else if (slot.outOfService) {
        [-1, 1].forEach((k) => { const cone = makeCone(); cone.position.set(x + k * (dim.w / 2 - 0.7), 0.24, z + dim.l / 2 - 0.8); dyn.add(cone); });
        const barrier = new THREE.Mesh(new THREE.BoxGeometry(dim.w - 0.7, 0.18, 0.14), mat(0xffb020));
        barrier.position.set(x, 0.9, z + dim.l / 2 - 0.8); barrier.userData.ownGeometry = true; dyn.add(barrier);
      }
      if (slot.vehicleType === "EV") {
        const ch = makeCharger(); ch.position.set(x, 0.2, z - dim.l / 2 + 0.5); dyn.add(ch);
      }
    });

    if (cfg.gate) {
      // barrier arm marking the entry to Gate 3's lot
      const arm = new THREE.Mesh(new THREE.BoxGeometry(5, 0.2, 0.2), mat(0xf5f5f5));
      arm.position.set(cfg.cx - 2.5 - width / 2 - 1, 1.2, cfg.cz + depth / 2 + 2.3); dyn.add(arm);
    }
  }

  // ---------------- interaction ----------------
  function pick(e) {
    const rect = renderer.domElement.getBoundingClientRect();
    mouse.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
    mouse.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(mouse, camera);
    const hit = raycaster.intersectObjects(pickables, false)[0];
    return hit ? hit.object.userData.slotId : null;
  }

  function onMove(e) {
    if (e.buttons) { tooltip.style.display = "none"; return; }
    const id = pick(e);
    setHover(id);
    if (id != null) {
      const p = plates.get(id);
      const stateText = { free: "Free", busy: "Occupied", over: "Overstay", maint: "Maintenance" }[p.state];
      tooltip.textContent = `${p.slot.slotNumber}  ·  ${stateText}${p.rec ? "  ·  " + p.rec.vehicleNumber : ""}`;
      const r = container.parentElement.getBoundingClientRect();
      tooltip.style.left = e.clientX - r.left + 14 + "px";
      tooltip.style.top = e.clientY - r.top + 14 + "px";
      tooltip.style.display = "block";
      renderer.domElement.style.cursor = "pointer";
    } else {
      tooltip.style.display = "none";
      renderer.domElement.style.cursor = "grab";
    }
  }

  function onUp(e) {
    if (!downAt) return;
    const moved = Math.hypot(e.clientX - downAt.x, e.clientY - downAt.y);
    downAt = null;
    if (moved > 5) return;              // it was a drag (rotate / pan), not a click
    const id = pick(e);
    select(id);
    if (handlers.onSelect) handlers.onSelect(id == null ? null : plates.get(id));
  }

  function setHover(id) {
    if (hoverId === id) return;
    if (hoverId != null && plates.has(hoverId)) plates.get(hoverId).mesh.material.emissiveIntensity = baseGlow(plates.get(hoverId).state);
    hoverId = id;
    if (id != null && plates.has(id)) plates.get(id).mesh.material.emissiveIntensity = 0.9;
  }
  const baseGlow = (state) => (state === "free" ? 0.35 : 0.18);

  function select(id, silent) {
    selectedId = id;
    if (selRing) { dyn.remove(selRing); selRing.geometry.dispose(); selRing.material.dispose(); selRing = null; }
    if (id == null || !plates.has(id)) { if (id != null) selectedId = null; return; }
    const m = plates.get(id).mesh;
    const g = m.geometry.parameters;
    selRing = new THREE.Mesh(new THREE.BoxGeometry(g.width + 0.5, 0.08, g.depth + 0.5),
      new THREE.MeshBasicMaterial({ color: COLORS.select, transparent: true, opacity: 0.85 }));
    selRing.position.set(m.position.x, 0.1, m.position.z);
    dyn.add(selRing);
    if (!silent) focusOn(m.position);
  }

  function focusOn(p) {
    camGoal = {
      target: new THREE.Vector3(p.x, 0, p.z),
      pos: new THREE.Vector3(p.x + 18, 26, p.z + 26),
    };
  }

  function resetView(immediate) {
    const goal = { pos: new THREE.Vector3(0, 82, 112), target: new THREE.Vector3(0, 0, 14) };
    if (immediate || !controls) { camera.position.copy(goal.pos); camera.lookAt(goal.target); } else camGoal = goal;
  }
  function topView() { camGoal = { pos: new THREE.Vector3(0, 140, 18), target: new THREE.Vector3(0, 0, 8) }; }
  function setAutoRotate(on) { if (controls) controls.autoRotate = !!on; }

  function resize() {
    if (!renderer) return;
    const w = container.clientWidth, h = container.clientHeight;
    if (w === 0 || h === 0) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }

  function loop(t) {
    requestAnimationFrame(loop);
    if (!container.offsetParent) return;           // map tab not showing: skip the work
    if (camGoal) {
      camera.position.lerp(camGoal.pos, 0.09);
      controls.target.lerp(camGoal.target, 0.09);
      if (camera.position.distanceTo(camGoal.pos) < 0.2) camGoal = null;
    }
    const pulse = 1 + Math.sin(t / 260) * 0.18;
    beacons.forEach((b) => b.scale.setScalar(pulse));
    if (selRing) selRing.material.opacity = 0.55 + Math.sin(t / 220) * 0.3;
    controls.update();
    renderer.render(scene, camera);
  }

  window.Map3D = {
    init, setData, select, resetView: () => resetView(false), topView, setAutoRotate, resize,
    get ready() { return ready; }, get failed() { return failed; },
    get lastData() { return lastData; },
  };
})();
