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
            if (app.isCustom) {
                div.setAttribute("data-custom", "true");
            }

            const row = document.createElement("div");
            row.className = "row";

            // Иконка
            const img = document.createElement("img");
            img.src = "data:image/png;base64," + app.iconBase64;
            img.alt = app.name;

            img.onerror = function() {
                this.style.display = "none";
                const emoji = document.createElement("span");
                emoji.className = "emoji-placeholder";
                emoji.textContent = app.isCustom ? "⚙️" : "📱";
                this.parentNode.insertBefore(emoji, this);
            };

            row.appendChild(img);

            // Название
            const name = document.createElement("span");
            name.className = "name";
            name.textContent = app.name;

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
            <p><b>Устройство:</b> <span>${info.device}</span></p>
            <p><b>Android:</b> <span>${info.version}</span></p>
        `;
    } catch (e) {
        container.innerHTML = "Ошибка загрузки информации";
        console.error(e);
    }
}