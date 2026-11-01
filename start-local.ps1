param(
    [switch]$CheckConfig,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs
)

$ErrorActionPreference = 'Stop'
$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Missing .env. Copy .env.example to .env and fill in your keys.'
}

$allowedKeys = @(
    'THOUGHTCODING_TRACING_ENABLED',
    'LANGFUSE_BASE_URL',
    'LANGFUSE_PUBLIC_KEY',
    'LANGFUSE_SECRET_KEY'
)
foreach ($line in Get-Content -LiteralPath $envFile) {
    $entry = $line.Trim()
    if (-not $entry -or $entry.StartsWith('#')) { continue }
    if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        throw 'Invalid .env entry. Expected NAME=value.'
    }
    $key = $Matches[1]
    $value = $Matches[2].Trim()
    if ($key -notin $allowedKeys) { continue }
    if ($value.Length -ge 2 -and (
        ($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))
    )) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}

if ($env:THOUGHTCODING_TRACING_ENABLED -eq 'true') {
    foreach ($key in @('LANGFUSE_BASE_URL', 'LANGFUSE_PUBLIC_KEY', 'LANGFUSE_SECRET_KEY')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
            throw "Missing environment variable: $key"
        }
    }
}

if ($CheckConfig) {
    Write-Host 'Local environment configuration loaded. Credentials are not displayed.'
    Write-Host 'This checks configuration only, not Langfuse authentication or tracing integration.'
    return
}

$jarPath = Join-Path $PSScriptRoot 'target/thoughtcoding.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Missing target/thoughtcoding.jar. Run mvn package first.'
}
Push-Location $PSScriptRoot
try {
    & java -jar $jarPath @AppArgs
    $appExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $appExitCode
