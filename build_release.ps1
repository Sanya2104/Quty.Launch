# build_release.ps1

# Устанавливаем кодировку UTF-8 для консоли
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ============================================
# ХЕЛПЕРЫ ДЛЯ ВЫВОДА
# ============================================

# Заголовок этапа — Cyan, крупная рамка
function Write-StageHeader {
    param([string]$Number, [string]$Title)
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "  ЭТАП $Number. $Title" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host ""
}

# Тонкий разделитель между вопросами — DarkGray
function Write-Divider {
    Write-Host "  ──────────────────────────────────────" -ForegroundColor DarkGray
    Write-Host ""
}

# Вопрос к пользователю — Yellow (яркий)
function Write-Question {
    param([string]$Text)
    Write-Host "  ⚠️  $Text" -ForegroundColor Yellow
}

# Результат ответа / подтверждение — DarkYellow (оливковый)
function Write-Result {
    param([string]$Text)
    Write-Host "  ⚠️  $Text" -ForegroundColor DarkYellow
}

# Приглашение ввода — White
function Read-Answer {
    param([string]$Prompt = ">")
    Write-Host "  $Prompt " -ForegroundColor White -NoNewline
    return Read-Host
}

# Системная информация — Gray
function Write-Info {
    param([string]$Text)
    Write-Host "  ℹ️  $Text" -ForegroundColor Gray
}

# Подшаг / действие — DarkGray
function Write-Step {
    param([string]$Text)
    Write-Host "  ➡️  $Text" -ForegroundColor DarkGray
}

# Успех — Green
function Write-Success {
    param([string]$Text)
    Write-Host "  ✅ $Text" -ForegroundColor Green
}

# Предупреждение (проблема, не ответ) — Yellow
function Write-Warn {
    param([string]$Text)
    Write-Host "  ⚠️  $Text" -ForegroundColor Yellow
}

# Ошибка — Red
function Write-Err {
    param([string]$Text)
    Write-Host "  ❌ $Text" -ForegroundColor Red
}

# Критический блок (рамка) — Red
function Write-CriticalBlock {
    param([string]$Text)
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host "  ❌ $Text" -ForegroundColor Red
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host ""
}

# ============================================
# СТАРТ
# ============================================
Write-Host ""
Write-Host "##########################################" -ForegroundColor Magenta
Write-Host "#                                        #" -ForegroundColor Magenta
Write-Host "#     🚀 QUTY.LAUNCH — СБОРКА И ПУБЛИКАЦИЯ" -ForegroundColor Magenta
Write-Host "#                                        #" -ForegroundColor Magenta
Write-Host "##########################################" -ForegroundColor Magenta
Write-Host ""

# ============================================
# ЭТАП 1: ЧТЕНИЕ ТЕКУЩЕЙ ВЕРСИИ
# ============================================
Write-StageHeader -Number "1" -Title "ЧТЕНИЕ ТЕКУЩЕЙ ВЕРСИИ"

