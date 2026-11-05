"use strict";

// =========================================================
// CONSTANTS
// =========================================================

const TOPICS = {
    status: "aquarium/status",     // device -> dashboard, JSON
    online: "aquarium/online",     // device -> dashboard, "1" / "0" (last will)
    control: "aquarium/control",   // dashboard -> device
    schedule: "aquarium/schedule"  // dashboard -> device
};

// The device reports every 10 s; three missed reports means it's gone.
const STALE_AFTER_MS = 30000;

// How long to wait for the device to confirm a change.
const CONFIRM_TIMEOUT_MS = 15000;

const MODES = {
    AUTO: { label: "Auto", button: "autoBtn" },
    FORCE_ON: { label: "Forced on", button: "forceOnBtn" },
    FORCE_OFF: { label: "Forced off", button: "forceOffBtn" }
};

const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

const SLOT_IDS = [1, 2];

// =========================================================
// DOM
// =========================================================

const $ = (id) => document.getElementById(id);

const ui = {
    onlineBadge: $("onlineBadge"),
    lightBadge: $("lightBadge"),
    modeBadge: $("modeBadge"),
    time: $("time"),
    modeButtons: Object.keys(MODES).map((mode) => $(MODES[mode].button)),
    modeStatus: $("modeStatus"),
    saveBtn: $("saveBtn"),
    saveStatus: $("saveStatus"),
    resetWifiBtn: $("resetWifiBtn"),
    wifiStatus: $("wifiStatus"),
    slots: SLOT_IDS.map((n) => ({
        display: $(`slot${n}`),
        enabled: $(`slot${n}Enabled`),
        on: $(`slot${n}On`),
        off: $(`slot${n}Off`)
    }))
};

// =========================================================
// STATE
// =========================================================

const state = {
    brokerConnected: false,
    deviceOnlineFlag: null,   // from aquarium/online; null = not heard
    lastStatusAt: 0,
    lastStatus: null,
    editorDirty: false        // user has unsaved edits; don't overwrite them
};

let client = null;

// =========================================================
// SMALL HELPERS
// =========================================================

function showMessage(el, text, kind = "ok", holdMs = 4000)
{
    el.textContent = text;
    el.classList.toggle("error", kind === "error");
    el.classList.add("show");

    clearTimeout(el.hideTimer);

    if(holdMs)
    {
        el.hideTimer = setTimeout(() => el.classList.remove("show"), holdMs);
    }
}

function setBadge(el, text, className)
{
    el.textContent = text;
    el.className = `badge ${className}`;
}

// "8:00" -> "08:00"; returns null for anything that isn't a time.
function normalizeTime(value)
{
    if(typeof value !== "string")
    {
        return null;
    }

    const padded = value.trim().padStart(5, "0");

    return TIME_PATTERN.test(padded) ? padded : null;
}

// "08:00-14:00" -> { on: "08:00", off: "14:00" }
function parseSlot(value)
{
    if(typeof value !== "string")
    {
        return null;
    }

    const [on, off] = value.split("-").map(normalizeTime);

    return on && off ? { on, off } : null;
}

function parseStatus(raw)
{
    let data;

    try
    {
        data = JSON.parse(raw);
    }
    catch
    {
        return null;
    }

    if(!data || typeof data !== "object")
    {
        return null;
    }

    const enabled = (v) => (typeof v === "boolean" ? v : null);

    return {
        mode: typeof data.mode === "string" ? data.mode : null,
        light: data.light === "ON" ? "ON" : "OFF",
        time: typeof data.time === "string" ? data.time : null,
        slots: [
            { ...parseSlot(data.slot1), enabled: enabled(data.slot1Enabled) },
            { ...parseSlot(data.slot2), enabled: enabled(data.slot2Enabled) }
        ]
    };
}

// =========================================================
// CONFIRMATIONS
// Commands are fire-and-forget over MQTT, so we watch incoming
// status reports for one that shows the change took effect.
// =========================================================

const waiters = new Set();

