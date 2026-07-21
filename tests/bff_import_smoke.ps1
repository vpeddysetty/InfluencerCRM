param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$Email,
    [string]$Password = "Passw0rd!",
    [string]$BrandName = "BFF Smoke",
    [string]$CsvPath = ""
)

$ErrorActionPreference = "Stop"
Set-Location "C:/AI/InfluencerCRM"

if ([string]::IsNullOrWhiteSpace($Email)) {
    $Email = "smoke." + [guid]::NewGuid().ToString("N").Substring(0, 8) + "@example.com"
}

if ([string]::IsNullOrWhiteSpace($CsvPath)) {
    $CsvPath = "C:/AI/InfluencerCRM/.tmp_smoke_import.csv"
    @(
        "Campaign Name,Instagram Handle,Platform,Stage"
        "Smoke Campaign,@smokesam,instagram,outreach"
    ) | Set-Content -Path $CsvPath -Encoding Ascii
}

$cleanupCsv = $CsvPath -eq "C:/AI/InfluencerCRM/.tmp_smoke_import.csv"

try {
    $signupBody = @{ email = $Email; password = $Password; brandName = $BrandName } | ConvertTo-Json
    $signup = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/signup" -ContentType "application/json" -Body $signupBody

    $token = $signup.accessToken
    $userId = $signup.userId
    if ([string]::IsNullOrWhiteSpace($token) -or [string]::IsNullOrWhiteSpace($userId)) {
        throw "Signup did not return accessToken/userId"
    }

    # Discover must be multipart upload. Use curl -F to avoid PowerShell 5.1 form limitations.
    $discoverRaw = curl.exe -s -X POST "$BaseUrl/api/import-batches/discover?userId=$userId" -H "Authorization: Bearer $token" -F "file=@$CsvPath;type=text/csv"
    $discover = $discoverRaw | ConvertFrom-Json

    $batchId = $discover.importBatch.id
    if ([string]::IsNullOrWhiteSpace($batchId)) {
        throw "Discover response missing importBatch.id. Raw: $discoverRaw"
    }

    Write-Output "SIGNUP_OK userId=$userId"
    Write-Output "DISCOVER_OK batchId=$batchId"
}
finally {
    if ($cleanupCsv) {
        Remove-Item -Path $CsvPath -ErrorAction SilentlyContinue
    }
}
