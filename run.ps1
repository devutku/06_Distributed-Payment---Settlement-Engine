# AegisPay Engine Başlatıcı Scripti
param (
    [switch]$Docker,
    [switch]$Local,
    [switch]$Test
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  AegisPay | Distributed Payment & Settlement Engine     " -ForegroundColor Green
Write-Host "  Clean Architecture (Hexagonal) &bull; Spring Boot 3.3  " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# JDK 21 Tespiti
$jdkPath = "$env:LOCALAPPDATA\Programs\Java\jdk-21.0.5+11"
if (Test-Path $jdkPath) {
    $env:JAVA_HOME = $jdkPath
    $env:Path = "$jdkPath\bin;" + $env:Path
    Write-Host "[OK] JDK 21 aktif: $jdkPath" -ForegroundColor Green
} else {
    Write-Host "[INFO] Sistem varsayılan Java kullanılıyor." -ForegroundColor Yellow
}

# Maven Tespiti
$mavenCmd = "$env:LOCALAPPDATA\Programs\Maven\apache-maven-3.9.9\bin\mvn.cmd"
if (-not (Test-Path $mavenCmd)) {
    $mavenCmd = ".\mvnw.cmd"
}
Write-Host "[OK] Maven aktif: $mavenCmd" -ForegroundColor Green

if ($Docker) {
    Write-Host "[DOCKER] PostgreSQL, Redis, Kafka, Prometheus ve Grafana ayağa kaldırılıyor..." -ForegroundColor Magenta
    docker compose -f docker/docker-compose.yml up -d
    Write-Host "[OK] Altyapı konteynerleri hazır!" -ForegroundColor Green
    exit 0
}

if ($Test) {
    Write-Host "[TEST] Tüm modül testleri çalıştırılıyor..." -ForegroundColor Magenta
    & $mavenCmd clean test
    exit 0
}

if ($Local) {
    Write-Host "[RUN] In-memory H2 profili ile başlatılıyor (Offline mod)..." -ForegroundColor Yellow
    & $mavenCmd -pl payment-api spring-boot:run -Dspring-boot.run.profiles=local
} else {
    Write-Host "[RUN] Standart profille başlatılıyor (PostgreSQL + Redis + Kafka)..." -ForegroundColor Green
    & $mavenCmd -pl payment-api spring-boot:run
}