function waitForStatus(matches)
{
    return new Promise((resolve) => {
        const waiter = { matches, resolve };

        waiter.timer = setTimeout(() => {
            waiters.delete(waiter);
            resolve(false);
        }, CONFIRM_TIMEOUT_MS);

        waiters.add(waiter);
    });
}

function notifyWaiters(status)
{
    for(const waiter of waiters)
    {
        if(waiter.matches(status))
        {
            clearTimeout(waiter.timer);
            waiters.delete(waiter);
            waiter.resolve(true);
        }
    }
}

// =========================================================
// RENDERING
// =========================================================

function isDeviceOnline()
{
    const fresh = Date.now() - state.lastStatusAt < STALE_AFTER_MS;

    return state.brokerConnected && state.deviceOnlineFlag !== false && fresh;
}

function renderConnection()
{
    if(!state.brokerConnected)
    {
        setBadge(ui.onlineBadge, "● Connecting…", "neutral");
    }
    else if(isDeviceOnline())
    {
        setBadge(ui.onlineBadge, "● Online", "online");
    }
    else if(state.lastStatusAt === 0 && state.deviceOnlineFlag === null)
    {
        setBadge(ui.onlineBadge, "● Waiting for device…", "neutral");
    }
    else
    {
        setBadge(ui.onlineBadge, "● Offline", "offline");
    }

    const disabled = !state.brokerConnected;

    ui.modeButtons.forEach((btn) => { btn.disabled = disabled; });
    ui.saveBtn.disabled = disabled;
    ui.resetWifiBtn.disabled = disabled;
}

function renderStatus(status)
{
    // Mode
    const mode = MODES[status.mode];

    ui.modeBadge.textContent = mode ? mode.label : (status.mode || "—");

    Object.entries(MODES).forEach(([key, { button }]) => {
        const active = key === status.mode;
        $(button).classList.toggle("active-mode", active);
        $(button).setAttribute("aria-pressed", String(active));
    });

    // Light
    if(status.light === "ON")
    {
        setBadge(ui.lightBadge, "💡 On", "light-on");
    }
    else
    {
        setBadge(ui.lightBadge, "🌙 Off", "light-off");
    }

    // Clock
    ui.time.textContent = status.time || "--:--:--";

    // Schedule summary + editor
    status.slots.forEach((slot, i) => {
        const el = ui.slots[i];

        if(!slot.on)
        {
            el.display.textContent = "--:-- → --:--";
            return;
        }

        el.display.textContent = slot.enabled === false
            ? `${slot.on} → ${slot.off} (disabled)`
            : `${slot.on} → ${slot.off}`;

        if(!state.editorDirty)
        {
            el.on.value = slot.on;
            el.off.value = slot.off;

            if(slot.enabled !== null)
            {
                el.enabled.checked = slot.enabled;
            }
        }
    });

    updateSlotInputs();
}

function updateSlotInputs()
{
    ui.slots.forEach((el) => {
        el.on.disabled = !el.enabled.checked;
        el.off.disabled = !el.enabled.checked;
    });
}

// =========================================================
// ACTIONS
// =========================================================

function publish(topic, payload)
{
    if(!client || !client.connected)
    {
        return false;
    }

    client.publish(topic, payload);
    return true;
}

async function setMode(mode)
{
    if(!publish(TOPICS.control, mode))
    {
        showMessage(ui.modeStatus, "Not connected — try again in a moment", "error");
        return;
    }

    showMessage(ui.modeStatus, "Sending…", "ok", 0);

    const confirmed = await waitForStatus((s) => s.mode === mode);

    if(confirmed)
    {
        showMessage(ui.modeStatus, `✓ ${MODES[mode].label}`);
    }
    else
    {
        showMessage(ui.modeStatus, "Device didn't confirm — it may be offline", "error", 6000);
    }
}

function readEditor()
{
    const slots = ui.slots.map((el, i) => ({
        n: i + 1,
        enabled: el.enabled.checked,
        on: normalizeTime(el.on.value),
        off: normalizeTime(el.off.value)
    }));

    for(const slot of slots)
    {
        if(!slot.enabled)
        {
            // The device still needs valid times for a disabled slot.
            slot.on = slot.on || "00:00";
            slot.off = slot.off || "00:00";
            continue;
        }

        if(!slot.on || !slot.off)
        {
            return { error: `Slot ${slot.n}: set both an on and an off time` };
        }

        if(slot.on === slot.off)
        {
            return { error: `Slot ${slot.n}: on and off times can't be the same` };
        }
    }

    return { slots };
}

