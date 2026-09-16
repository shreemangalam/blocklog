# Seeds the running BlockLog backend with a realistic tenant of synthetic logs.
# Requires: backend already running on http://localhost:8080 (or pass -Url).
#
# Examples:
#   .\scripts\seed-demo.ps1                       # 1000 records into demo-shop
#   .\scripts\seed-demo.ps1 -Tenant acme -Count 5000
#   .\scripts\seed-demo.ps1 -Url http://localhost:9090

param(
    [string]$Tenant = "demo-shop",
    [int]$Count = 1000,
    [int]$Batch = 25,
    [int]$Seed = 42,
    [int]$Hours = 24,
    [string]$Url = "http://localhost:8080"
)

$backendDir = Join-Path $PSScriptRoot "..\backend"
Push-Location $backendDir
try {
    # Compile if classes are stale (fast no-op when up to date).
    & .\mvnw.cmd -B -q compile
    if ($LASTEXITCODE -ne 0) { throw "backend compile failed" }

    # Run the generator against the live server.
    & java -cp target\classes com.blocklog.demo.SyntheticIngest `
        --tenant $Tenant `
        --count $Count `
        --batch $Batch `
        --seed $Seed `
        --hours $Hours `
        --url $Url
    if ($LASTEXITCODE -ne 0) { throw "seed generator failed with exit $LASTEXITCODE" }
} finally {
    Pop-Location
}
