// Broker connection for the dashboard.
//
// Everything in this file is visible to anyone who opens the page, so use a
// broker user restricted to the aquarium/# topics (HiveMQ Cloud → Access
// Management), not an admin account.
window.AQUARIUM_CONFIG = {
    mqtt: {
        host: "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud",
        port: 8884,
        protocol: "wss",
        path: "/mqtt",
        username: "aquarium_admin",
        password: "sdey123A"
    }
};
