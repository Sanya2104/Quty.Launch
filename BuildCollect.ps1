# BuildCollect.ps1

$StartDir = "app\src\main"
$OutputFile = "project_context.txt"
$Extensions = @(".kt", ".xml", ".gradle", ".kts")
$IgnoreDirs = @("build", ".idea", ".git", "drawable", "mipmap", "assets")

Write-Host ""
Write-Host "==========================================" -ForegroundColor Magenta
Write-Host "  PROJECT CONTEXT BUILDER" -ForegroundColor Magenta
Write-Host "==========================================" -ForegroundColor Magenta
Write-Host ""

Write-Host "Step 1: Checking folder..." -ForegroundColor Cyan

if (-not (Test-Path $StartDir)) {
    Write-Host "ERROR: Folder '$StartDir' not found!" -ForegroundColor Red
    Read-Host "Press Enter to exit..."
    exit 1
}

Write-Host "OK: Folder found: $StartDir" -ForegroundColor Green

Write-Host ""
Write-Host "Step 2: Collecting files..." -ForegroundColor Cyan

if (Test-Path $OutputFile) {
    Remove-Item $OutputFile
    Write-Host "Old file removed" -ForegroundColor DarkGray
}

$files = Get-ChildItem -Path $StartDir -Recurse -File
$totalCount = 0
$skipCount = 0

foreach ($file in $files) {
    if ($Extensions -notcontains $file.Extension) {
        $skipCount++
        continue
    }

    $skip = $false
    foreach ($ignore in $IgnoreDirs) {
        if ($file.FullName -match "\\$ignore\\") {
            $skip = $true
            break
        }
    }
    if ($skip) {
        $skipCount++
        continue
    }

    $relativePath = $file.FullName.Replace((Get-Location).Path + "\", "")

    Add-Content -Path $OutputFile -Value "`n`n=================================================="
    Add-Content -Path $OutputFile -Value "FILE: $relativePath"
    Add-Content -Path $OutputFile -Value "=================================================="
    Add-Content -Path $OutputFile -Value "`n"

    try {
        Get-Content -Path $file.FullName -Raw -Encoding UTF8 | Add-Content -Path $OutputFile
        Write-Host "  Added: $relativePath" -ForegroundColor DarkGray
        $totalCount++
    } catch {
        Add-Content -Path $OutputFile -Value "[Read error]"
        Write-Host "  Read error: $relativePath" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  DONE!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "  File:            $OutputFile" -ForegroundColor White
Write-Host "  Files collected: $totalCount" -ForegroundColor White
Write-Host "  Files skipped:   $skipCount" -ForegroundColor White
Write-Host ""
Write-Host "==========================================" -ForegroundColor Green

Write-Host ""
Read-Host "Press Enter to exit..."
exit 0