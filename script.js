const options = {
    host: "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud",
    port: 8884,
    protocol: "wss",
    path: "/mqtt",

    username: "aquarium_admin",
    password: "sdey123A"
};
let lastMessageTime =
    Date.now();
const client = mqtt.connect(options);

client.on("connect", () => {

    console.log("Connected");

    client.subscribe("aquarium/status");
});
let scheduleLoaded = false;
client.on("message", (topic, message) => {

    const data =
        JSON.parse(
            message.toString());
    lastMessageTime =
    Date.now();

    // MODE BADGE

    document.getElementById("modeBadge").innerText =
        data.mode;

    // ACTIVE BUTTON HIGHLIGHT

    document.getElementById("autoBtn")
        .classList.remove("active-mode");

    document.getElementById("forceOnBtn")
        .classList.remove("active-mode");

    document.getElementById("forceOffBtn")
        .classList.remove("active-mode");

    if(data.mode === "AUTO")
    {
        document.getElementById("autoBtn")
            .classList.add("active-mode");
    }
    else if(data.mode === "FORCE_ON")
    {
        document.getElementById("forceOnBtn")
            .classList.add("active-mode");
    }
    else if(data.mode === "FORCE_OFF")
    {
        document.getElementById("forceOffBtn")
            .classList.add("active-mode");
    }

    // ONLINE STATUS

    if(data.online)
    {
        document.getElementById("onlineBadge").innerText =
            "● ONLINE";

        document.getElementById("onlineBadge").className =
            "badge online";
    }
    else
    {
        document.getElementById("onlineBadge").innerText =
            "● OFFLINE";

        document.getElementById("onlineBadge").className =
            "badge offline";
    }

    // LIGHT STATUS

    if(data.light === "ON")
    {
        document.getElementById("lightBadge").innerText =
            "💡 ON";

        document.getElementById("lightBadge").className =
            "badge light-on";
    }
    else
    {
        document.getElementById("lightBadge").innerText =
            "🌙 OFF";

        document.getElementById("lightBadge").className =
            "badge light-off";
    }

    // TIME

    document.getElementById("time").innerText =
        data.time;

    // SCHEDULE DISPLAY

    document.getElementById("slot1").innerText =
        data.slot1;

    document.getElementById("slot2").innerText =
        data.slot2;

    // AUTO-FILL EDITOR

    const slot1Parts =
        data.slot1.split("-");

    const slot2Parts =
        data.slot2.split("-");

    if(!scheduleLoaded)
    {
        document.getElementById("slot1On").value =
            slot1Parts[0].padStart(5, "0");
    
        document.getElementById("slot1Off").value =
            slot1Parts[1].padStart(5, "0");
    
        document.getElementById("slot2On").value =
            slot2Parts[0].padStart(5, "0");
    
        document.getElementById("slot2Off").value =
            slot2Parts[1].padStart(5, "0");
    
        scheduleLoaded = true;
    }
});
function setMode(mode)
{
    client.publish(
        "aquarium/control",
        mode);

    console.log(
        "Mode Sent:",
        mode);
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

    client.publish(
        "aquarium/control",
        "RESET_WIFI");

    console.log(
        "WiFi Reset Sent");

    const status =
        document.getElementById(
            "saveStatus");

    status.innerText =
        "⚠ WiFi reset sent — device will restart";

    status.classList.add(
        "show");

    setTimeout(() => {

        status.classList.remove(
            "show");

    }, 5000);
}
function saveSchedule()
{
    const slot1On =
        document.getElementById("slot1On").value;

    const slot1Off =
        document.getElementById("slot1Off").value;

    const slot2On =
        document.getElementById("slot2On").value;

    const slot2Off =
        document.getElementById("slot2Off").value;

    const slot1Enabled =
    document.getElementById(
        "slot1Enabled").checked ? 1 : 0;

const slot2Enabled =
    document.getElementById(
        "slot2Enabled").checked ? 1 : 0;

const payload =
    slot1Enabled + "," +
    slot1On + "," +
    slot1Off + "," +
    slot2Enabled + "," +
    slot2On + "," +
    slot2Off;

    client.publish(
        "aquarium/schedule",
        payload);

    console.log(
        "Schedule Sent:",
        payload);

    const status =
        document.getElementById(
            "saveStatus");

    status.innerText =
        "✓ Schedule Saved";

    status.classList.add(
        "show");

    setTimeout(() => {

        status.classList.remove(
            "show");

    }, 3000);
}
function updateSlotStates()
{
    const slot1Enabled =
        document.getElementById(
            "slot1Enabled").checked;

    const slot2Enabled =
        document.getElementById(
            "slot2Enabled").checked;

    document.getElementById(
        "slot1On").disabled =
        !slot1Enabled;

    document.getElementById(
        "slot1Off").disabled =
        !slot1Enabled;

    document.getElementById(
        "slot2On").disabled =
        !slot2Enabled;

    document.getElementById(
        "slot2Off").disabled =
        !slot2Enabled;
}
document
.getElementById("slot1Enabled")
.addEventListener(
    "change",
    updateSlotStates);

document
.getElementById("slot2Enabled")
.addEventListener(
    "change",
    updateSlotStates);
updateSlotStates();

setInterval(() =>
{
    if(
        Date.now() -
        lastMessageTime >
        30000
    )
    {
        document
        .getElementById(
            "onlineBadge")
        .innerText =
            "● OFFLINE";

        document
        .getElementById(
            "onlineBadge")
        .className =
            "badge offline";
    }

}, 1000);

// =========================================================
// HERO BUBBLE SCENE (three.js)
// Purely decorative — rising, looping glass-bubble field
// rendered inside the hero image. Skipped entirely if the
// visitor prefers reduced motion, or if three.js fails to load.
// =========================================================

function initBubbleScene()
{
    const prefersReducedMotion =
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    const canvas = document.getElementById("bubbleCanvas");

    if(!canvas || prefersReducedMotion || typeof THREE === "undefined")
    {
        return;
    }

    const hero = canvas.closest(".hero");

    const renderer = new THREE.WebGLRenderer({
        canvas: canvas,
        alpha: true,
        antialias: true
    });
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
        const scale = 0.12 + Math.random() * 0.5;
        b.scale.setScalar(scale);
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

        // Pause work entirely when the tab isn't visible.
        if(document.hidden)
        {
            return;
        }

        const dt = Math.min((now - lastFrame) / 1000, 0.05);
        lastFrame = now;

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
    document.addEventListener("DOMContentLoaded", initBubbleScene);
}
else
{
    initBubbleScene();
}
