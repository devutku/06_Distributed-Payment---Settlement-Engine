# Runs the k6 load test in Docker against the locally running app and Keycloak.
# Prerequisites: app on http://localhost:8080 and Keycloak on http://localhost:8180 (docker compose).
param(
    [string]$BaseUrl = "http://host.docker.internal:8080",
    [string]$KeycloakUrl = "http://host.docker.internal:8180"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$scripts = Join-Path $root "load\k6"

docker run --rm -i `
    -v "${scripts}:/scripts:ro" `
    -e BASE_URL=$BaseUrl `
    -e KEYCLOAK_URL=$KeycloakUrl `
    grafana/k6:0.54.0 run /scripts/transfers.js

exit $LASTEXITCODE
