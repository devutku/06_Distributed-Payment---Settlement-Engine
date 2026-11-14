# Runs Eclipse Memory Analyzer (MAT) headless on a heap dump and produces HTML reports:
#   <dump>_Leak_Suspects.zip  - likely leaks with their accumulation points
#   <dump>_System_Overview.zip - heap summary, top consumers, class histogram
# Requires Eclipse MAT installed locally (https://eclipse.dev/mat/) and MAT_HOME pointing at it.
param(
    [Parameter(Mandatory = $true)][string]$Dump,
    [string]$MatHome = $env:MAT_HOME
)

$ErrorActionPreference = "Stop"

if (-not $MatHome) {
    throw "MAT_HOME is not set. Install Eclipse MAT (https://eclipse.dev/mat/) and set MAT_HOME to its folder, or pass -MatHome."
}
$parser = Join-Path $MatHome "ParseHeapDump.bat"
if (-not (Test-Path $parser)) { throw "ParseHeapDump.bat not found in '$MatHome'." }
if (-not (Test-Path $Dump)) { throw "Heap dump '$Dump' not found." }

$dumpPath = (Resolve-Path $Dump).Path
Write-Host "Parsing $dumpPath with Eclipse MAT (this can take a while for large dumps) ..."
& $parser $dumpPath org.eclipse.mat.api:suspects org.eclipse.mat.api:overview
if ($LASTEXITCODE -ne 0) { throw "ParseHeapDump failed with exit code $LASTEXITCODE" }

$base = [System.IO.Path]::Combine([System.IO.Path]::GetDirectoryName($dumpPath), [System.IO.Path]::GetFileNameWithoutExtension($dumpPath))
foreach ($report in @("_Leak_Suspects.zip", "_System_Overview.zip")) {
    $zip = "$base$report"
    if (Test-Path $zip) { Write-Host "Report: $zip" } else { Write-Warning "Expected report not found: $zip" }
}
