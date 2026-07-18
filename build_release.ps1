# build_release.ps1

# Устанавливаем кодировку UTF-8 для консоли
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Флаг для отслеживания ошибок
$hasError = $false

Write-Host "=========================================="
Write-Host "🚀 Начинаем процесс сборки и публикации Quty.Launch..."
Write-Host "=========================================="
Write-Host ""

# Функции
function Print-Step {
    Write-Host "➡️ $args" -ForegroundColor Blue
}

function Print-Success {
    Write-Host "✅ $args" -ForegroundColor Green
}

function Print-Warning {
    Write-Host "⚠️ $args" -ForegroundColor Yellow
}

function Print-Error {
    Write-Host "❌ $args" -ForegroundColor Red
    $script:hasError = $true
}

function Print-Info {
    Write-Host "ℹ️ $args" -ForegroundColor Cyan
}

# ============================================
# ПОЛУЧАЕМ ТЕКУЩУЮ ВЕРСИЮ
# ============================================
Print-Step "Чтение текущей версии из build.gradle.kts..."

# Проверяем существование файла
if (-not (Test-Path "app/build.gradle.kts")) {
    Print-Error "Файл app/build.gradle.kts не найден!"
    return
}

$gradleFile = Get-Content "app/build.gradle.kts" -Raw -Encoding UTF8

$currentVersionCode = 0
$currentVersionName = ""
$currentVersionSuffix = ""

# Разбираем построчно
foreach ($line in $gradleFile -split "`n") {
    if ($line -match 'versionCode\s*=\s*(\d+)') {
        $currentVersionCode = [int]$matches[1]
    }
    if ($line -match 'versionName\s*=\s*"([^"]+)"' -and $line -notmatch 'versionNameSuffix') {
        $currentVersionName = $matches[1]
    }
    if ($line -match 'versionNameSuffix\s*=\s*"([^"]+)"') {
        $currentVersionSuffix = $matches[1]
    }
}

Print-Success "Текущая версия: $currentVersionName $currentVersionSuffix (code: $currentVersionCode)"

# ============================================
# СПРАШИВАЕМ НОВУЮ ВЕРСИЮ
# ============================================
Write-Host ""
Print-Warning "Введите новый номер версии (сейчас $currentVersionName):"
$newVersionName = Read-Host "> "

if ([string]::IsNullOrEmpty($newVersionName)) {
    $newVersionName = $currentVersionName
    Print-Warning "Оставлена текущая версия: $newVersionName"
} else {
    Print-Success "Новая версия: $newVersionName"
}

# ============================================
# УВЕЛИЧИВАЕМ versionCode
# ============================================
$newVersionCode = $currentVersionCode + 1
Print-Success "Новый versionCode: $newVersionCode (был $currentVersionCode)"

# ============================================
# ЗАПРАШИВАЕМ CHANGELOG
# ============================================
Write-Host ""
Print-Warning "Введите описание изменений (changelog) для этой версии:"
Print-Warning "(несколько строк, для окончания введите пустую строку)"
Write-Host ""

$changelog = ""
while ($true) {
    $line = Read-Host "> "
    if ([string]::IsNullOrEmpty($line)) {
        break
    }
    if ([string]::IsNullOrEmpty($changelog)) {
        $changelog = $line
    } else {
        $changelog = $changelog + "`n" + $line
    }
}

if ([string]::IsNullOrEmpty($changelog)) {
    $changelog = "Исправление багов и улучшение производительности"
    Print-Warning "Использован стандартный changelog"
}

# ============================================
# ОБНОВЛЯЕМ build.gradle.kts
# ============================================
Print-Step "Обновление build.gradle.kts..."

