param(
    [switch]$IncludeLint,
    [switch]$IncludeCheck,
    [switch]$WarningModeAll
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'

$gradleArgs = @('test', '--console=plain')
if ($WarningModeAll) {
    $gradleArgs += '--warning-mode'
    $gradleArgs += 'all'
}

Write-Host "[run_checks_utf8] Running: .\\gradlew.bat $($gradleArgs -join ' ')"
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ($IncludeLint) {
    Write-Host '[run_checks_utf8] Running: .\\gradlew.bat lint --console=plain'
    & .\gradlew.bat lint --console=plain
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

if ($IncludeCheck) {
    Write-Host '[run_checks_utf8] Running: .\\gradlew.bat check --console=plain'
    & .\gradlew.bat check --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'check task failed (commonly due to unavailable network dependency for checkstyle in sandbox).'
        exit $LASTEXITCODE
    }
}

Write-Host '[run_checks_utf8] All requested tasks completed.'
