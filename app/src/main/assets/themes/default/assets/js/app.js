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

            // Контейнер для иконки и текста
            const row = document.createElement("div");
            row.style.display = "flex";
            row.style.alignItems = "center";
            row.style.gap = "12px";

            // Иконка (теперь у всех приложений есть iconBase64)
            const img = document.createElement("img");
            img.src = "data:image/png;base64," + app.iconBase64;
            img.style.width = "32px";
            img.style.height = "32px";
            img.style.borderRadius = "6px";

            // Если иконка не загрузилась - показываем эмодзи
            img.onerror = function() {
                this.style.display = "none";
                const emoji = document.createElement("span");
                emoji.textContent = app.isCustom ? "⚙️" : "📱";
                emoji.style.fontSize = "24px";
                emoji.style.width = "32px";
                emoji.style.textAlign = "center";
                this.parentNode.insertBefore(emoji, this);
            };

            row.appendChild(img);

            // Название
            const name = document.createElement("span");
            name.textContent = app.name;
            name.style.flex = "1";

            if (app.isCustom) {
                name.style.fontWeight = "bold";
                div.style.backgroundColor = "#2A2A5A";
            }

            row.appendChild(name);
            div.appendChild(row);

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