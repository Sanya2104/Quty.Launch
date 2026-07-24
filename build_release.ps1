# build_release.ps1

# Устанавливаем кодировку UTF-8 для консоли
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  🚀 СБОРКА И ПУБЛИКАЦИЯ Quty.Launch" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ============================================
# ПОЛУЧАЕМ ТЕКУЩУЮ ВЕРСИЮ
# ============================================
Write-Host "➡️ Чтение текущей версии из build.gradle.kts..." -ForegroundColor Blue

# Проверяем существование файла
if (-not (Test-Path "app/build.gradle.kts")) {
    Write-Host "❌ Файл app/build.gradle.kts не найден!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
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

Write-Host "✅ Текущая версия: $currentVersionName $currentVersionSuffix (code: $currentVersionCode)" -ForegroundColor Green

# ============================================
# СПРАШИВАЕМ НОВУЮ ВЕРСИЮ
# ============================================
Write-Host ""
Write-Host "⚠️ Введите новый номер версии (сейчас $currentVersionName):" -ForegroundColor Yellow
$newVersionName = Read-Host "> "

if ([string]::IsNullOrEmpty($newVersionName)) {
    $newVersionName = $currentVersionName
    Write-Host "⚠️ Оставлена текущая версия: $newVersionName" -ForegroundColor Yellow
} else {
    Write-Host "✅ Новая версия: $newVersionName" -ForegroundColor Green
}

# ============================================
# УВЕЛИЧИВАЕМ versionCode
# ============================================
$newVersionCode = $currentVersionCode + 1
Write-Host "✅ Новый versionCode: $newVersionCode (был $currentVersionCode)" -ForegroundColor Green

# ============================================
# ЗАПРАШИВАЕМ CHANGELOG
# ============================================
Write-Host ""
Write-Host "⚠️ Введите описание изменений (changelog) для этой версии:" -ForegroundColor Yellow
Write-Host "   (несколько строк, для окончания введите пустую строку)" -ForegroundColor Gray
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
    Write-Host "⚠️ Использован стандартный changelog" -ForegroundColor Yellow
}

# ============================================
# ОБНОВЛЯЕМ build.gradle.kts
# ============================================
Write-Host ""
Write-Host "➡️ Обновление build.gradle.kts..." -ForegroundColor Blue

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