async function saveSchedule()
{
    const { slots, error } = readEditor();

    if(error)
    {
        showMessage(ui.saveStatus, error, "error", 6000);
        return;
    }

    // Wire format: e1,HH:MM,HH:MM,e2,HH:MM,HH:MM
    const payload = slots
        .map((s) => `${s.enabled ? 1 : 0},${s.on},${s.off}`)
        .join(",");

    if(!publish(TOPICS.schedule, payload))
    {
        showMessage(ui.saveStatus, "Not connected — try again in a moment", "error");
        return;
    }

    ui.saveBtn.disabled = true;
    showMessage(ui.saveStatus, "Saving…", "ok", 0);

    const confirmed = await waitForStatus((status) =>
        status.slots.every((reported, i) =>
            reported.on === slots[i].on &&
            reported.off === slots[i].off &&
            // Older firmware doesn't report enabled flags; skip the check then.
            (reported.enabled === null || reported.enabled === slots[i].enabled)));

    ui.saveBtn.disabled = !state.brokerConnected;

    if(confirmed)
    {
        state.editorDirty = false;
        showMessage(ui.saveStatus, "✓ Schedule saved on the device");
    }
    else
    {
        showMessage(ui.saveStatus, "Device didn't confirm — it may be offline", "error", 6000);
    }
}

function resetWifi()
{
    const confirmed = confirm(
        "This will disconnect the device from WiFi and restart it into " +
        "setup mode (AquariumLight_Setup). You'll need to connect to that " +
        "hotspot directly to enter new WiFi credentials. Continue?");

    if(!confirmed)
    {
        return;
    }

    if(publish(TOPICS.control, "RESET_WIFI"))
    {
        showMessage(ui.wifiStatus, "⚠ WiFi reset sent — device will restart", "ok", 8000);
    }
    else
    {
        showMessage(ui.wifiStatus, "Not connected — try again in a moment", "error");
    }
}

// =========================================================
// MQTT
// =========================================================

function handleMessage(topic, message)
{
    const text = message.toString();

    if(topic === TOPICS.online)
    {
        state.deviceOnlineFlag = text === "1";
        renderConnection();
        return;
    }

    if(topic !== TOPICS.status)
    {
        return;
    }

    const status = parseStatus(text);

    if(!status)
    {
        console.warn("Ignoring malformed status message:", text);
        return;
    }

    state.lastStatus = status;
    state.lastStatusAt = Date.now();

    renderStatus(status);
    renderConnection();
    notifyWaiters(status);
}

function connect()
{
    const config = window.AQUARIUM_CONFIG && window.AQUARIUM_CONFIG.mqtt;

    if(typeof mqtt === "undefined" || !config)
    {
        setBadge(ui.onlineBadge, "● Dashboard failed to load", "offline");
        console.error("MQTT library or config.js missing");
        return;
    }

    client = mqtt.connect({
        ...config,
        reconnectPeriod: 5000,
        connectTimeout: 10000,
        clean: true
    });

    client.on("connect", () => {
        state.brokerConnected = true;
        client.subscribe([TOPICS.status, TOPICS.online]);
        renderConnection();
    });

    client.on("close", () => {
        state.brokerConnected = false;
        renderConnection();
    });

    client.on("error", (err) => {
        console.error("MQTT error:", err.message);
    });

    client.on("message", handleMessage);
}

// =========================================================
// WIRING
// =========================================================