# Проверяем существование файла
if (-not (Test-Path "app/build.gradle.kts")) {
    Write-Err "Файл app/build.gradle.kts не найден!"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

Write-Info "Файл: app/build.gradle.kts"

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

Write-Success "Текущая версия: $currentVersionName $currentVersionSuffix"
Write-Info "Version code: $currentVersionCode"

# ============================================
# ЭТАП 2: НАСТРОЙКИ РЕЛИЗА
# ============================================
Write-StageHeader -Number "2" -Title "НАСТРОЙКИ РЕЛИЗА"

# --- Вопрос 1: Новая версия ---
Write-Question "Введите новый номер версии (сейчас $currentVersionName):"
$newVersionName = Read-Answer

if ([string]::IsNullOrEmpty($newVersionName)) {
    $newVersionName = $currentVersionName
    Write-Result "Оставлена текущая версия: $newVersionName"
} else {
    Write-Success "Новая версия: $newVersionName"
}

Write-Divider

# --- Вопрос 2: Changelog ---
Write-Question "Введите описание изменений (changelog) для этой версии:"
Write-Info "Несколько строк, для окончания введите пустую строку"
Write-Host ""

$changelog = ""
while ($true) {
    $line = Read-Answer
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
    Write-Result "Использован стандартный changelog"
}

Write-Divider

# --- Вопрос 3: Критическое обновление ---
Write-Question "Это критическое обновление? (y/N) [N]:"
$isCriticalInput = Read-Answer
$isCritical = ($isCriticalInput -eq "y" -or $isCriticalInput -eq "Y")
if ($isCritical) {
    Write-Result "Обновление будет помечено как КРИТИЧЕСКОЕ"
} else {
    Write-Success "Обычное обновление"
}

Write-Divider

# --- Вопрос 4: Git tag ---
Write-Question "Создать Git tag для этого релиза в Quty.Launch.Server? (y/N) [N]:"
$createTagInput = Read-Answer
$createTag = ($createTagInput -eq "y" -or $createTagInput -eq "Y")
if ($createTag) {
    Write-Success "Tag v$newVersionName будет создан"
} else {
    Write-Success "Tag создаваться не будет"
}

# Подсчёт нового versionCode
$newVersionCode = $currentVersionCode + 1

# ============================================
# ЭТАП 3: ОБНОВЛЕНИЕ BUILD.GRADLE.KTS
# ============================================
Write-StageHeader -Number "3" -Title "ОБНОВЛЕНИЕ BUILD.GRADLE.KTS"

Write-Step "versionCode: $currentVersionCode → $newVersionCode"
Write-Step "versionName: $currentVersionName → $newVersionName"

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
    $contentToWrite = $newContent -join "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    $bytes = $utf8NoBom.GetBytes($contentToWrite)
    [System.IO.File]::WriteAllBytes("app/build.gradle.kts", $bytes)
    Write-Success "build.gradle.kts обновлен"
} catch {
    Write-Err "Не удалось сохранить build.gradle.kts: $($_.Exception.Message)"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

# ============================================
# ЭТАП 4: СБОРКА RELEASE
# ============================================
Write-StageHeader -Number "4" -Title "СБОРКА RELEASE"

Write-Step "Запуск: gradlew clean assembleRelease"
Write-Info "Это может занять несколько минут..."
Write-Host ""

# Проверяем наличие gradlew
if (-not (Test-Path "gradlew.bat") -and -not (Test-Path "gradlew")) {
    Write-Err "gradlew не найден!"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

if (Test-Path "gradlew.bat") {
    .\gradlew.bat clean assembleRelease
} else {
    ./gradlew clean assembleRelease
}

if ($LASTEXITCODE -ne 0) {
    Write-CriticalBlock "СБОРКА НЕ УДАЛАСЬ! Код ошибки: $LASTEXITCODE"
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

Write-Host ""
Write-Success "Сборка успешно завершена!"

# ============================================
# ЭТАП 5: КОПИРОВАНИЕ APK
# ============================================
Write-StageHeader -Number "5" -Title "КОПИРОВАНИЕ APK"

$apkFilename = "Quty.Launch-$newVersionName.apk"
$sourceApk = "app\build\outputs\apk\release\app-release.apk"
$destDir = "..\Quty.Launch.Server\updates\apk\"
$destApk = Join-Path $destDir $apkFilename

Write-Info "Источник:   $sourceApk"
Write-Info "Назначение: $destApk"

if (-not (Test-Path $sourceApk)) {
    Write-Err "APK файл не найден: $sourceApk"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

try {
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
} catch {
    Write-Err "Не удалось создать директорию $destDir"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

try {
    Copy-Item $sourceApk $destApk -Force -ErrorAction Stop
    Write-Success "APK скопирован"
} catch {
    Write-Err "Не удалось скопировать APK: $($_.Exception.Message)"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

# Размер APK
$apkSize = (Get-Item $destApk).Length
$apkSizeHuman = if ($apkSize -gt 1MB) {
    "{0:N2} MB" -f ($apkSize / 1MB)
} elseif ($apkSize -gt 1KB) {
    "{0:N0} KB" -f ($apkSize / 1KB)
} else {
    "{0} B" -f $apkSize
}
Write-Info "Размер APK: $apkSizeHuman"

# ============================================
# ЭТАП 6: СОЗДАНИЕ VERSION.JSON
# ============================================
Write-StageHeader -Number "6" -Title "СОЗДАНИЕ VERSION.JSON"

$versionJson = "..\Quty.Launch.Server\updates\version.json"
$jsonChangelog = $changelog -replace "`n", "\n"

$isCriticalJson = if ($isCritical) { "true" } else { "false" }

Write-Info "Файл: $versionJson"
Write-Step "isCritical: $isCriticalJson"

$jsonContent = @"
{
  "version": "$newVersionName",
  "versionCode": $newVersionCode,
  "downloadUrl": "https://raw.githubusercontent.com/Sanya2104/Quty.Launch.Server/main/updates/apk/$apkFilename",
  "changelog": "$jsonChangelog",
  "releaseDate": "$(Get-Date -Format dd-MM-yyyy)",
  "isCritical": $isCriticalJson,
  "size": "$apkSizeHuman"
}
"@

try {
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    $bytes = $utf8NoBom.GetBytes($jsonContent)
    [System.IO.File]::WriteAllBytes($versionJson, $bytes)
    Write-Success "version.json создан"
} catch {
    Write-Err "Не удалось создать version.json: $($_.Exception.Message)"
    Write-Host ""
    Write-Host "  Нажмите Enter для выхода..." -ForegroundColor White
    Read-Host
    exit 1
}

# ============================================
# ЭТАП 7: GIT PUSH
# ============================================
Write-StageHeader -Number "7" -Title "GIT PUSH"

if (-not (Test-Path "..\Quty.Launch.Server\.git")) {
    Write-Warn "Папка ..\Quty.Launch.Server не является Git репозиторием!"
    Write-Warn "Пропускаем Git push"
} else {
    Push-Location "..\Quty.Launch.Server" -ErrorAction SilentlyContinue

    Write-Step "git add ."
    git add .

    Write-Step "git commit -m `"Release $newVersionName`""
    git commit -m "Release $newVersionName"

    Write-Step "git push origin main"
    git push origin main

    if ($LASTEXITCODE -eq 0) {
        Write-Success "Изменения отправлены в GitHub"
    } else {
        Write-Err "Ошибка отправки в GitHub (код: $LASTEXITCODE)"
    }

    Pop-Location
}

# ============================================
# ЭТАП 8: GIT TAG (если выбрано)
# ============================================
if ($createTag) {
    Write-StageHeader -Number "8" -Title "GIT TAG"

    Push-Location "..\Quty.Launch.Server" -ErrorAction SilentlyContinue

    Write-Step "git tag -a `"v$newVersionName`""
    git tag -a "v$newVersionName" -m "Release $newVersionName"

    Write-Step "git push origin `"v$newVersionName`""
    git push origin "v$newVersionName"

    if ($LASTEXITCODE -eq 0) {
        Write-Success "Tag v$newVersionName создан и отправлен"
    } else {
        Write-Err "Ошибка создания tag (код: $LASTEXITCODE)"
    }

    Pop-Location
} else {
    Write-Host ""
    Write-Info "Git tag не создавался"
}

# ============================================
# ИТОГ
# ============================================
Write-Host ""
Write-Host "##########################################" -ForegroundColor Green
Write-Host "#                                        #" -ForegroundColor Green
Write-Host "#     ✅ ПРОЦЕСС ЗАВЕРШЕН УСПЕШНО!      #" -ForegroundColor Green
Write-Host "#                                        #" -ForegroundColor Green
Write-Host "##########################################" -ForegroundColor Green
Write-Host ""
Write-Host "  📦 Версия:       " -NoNewline -ForegroundColor Gray
Write-Host "$newVersionName $currentVersionSuffix" -ForegroundColor White
Write-Host "  🔢 Version code: " -NoNewline -ForegroundColor Gray
Write-Host "$newVersionCode" -ForegroundColor White
Write-Host "  ⚠️  Critical:    " -NoNewline -ForegroundColor Gray
Write-Host "$isCritical" -ForegroundColor White
Write-Host "  🏷️  Git tag:     " -NoNewline -ForegroundColor Gray
Write-Host "$createTag" -ForegroundColor White
Write-Host ""
Write-Host "  📄 Changelog:" -ForegroundColor Gray
Write-Host "$changelog" -ForegroundColor White
Write-Host ""
Write-Host "  📁 APK:  " -NoNewline -ForegroundColor Gray
Write-Host "$destApk" -ForegroundColor White
Write-Host "  📄 JSON: " -NoNewline -ForegroundColor Gray
Write-Host "$versionJson" -ForegroundColor White
Write-Host ""
Write-Host "==========================================" -ForegroundColor Green

# ============================================
# ВЫХОД
# ============================================
Write-Host ""
Write-Host "  Нажмите Enter для выхода..." -ForegroundColor Cyan
Read-Host
exit 0