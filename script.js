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

    document.getElementById("online").innerText =
        data.online;

    document.getElementById("mode").innerText =
        data.mode;

    document.getElementById("light").innerText =
        data.light;

    document.getElementById("time").innerText =
        data.time;

    document.getElementById("slot1").innerText =
        data.slot1;

    document.getElementById("slot2").innerText =
        data.slot2;
});
