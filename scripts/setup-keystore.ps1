# Generates (or reuses) .tools\nexshare.keystore and sets NEXSHARE_KEYSTORE_B64 + NEXSHARE_KEYSTORE_PASS
# on GitHub Actions (via `gh secret set` if available, or prints them to copy into Settings -> Secrets).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root '.tools'
New-Item -ItemType Directory -Force -Path $Tools | Out-Null

$Ks = Join-Path $Tools 'nexshare.keystore'
$PassFile = Join-Path $Tools 'keystore.pass'

if (-not (Test-Path $Ks)) {
  $chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $bytes = New-Object byte[] 20
  $rng.GetBytes($bytes)
  $pass = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
  Set-Content -Path $PassFile -Value $pass -NoNewline
  & keytool -genkeypair -keystore $Ks -alias nexshare -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $pass -keypass $pass -dname "CN=NexShare"
  Write-Host "Created new release keystore at $Ks"
} else {
  Write-Host "Reusing existing keystore at $Ks"
}

$Pass = (Get-Content -Path $PassFile -Raw).Trim()
$B64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Ks))

if (Get-Command gh -ErrorAction SilentlyContinue) {
  try {
    $B64 | gh secret set NEXSHARE_KEYSTORE_B64
    $Pass | gh secret set NEXSHARE_KEYSTORE_PASS
    Write-Host "== Uploaded NEXSHARE_KEYSTORE_B64 and NEXSHARE_KEYSTORE_PASS to GitHub repository secrets!"
    exit 0
  } catch {
    Write-Host "gh CLI not authenticated or no remote repo; printing secrets below:"
  }
}

Write-Host ""
Write-Host "Add these two Repository Secrets in GitHub (Settings -> Secrets and variables -> Actions):"
Write-Host "--------------------------------------------------------------------------------"
Write-Host "NEXSHARE_KEYSTORE_PASS = $Pass"
Write-Host "NEXSHARE_KEYSTORE_B64  = $B64"
Write-Host "--------------------------------------------------------------------------------"

