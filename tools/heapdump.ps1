# Takes a heap dump of a locally running JVM with jcmd and writes it to .\dumps.
# Default target: the java process listening on port 8080 (the payment API).
# Note: the Docker image is JRE-only (no jcmd); there the JVM dumps automatically on OutOfMemoryError.
param(
    [int]$ProcessId = 0,
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dumpDir = Join-Path $root "dumps"
New-Item -ItemType Directory -Force $dumpDir | Out-Null

if ($ProcessId -eq 0) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { throw "No process is listening on port $Port. Start the app or pass -ProcessId." }
    $ProcessId = $conn.OwningProcess
}
$proc = Get-Process -Id $ProcessId
if ($proc.ProcessName -ne "java") { throw "Process $ProcessId is '$($proc.ProcessName)', not java." }

$jcmd = "jcmd"
if ($env:JAVA_HOME) { $jcmd = Join-Path $env:JAVA_HOME "bin\jcmd.exe" }

$file = Join-Path $dumpDir ("heap-{0}.hprof" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
Write-Host "Dumping heap of PID $ProcessId to $file ..."
& $jcmd $ProcessId GC.heap_dump $file
if ($LASTEXITCODE -ne 0) { throw "jcmd failed with exit code $LASTEXITCODE" }

$sizeMb = [math]::Round((Get-Item $file).Length / 1MB, 1)
Write-Host "Heap dump written: $file ($sizeMb MB)"
Write-Host "Analyze it with: .\tools\mat-report.ps1 -Dump `"$file`""