$newContent = @()
foreach ($line in ($gradleFile -split "`n")) {
    if ($line -match 'versionCode\s*=') {
        $newContent += "    versionCode = $newVersionCode"
    } elseif ($line -match 'versionName\s*=' -and $line -notmatch 'versionNameSuffix') {
        $newContent += "    versionName = `"$newVersionName`""
    } else {
        $newContent += $line
    }
}

# Сохраняем с правильной кодировкой
$newContent -join "`n" | Set-Content "app/build.gradle.kts" -Encoding UTF8

Print-Success "build.gradle.kts обновлен"

# ============================================
# СБОРКА
# ============================================
Print-Step "Запуск сборки release версии..."

# Проверяем наличие gradlew
if (-not (Test-Path "gradlew.bat") -and -not (Test-Path "gradlew")) {
    Print-Error "gradlew не найден!"
    return
}

# Запускаем gradlew с выводом всех ошибок
Print-Info "Запуск gradlew... (это может занять несколько минут)"

if (Test-Path "gradlew.bat") {
    .\gradlew.bat clean assembleRelease
} else {
    ./gradlew clean assembleRelease
}

if ($LASTEXITCODE -ne 0) {
    Print-Error "Сборка завершилась с ошибкой (код: $LASTEXITCODE)"
    Print-Info "Проверьте вывод выше для поиска ошибки"
    return
}

Print-Success "Сборка успешно завершена!"

# ============================================
# КОПИРОВАНИЕ APK
# ============================================
Print-Step "Копирование APK файла..."

$apkFilename = "Quty.Launch-$newVersionName.apk"
$sourceApk = "app\build\outputs\apk\release\app-release.apk"
$destDir = "..\Quty.Launch.Server\updates\apk\"
$destApk = Join-Path $destDir $apkFilename

if (-not (Test-Path $sourceApk)) {
    Print-Error "APK файл не найден: $sourceApk"
    return
}

# Создаем директорию назначения
New-Item -ItemType Directory -Force -Path $destDir | Out-Null

# Копируем файл
Copy-Item $sourceApk $destApk -Force

Print-Success "APK скопирован в: $destApk"

# ============================================
# РАЗМЕР APK
# ============================================
$apkSize = (Get-Item $destApk).Length
$apkSizeHuman = if ($apkSize -gt 1MB) {
    "{0:N2} MB" -f ($apkSize / 1MB)
} elseif ($apkSize -gt 1KB) {
    "{0:N0} KB" -f ($apkSize / 1KB)
} else {
    "{0} B" -f $apkSize
}
Print-Success "Размер APK: $apkSizeHuman"

# ============================================
# СОЗДАНИЕ version.json
# ============================================
Print-Step "Создание version.json..."

$versionJson = "..\Quty.Launch.Server\updates\version.json"
$jsonChangelog = $changelog -replace "`n", "\n"

$jsonContent = @"
{
  "version": "$newVersionName",
  "versionCode": $newVersionCode,
  "downloadUrl": "https://raw.githubusercontent.com/Sanya2104/Quty.Launch.Server/main/updates/apk/$apkFilename",
  "changelog": "$jsonChangelog",
  "releaseDate": "$(Get-Date -Format dd-MM-yyyy)",
  "isCritical": false,
  "size": "$apkSizeHuman"
}
"@

# Удаляем BOM если он есть и сохраняем в UTF-8 without BOM
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$bytes = $utf8NoBom.GetBytes($jsonContent)
[System.IO.File]::WriteAllBytes($versionJson, $bytes)

Print-Success "version.json создан: $versionJson"

# ============================================
# CRITICAL ОБНОВЛЕНИЕ
# ============================================
Write-Host ""
Print-Warning "Это критическое обновление? (y/N) [N]:"
$isCritical = Read-Host "> "

if ($isCritical -eq "y" -or $isCritical -eq "Y") {
    # Читаем файл
    $jsonContent = [System.IO.File]::ReadAllText($versionJson)
    # Заменяем флаг
    $jsonContent = $jsonContent -replace '"isCritical": false', '"isCritical": true'
    # Сохраняем без BOM
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    $bytes = $utf8NoBom.GetBytes($jsonContent)
    [System.IO.File]::WriteAllBytes($versionJson, $bytes)
    Print-Warning "Обновление помечено как КРИТИЧЕСКОЕ"
}

# ============================================
# GIT PUSH
# ============================================
Print-Step "Отправка изменений в GitHub..."

# Проверяем существование папки репозитория
if (-not (Test-Path "..\Quty.Launch.Server\.git")) {
    Print-Warning "Папка ..\Quty.Launch.Server не является Git репозиторием!"
    Print-Warning "Пропускаем Git push"
} else {
    Push-Location "..\Quty.Launch.Server"

    git add .
    git commit -m "Release $newVersionName"
    git push origin main

    if ($LASTEXITCODE -eq 0) {
        Print-Success "Изменения отправлены в GitHub"
    } else {
        Print-Error "Ошибка отправки в GitHub"
    }

    Pop-Location
}

# ============================================
# GIT TAG (опционально) - ДЛЯ РЕПОЗИТОРИЯ СЕРВЕРА
# ============================================
Write-Host ""
Print-Warning "Создать Git tag для этого релиза в Quty.Launch.Server? (y/N) [N]:"
$createTag = Read-Host "> "

if ($createTag -eq "y" -or $createTag -eq "Y") {
    Print-Step "Создание Git tag в Quty.Launch.Server..."

    # Переходим в папку сервера
    Push-Location "..\Quty.Launch.Server"

    git tag -a "v$newVersionName" -m "Release $newVersionName"
    git push origin "v$newVersionName"

    if ($LASTEXITCODE -eq 0) {
        Print-Success "Tag v$newVersionName создан и отправлен в Quty.Launch.Server"
    } else {
        Print-Error "Ошибка создания tag"
    }

    Pop-Location
}

# ============================================
# ИТОГ
# ============================================
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Print-Success "ПРОЦЕСС ЗАВЕРШЕН УСПЕШНО!"
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "📦 Версия: $newVersionName $currentVersionSuffix"
Write-Host "🔢 Version code: $newVersionCode"
Write-Host "📄 Changelog:"
Write-Host "$changelog"
Write-Host "📁 APK: $destApk"
Write-Host "📄 JSON: $versionJson"
Write-Host "==========================================" -ForegroundColor Cyan

# ============================================
# ВЫХОД
# ============================================

if ($hasError) {
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Red
    Print-Error "ПРОЦЕСС ЗАВЕРШЕН С ОШИБКАМИ!"
    Write-Host "==========================================" -ForegroundColor Red
}

# Пауза, чтобы увидеть результат
Read-Host "`nНажмите Enter для выхода"