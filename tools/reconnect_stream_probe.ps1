[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$OutDir = "tools/out",
    [string]$PackageName = "org.las2mile.scrcpy"
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$PSDefaultParameterValues['Out-File:Encoding'] = 'utf8'
$PSDefaultParameterValues['Set-Content:Encoding'] = 'utf8'

function Get-AdbArgs {
    param([string[]]$Tail)
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        return $Tail
    }
    return @("-s", $Serial) + $Tail
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb 未找到，请先把 platform-tools 加入 PATH。"
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -Path $OutDir -ItemType Directory -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $OutDir ("reconnect-stream-{0}.log" -f $timestamp)
$errFile = Join-Path $OutDir ("reconnect-stream-{0}.stderr.log" -f $timestamp)
$summaryFile = Join-Path $OutDir ("reconnect-stream-{0}-summary.txt" -f $timestamp)

& adb @(Get-AdbArgs @("wait-for-device"))
& adb @(Get-AdbArgs @("logcat", "-c"))
& adb @(Get-AdbArgs @("shell", "am", "start", "-n", "$PackageName/.SettingsActivity")) | Out-Null

$logArgs = Get-AdbArgs @("logcat", "-v", "time", "scrcpy:I", "AndroidRuntime:E", "*:S")
$logcatProc = Start-Process -FilePath "adb" -ArgumentList $logArgs -NoNewWindow -PassThru `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile

Write-Host ""
Write-Host ("日志采集中: {0}" -f $logFile)
Write-Host "请在设备端按以下步骤手工操作："
Write-Host "1. 建立一次正常连接（画面出来）。"
Write-Host "2. 连续点击 3 次“重连（仅重启流）”。"
Write-Host "3. 再点击 1 次“重新部署并重连”。"
Write-Host "4. 回到本窗口按 Enter 结束采集。"
Write-Host ""
[void](Read-Host)

if (-not $logcatProc.HasExited) {
    Stop-Process -Id $logcatProc.Id -Force
    $null = $logcatProc.WaitForExit(3000)
}

$successPattern = "Connected device:|Video codec:"
$failurePattern = "Failed to open scrcpy streams|Connection loop runtime error|Connection failed|Connection closed"

$successHits = @()
$failureHits = @()
if (Test-Path -LiteralPath $logFile) {
    $successHits = Select-String -Path $logFile -Pattern $successPattern
    $failureHits = Select-String -Path $logFile -Pattern $failurePattern
}

$summaryLines = @(
    ("timestamp={0}" -f $timestamp),
    ("package={0}" -f $PackageName),
    ("log_file={0}" -f (Resolve-Path -LiteralPath $logFile)),
    ("stderr_file={0}" -f (Resolve-Path -LiteralPath $errFile)),
    ("success_count={0}" -f $successHits.Count),
    ("failure_count={0}" -f $failureHits.Count),
    "--- success lines ---"
)

$summaryLines += ($successHits | ForEach-Object { $_.Line })
$summaryLines += "--- failure lines ---"
$summaryLines += ($failureHits | ForEach-Object { $_.Line })

$summaryLines | Out-File -FilePath $summaryFile -Encoding utf8

Write-Host ""
Write-Host ("采集完成。摘要: {0}" -f $summaryFile)
Write-Host ("日志文件: {0}" -f $logFile)
