const options = {
    host: "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud",
    port: 8884,
    protocol: "wss",
    path: "/mqtt",

    username: "aquarium_admin",
    password: "sdey123A"
};

const client = mqtt.connect(options);

client.on("connect", () => {

    console.log("Connected");

    client.subscribe("aquarium/status");
});

client.on("message", (topic, message) => {

    const data =
        JSON.parse(
            message.toString());

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

    document.getElementById("slot1On").value =
        slot1Parts[0];

    document.getElementById("slot1Off").value =
        slot1Parts[1];

    document.getElementById("slot2On").value =
        slot2Parts[0];

    document.getElementById("slot2Off").value =
        slot2Parts[1];
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

    const payload =
        slot1On + "," +
        slot1Off + "," +
        slot2On + "," +
        slot2Off;

    client.publish(
        "aquarium/schedule",
        payload);

    console.log(
        "Schedule Sent:",
        payload);
}
