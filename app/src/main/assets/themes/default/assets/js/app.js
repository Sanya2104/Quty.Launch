function navigate(screen) {
    document.querySelectorAll(".screen").forEach(s => s.classList.remove("active"));
    document.getElementById(screen).classList.add("active");

    if (screen === "apps") {
        loadApps();
    }

    if (screen === "info") {
        loadInfo();
    }
}

/*
    ==== API METHODS ====
    Предполагаем что JsBridge имеет методы:

    Android.getApps()
    Android.getDeviceInfo()
    Android.launchApp(packageName)
*/

function loadApps() {
    const container = document.getElementById("apps-list");
    container.innerHTML = "Загрузка...";

    try {
        const response = JSON.parse(Android.call("getInstalledApps", null));

        if (!response.success) {
            container.innerHTML = "Ошибка: " + response.error;
            return;
        }

        const apps = response.data;
        container.innerHTML = "";

        apps.forEach(app => {
            const div = document.createElement("div");
            div.className = "app-item";
            div.innerText = app.name;

            div.onclick = () => {
                const params = JSON.stringify({packageName: app.packageName});
                Android.call("launchApp", params);
            };

            container.appendChild(div);
        });

    } catch (e) {
        container.innerHTML = "Ошибка загрузки приложений";
        console.error(e);
    }
}

function loadInfo() {
    const container = document.getElementById("info-content");
    container.innerHTML = "Загрузка...";

    try {
        const response = JSON.parse(Android.call("getSystemInfo", null));

        if (!response.success) {
            container.innerHTML = "Ошибка: " + response.error;
            return;
        }

        const info = response.data;
        container.innerHTML = `
            <p><b>Устройство:</b> ${info.device}</p>
            <p><b>Android:</b> ${info.version}</p>
        `;
    } catch (e) {
        container.innerHTML = "Ошибка загрузки информации";
        console.error(e);
    }
}