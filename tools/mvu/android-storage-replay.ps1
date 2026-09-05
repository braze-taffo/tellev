param([string]$Serial = "HA25GHH4")
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$package = "app.tellev.mvuvalidation"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$results = Join-Path $root "build\mvu-oracle\results"
New-Item -ItemType Directory -Force -Path $results | Out-Null
$records = @()
foreach ($stage in @("PAYLOAD_SYNCED", "PREPARED", "REPLACED", "COMMITTED")) {
    $prepare = (& $adb -s $Serial shell am instrument -w -e class app.tellev.JournalCrashTest -e journalPhase prepare -e journalStage $stage "$package.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String)
    $reached = (& $adb -s $Serial shell run-as $package cat "cache/mvu-process-crash/$stage/reached.txt" | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $reached -ne $stage) { throw "Device did not reach crash stage $stage" }
    $recover = (& $adb -s $Serial shell am instrument -w -e class app.tellev.JournalCrashTest -e journalPhase recover -e journalStage $stage "$package.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String)
    if ($recover -notmatch 'OK \(1 test\)') { throw "Recovery failed at ${stage}: $recover" }
    $records += @{stage=$stage;processKilled=$true;prepare=$prepare;recover=$recover;passed=$true}
    Write-Output "Verified process-death recovery: $stage"
}
@{device=$Serial;package=$package;stages=$records;limitations=@("No power-cut simulation", "No physical disk-full simulation")} |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $results "android-storage-process-death.json") -Encoding utf8
