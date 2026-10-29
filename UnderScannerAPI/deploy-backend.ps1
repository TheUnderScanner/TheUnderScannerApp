# Deploy UnderScannerAPI/app.py (the version COMMITTED at HEAD) to the Jetson.
#
#   .\UnderScannerAPI\deploy-backend.ps1               # normal deploy
#   .\UnderScannerAPI\deploy-backend.ps1 -DryRun       # checks only, change nothing
#   .\UnderScannerAPI\deploy-backend.ps1 -JetsonHost 10.42.0.1 -User orin4slam
#
# Safety model:
#   * Only committed code is deployed (the HEAD blob, byte-exact, LF endings), so every
#     version that ever runs on the Jetson exists in git history.
#   * Before overwriting, the Jetson's current app.py is hashed and looked up in the
#     history of UnderScannerAPI/app.py. Found -> it is a known version, safe to replace.
#     Not found -> someone edited it on the Jetson: abort, and keep it as
#     UnderScannerAPI/app.jetson.py so it can be merged first.
#   * Refuses while a scan is running (the restart would kill it).
#   * The new file is syntax-checked on the Jetson BEFORE it replaces the old one, and the
#     old one is kept as app.py.bak. If the service doesn't come back, it rolls back.
#
# Each ssh/scp asks for the password unless an SSH key is installed (see ReadMe.md);
# the restart also asks for the sudo password.
# This file is ASCII on purpose: Windows PowerShell 5.1 misreads BOM-less UTF-8.

param(
    [string]$JetsonHost = "10.42.0.1",
    [string]$User = "orin4slam",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$RepoPath = "UnderScannerAPI/app.py"
$RemoteDir = "underscanner-api"          # relative to home: the Jetson's SFTP rejects absolute paths
$Target = "$User@$JetsonHost"
$Api = "http://${JetsonHost}:8000"
$JetsonCopy = Join-Path $PSScriptRoot "app.jetson.py"

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERREUR: $msg" -ForegroundColor Red; exit 1 }

function Invoke-Native {
    # Run a native command, fail on a non-zero exit code.
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { Fail "$Exe a echoue (code $LASTEXITCODE)" }
}

function Export-GitBlob($Spec, $OutFile) {
    # Byte-exact blob export (PowerShell redirection would re-encode the text).
    $psi = New-Object System.Diagnostics.ProcessStartInfo "git", "-C `"$Root`" cat-file blob $Spec"
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    $fs = [System.IO.File]::Create($OutFile)
    try { $p.StandardOutput.BaseStream.CopyTo($fs) } finally { $fs.Close() }
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { Fail "git cat-file $Spec a echoue" }
}

function Get-BlobHash($File) {
    # --path applies the repo's line-ending rules, so LF and CRLF copies hash the same.
    $h = git -C $Root hash-object --path=$RepoPath $File
    if ($LASTEXITCODE -ne 0) { Fail "git hash-object a echoue" }
    return $h.Trim()
}

function Wait-Api($Seconds) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        try { return Invoke-RestMethod "$Api/status" -TimeoutSec 2 } catch { Start-Sleep -Seconds 1 }
    }
    return $null
}

# ssh/scp ship with Windows but are not always on PATH.
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    $env:Path += ";C:\Windows\System32\OpenSSH"
    if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { Fail "ssh introuvable (client OpenSSH Windows)" }
}

# ---- 1. What we deploy: HEAD, committed ----
Step "Version locale"
$dirty = git -C $Root status --porcelain -- $RepoPath
if ($dirty) { Fail "$RepoPath a des modifications non commitees. Commite d'abord (seul HEAD est deploye)." }
$headCommit = (git -C $Root log -1 --format="%h %s" -- $RepoPath).Trim()
$headHash = (git -C $Root rev-parse "HEAD:$RepoPath").Trim()
Write-Host "    HEAD: $headCommit"

# ---- 2. Jetson reachable, no scan running ----
Step "Etat du Jetson ($JetsonHost)"
try {
    $scan = Invoke-RestMethod "$Api/scan/status" -TimeoutSec 4
    if ($scan.running) { Fail "un scan tourne ($($scan.scan)). Arrete-le avant de deployer." }
    Write-Host "    API joignable, aucun scan en cours"
} catch {
    Write-Host "    API injoignable (service arrete ?) - on continue, ssh dira si le Jetson repond" -ForegroundColor Yellow
}

# ---- 3. Which version is running? ----
Step "Version sur le Jetson"
Invoke-Native "scp" @("-q", "${Target}:$RemoteDir/app.py", $JetsonCopy)
$remoteHash = Get-BlobHash $JetsonCopy
if ($remoteHash -eq $headHash) {
    Remove-Item $JetsonCopy
    Write-Host "    Deja a jour ($headCommit). Rien a faire." -ForegroundColor Green
    exit 0
}
$known = $null
foreach ($c in (git -C $Root log --format=%H -- $RepoPath)) {
    $h = git -C $Root rev-parse "${c}:$RepoPath"
    if ($h -and $h.Trim() -eq $remoteHash) { $known = $c; break }
}
if (-not $known) {
    Fail ("la version du Jetson n'existe dans aucun commit : elle a ete modifiee sur place.`n" +
          "       Copie gardee dans UnderScannerAPI\app.jetson.py - fusionne-la, commite, puis relance.")
}
Remove-Item $JetsonCopy
Write-Host "    Jetson: $((git -C $Root log -1 --format='%h %s' $known).Trim())"
Write-Host "    -> passage a: $headCommit"

if ($DryRun) { Write-Host "DryRun: aucune modification." -ForegroundColor Yellow; exit 0 }

# ---- 4. Upload next to the old file, check, swap, restart ----
Step "Envoi"
$tmp = Join-Path $env:TEMP "underscanner-app.py"
Export-GitBlob "HEAD:$RepoPath" $tmp
Invoke-Native "scp" @("-q", $tmp, "${Target}:$RemoteDir/app.py.new")
Remove-Item $tmp

Step "Verification syntaxe, remplacement, redemarrage (mot de passe sudo)"
# Syntax-check a copy in /tmp (its .pyc lands there, not in the service dir); the swap only
# happens if it passes. No quotes in this command on purpose: PowerShell 5.1 mangles
# embedded double quotes when passing arguments to native programs.
$remote = "cd $RemoteDir && mkdir -p /tmp/usc-check && cp app.py.new /tmp/usc-check/app.py && " +
          "python3 -m py_compile /tmp/usc-check/app.py && " +
          "cp app.py app.py.bak && mv app.py.new app.py && " +
          "sudo systemctl restart underscanner"
Invoke-Native "ssh" @("-t", $Target, $remote)

# ---- 5. Did it come back? ----
Step "Attente du service"
$st = Wait-Api 30
if ($st) {
    Write-Host "    OK: $($st.status), heure Jetson $($st.time)" -ForegroundColor Green
    exit 0
}

Write-Host "Le service ne repond pas : derniers logs, puis retour a la version precedente." -ForegroundColor Red
& ssh $Target "journalctl -u underscanner -n 40 --no-pager"
Invoke-Native "ssh" @("-t", $Target, "cd $RemoteDir && cp app.py.bak app.py && sudo systemctl restart underscanner")
if (Wait-Api 30) { Fail "nouvelle version KO, ancienne version restauree et relancee." }
Fail "nouvelle version KO et l'ancienne ne repond pas non plus : voir journalctl sur le Jetson."