try {
    $newContent -join "`n" | Set-Content "app/build.gradle.kts" -Encoding UTF8 -ErrorAction Stop
    Write-Host "✅ build.gradle.kts обновлен" -ForegroundColor Green
} catch {
    Write-Host "❌ Не удалось сохранить build.gradle.kts: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

# ============================================
# СБОРКА
# ============================================
Write-Host ""
Write-Host "➡️ Запуск сборки release версии..." -ForegroundColor Blue
Write-Host "ℹ️ Запуск gradlew... (это может занять несколько минут)" -ForegroundColor Cyan

# Проверяем наличие gradlew
if (-not (Test-Path "gradlew.bat") -and -not (Test-Path "gradlew")) {
    Write-Host "❌ gradlew не найден!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

if (Test-Path "gradlew.bat") {
    .\gradlew.bat clean assembleRelease
} else {
    ./gradlew clean assembleRelease
}

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host "❌ СБОРКА НЕ УДАЛАСЬ! Код ошибки: $LASTEXITCODE" -ForegroundColor Red
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

Write-Host "✅ Сборка успешно завершена!" -ForegroundColor Green

# ============================================
# КОПИРОВАНИЕ APK
# ============================================
Write-Host ""
Write-Host "➡️ Копирование APK файла..." -ForegroundColor Blue

$apkFilename = "Quty.Launch-$newVersionName.apk"
$sourceApk = "app\build\outputs\apk\release\app-release.apk"
$destDir = "..\Quty.Launch.Server\updates\apk\"
$destApk = Join-Path $destDir $apkFilename

if (-not (Test-Path $sourceApk)) {
    Write-Host "❌ APK файл не найден: $sourceApk" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

# Создаем директорию назначения
try {
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
} catch {
    Write-Host "❌ Не удалось создать директорию $destDir" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

# Копируем файл
try {
    Copy-Item $sourceApk $destApk -Force -ErrorAction Stop
    Write-Host "✅ APK скопирован в: $destApk" -ForegroundColor Green
} catch {
    Write-Host "❌ Не удалось скопировать APK: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

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
Write-Host "✅ Размер APK: $apkSizeHuman" -ForegroundColor Green

# ============================================
# СОЗДАНИЕ version.json
# ============================================
Write-Host ""
Write-Host "➡️ Создание version.json..." -ForegroundColor Blue

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

try {
    # Удаляем BOM если он есть и сохраняем в UTF-8 without BOM
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    $bytes = $utf8NoBom.GetBytes($jsonContent)
    [System.IO.File]::WriteAllBytes($versionJson, $bytes)
    Write-Host "✅ version.json создан: $versionJson" -ForegroundColor Green
} catch {
    Write-Host "❌ Не удалось создать version.json: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Нажмите Enter для выхода..." -ForegroundColor Yellow
    Read-Host
    exit 1
}

# ============================================
# CRITICAL ОБНОВЛЕНИЕ
# ============================================
Write-Host ""
Write-Host "⚠️ Это критическое обновление? (y/N) [N]:" -ForegroundColor Yellow
$isCritical = Read-Host "> "

if ($isCritical -eq "y" -or $isCritical -eq "Y") {
    try {
        $jsonContent = [System.IO.File]::ReadAllText($versionJson)
        $jsonContent = $jsonContent -replace '"isCritical": false', '"isCritical": true'
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        $bytes = $utf8NoBom.GetBytes($jsonContent)
        [System.IO.File]::WriteAllBytes($versionJson, $bytes)
        Write-Host "⚠️ Обновление помечено как КРИТИЧЕСКОЕ" -ForegroundColor Yellow
    } catch {
        Write-Host "❌ Не удалось обновить version.json" -ForegroundColor Red
    }
}

# ============================================
# GIT PUSH
# ============================================
Write-Host ""
Write-Host "➡️ Отправка изменений в GitHub..." -ForegroundColor Blue

# Проверяем существование папки репозитория
if (-not (Test-Path "..\Quty.Launch.Server\.git")) {
    Write-Host "⚠️ Папка ..\Quty.Launch.Server не является Git репозиторием!" -ForegroundColor Yellow
    Write-Host "⚠️ Пропускаем Git push" -ForegroundColor Yellow
} else {
    Push-Location "..\Quty.Launch.Server" -ErrorAction SilentlyContinue

    git add .
    git commit -m "Release $newVersionName"
    git push origin main

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ Изменения отправлены в GitHub" -ForegroundColor Green
    } else {
        Write-Host "❌ Ошибка отправки в GitHub (код: $LASTEXITCODE)" -ForegroundColor Red
    }

    Pop-Location
}

# ============================================
# GIT TAG (опционально)
# ============================================
Write-Host ""
Write-Host "⚠️ Создать Git tag для этого релиза в Quty.Launch.Server? (y/N) [N]:" -ForegroundColor Yellow
$createTag = Read-Host "> "

if ($createTag -eq "y" -or $createTag -eq "Y") {
    Write-Host "➡️ Создание Git tag в Quty.Launch.Server..." -ForegroundColor Blue

    Push-Location "..\Quty.Launch.Server" -ErrorAction SilentlyContinue

    git tag -a "v$newVersionName" -m "Release $newVersionName"
    git push origin "v$newVersionName"

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ Tag v$newVersionName создан и отправлен в Quty.Launch.Server" -ForegroundColor Green
    } else {
        Write-Host "❌ Ошибка создания tag (код: $LASTEXITCODE)" -ForegroundColor Red
    }

    Pop-Location
}

# ============================================
# ИТОГ
# ============================================
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  ✅ ПРОЦЕСС ЗАВЕРШЕН УСПЕШНО!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "📦 Версия: $newVersionName $currentVersionSuffix"
Write-Host "🔢 Version code: $newVersionCode"
Write-Host "📄 Changelog:"
Write-Host "$changelog"
Write-Host "📁 APK: $destApk"
Write-Host "📄 JSON: $versionJson"
Write-Host "==========================================" -ForegroundColor Cyan

# ============================================
# ВЫХОД С ОЖИДАНИЕМ НАЖАТИЯ КНОПКИ
# ============================================
Write-Host ""
Write-Host "Нажмите Enter для выхода..." -ForegroundColor Cyan
Read-Host
exit 0