function init()
{
    Object.keys(MODES).forEach((mode) => {
        $(MODES[mode].button).addEventListener("click", () => setMode(mode));
    });

    ui.saveBtn.addEventListener("click", saveSchedule);
    ui.resetWifiBtn.addEventListener("click", resetWifi);

    ui.slots.forEach((el) => {
        el.enabled.addEventListener("change", () => {
            state.editorDirty = true;
            updateSlotInputs();
        });
        el.on.addEventListener("input", () => { state.editorDirty = true; });
        el.off.addEventListener("input", () => { state.editorDirty = true; });
    });

    updateSlotInputs();
    renderConnection();

    // Re-evaluate staleness even when no messages arrive.
    setInterval(renderConnection, 1000);

    connect();
    initBubbleScene();
}

// =========================================================
// HERO BUBBLE SCENE (three.js)
// Purely decorative — rising, looping glass-bubble field
// rendered inside the hero image. Skipped entirely if the
// visitor prefers reduced motion, or if three.js / WebGL
// isn't available.
// =========================================================

function initBubbleScene()
{
    const prefersReducedMotion =
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    const canvas = $("bubbleCanvas");

    if(!canvas || prefersReducedMotion || typeof THREE === "undefined")
    {
        return;
    }

    const hero = canvas.closest(".hero");

    let renderer;

    try
    {
        renderer = new THREE.WebGLRenderer({
            canvas: canvas,
            alpha: true,
            antialias: true
        });
    }
    catch(err)
    {
        console.warn("WebGL unavailable, skipping bubble scene:", err);
        return;
    }

    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

    const scene = new THREE.Scene();

    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);
    camera.position.set(0, 0, 18);

    // Lighting — a cool key light plus a warm rim light, echoing
    // the tank's own overhead lamps.
    scene.add(new THREE.AmbientLight(0x2dd4c8, 0.5));

    const key = new THREE.PointLight(0x9fe8ff, 1.4, 60);
    key.position.set(-8, 10, 12);
    scene.add(key);

    const rim = new THREE.PointLight(0xffb454, 0.8, 60);
    rim.position.set(10, -6, 6);
    scene.add(rim);

    // Bubble field
    const BUBBLE_COUNT = 34;
    const bubbleGeo = new THREE.SphereGeometry(1, 16, 16);
    const bubbleMat = new THREE.MeshPhongMaterial({
        color: 0xbdf4ff,
        transparent: true,
        opacity: 0.28,
        shininess: 120,
        specular: 0xffffff
    });

    const bubbles = [];

    function resetBubble(b, randomizeHeight)
    {
        b.userData.speed = 0.6 + Math.random() * 1.1;
        b.userData.drift = (Math.random() - 0.5) * 0.4;
        b.userData.wobble = Math.random() * Math.PI * 2;
        b.scale.setScalar(0.12 + Math.random() * 0.5);
        b.position.x = (Math.random() - 0.5) * 22;
        b.position.z = (Math.random() - 0.5) * 10;
        b.position.y = randomizeHeight
            ? (Math.random() - 0.5) * 16
            : -9 - Math.random() * 4;
    }

    for(let i = 0; i < BUBBLE_COUNT; i++)
    {
        const b = new THREE.Mesh(bubbleGeo, bubbleMat);
        resetBubble(b, true);
        bubbles.push(b);
        scene.add(b);
    }

    function resize()
    {
        const rect = hero.getBoundingClientRect();
        renderer.setSize(rect.width, rect.height, false);
        camera.aspect = rect.width / Math.max(rect.height, 1);
        camera.updateProjectionMatrix();
    }

    resize();
    window.addEventListener("resize", resize);

    let lastFrame = performance.now();

    function animate(now)
    {
        requestAnimationFrame(animate);

        const dt = Math.min((now - lastFrame) / 1000, 0.05);
        lastFrame = now;

        // Pause work entirely when the tab isn't visible.
        if(document.hidden)
        {
            return;
        }

        bubbles.forEach((b) => {
            b.position.y += b.userData.speed * dt;
            b.userData.wobble += dt * 1.5;
            b.position.x += Math.sin(b.userData.wobble) * b.userData.drift * dt;

            if(b.position.y > 9)
            {
                resetBubble(b, false);
            }
        });

        renderer.render(scene, camera);
    }

    requestAnimationFrame(animate);
}

if(document.readyState === "loading")
{
    document.addEventListener("DOMContentLoaded", init);
}
else
{
    init();
}
