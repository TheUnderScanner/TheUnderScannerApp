# Deploy the backend files (the versions COMMITTED at HEAD) to the Jetson.
#
#   .\UnderScannerAPI\deploy-backend.ps1               # normal deploy
#   .\UnderScannerAPI\deploy-backend.ps1 -DryRun       # checks only, change nothing
#   .\UnderScannerAPI\deploy-backend.ps1 -JetsonHost 10.42.0.1 -User orin4slam
#
# Files deployed: see $Files (app.py + the modules it imports).
#
# Safety model:
#   * Only committed code is deployed (the HEAD blobs, byte-exact, LF endings), so every
#     version that ever runs on the Jetson exists in git history.
#   * Before overwriting, each file on the Jetson is hashed (git hash-object, run there) and
#     looked up in the history of that path. Found -> a known version, safe to replace.
#     Absent -> a new file, fine. Not found -> someone edited it on the Jetson: abort, and
#     keep it as UnderScannerAPI\<name>.jetson.py so it can be merged first.
#   * Refuses while a scan is running (the restart would kill it).
#   * The new files are syntax-checked on the Jetson BEFORE they replace the old ones; the
#     old ones are kept as <file>.bak. If the service doesn't come back, it rolls back
#     (restores the .bak files, removes files that were new).
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
$Files = @("app.py", "slamcfg.py")       # under UnderScannerAPI/ locally, underscanner-api/ remotely
$RemoteDir = "underscanner-api"          # relative to home: the Jetson's SFTP rejects absolute paths
$Target = "$User@$JetsonHost"
$Api = "http://${JetsonHost}:8000"

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
$head = @{}
foreach ($f in $Files) {
    $rp = "UnderScannerAPI/$f"
    $dirty = git -C $Root status --porcelain -- $rp
    if ($dirty) { Fail "$rp a des modifications non commitees (ou n'est pas suivi). Commite d'abord (seul HEAD est deploye)." }
    $h = git -C $Root rev-parse "HEAD:$rp" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $h) { Fail "$rp n'existe pas dans HEAD." }
    $head[$f] = $h.Trim()
    Write-Host ("    {0,-12} {1}" -f $f, (git -C $Root log -1 --format="%h %s" -- $rp).Trim())
}

# ---- 2. Jetson reachable, no scan running ----
Step "Etat du Jetson ($JetsonHost)"
try {
    $scan = Invoke-RestMethod "$Api/scan/status" -TimeoutSec 4
    if ($scan.running -or $scan.starting) { Fail "un scan tourne ($($scan.scan)). Arrete-le avant de deployer." }
    Write-Host "    API joignable, aucun scan en cours"
} catch {
    Write-Host "    API injoignable (service arrete ?) - on continue, ssh dira si le Jetson repond" -ForegroundColor Yellow
}

# ---- 3. Which versions are running? (one ssh: git hash-object on the Jetson) ----
Step "Versions sur le Jetson"
# No double quotes in remote commands: PowerShell 5.1 mangles them when calling native programs.
$probe = "cd $RemoteDir && for f in $($Files -join ' '); do if [ -f `$f ]; then git hash-object `$f; else echo MISSING; fi; done"
$out = & ssh $Target $probe
if ($LASTEXITCODE -ne 0) { Fail "ssh a echoue (code $LASTEXITCODE)" }
$remote = @($out | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($remote.Count -ne $Files.Count) { Fail "reponse inattendue du Jetson (git installe ?) : $out" }

$changed = @()
$unknown = @()
for ($i = 0; $i -lt $Files.Count; $i++) {
    $f = $Files[$i]; $r = $remote[$i]; $rp = "UnderScannerAPI/$f"
    if ($r -eq $head[$f]) { Write-Host ("    {0,-12} deja a jour" -f $f); continue }
    $changed += $f
    if ($r -eq "MISSING") { Write-Host ("    {0,-12} absent -> nouveau fichier" -f $f); continue }
    if ($r -notmatch '^[0-9a-f]{40}$') { Fail "hash illisible pour $f : $r" }
    $known = $null
    foreach ($c in (git -C $Root log --format=%H -- $rp)) {
        $h = git -C $Root rev-parse "${c}:$rp" 2>$null
        if ($h -and $h.Trim() -eq $r) { $known = $c; break }
    }
    if ($known) {
        Write-Host ("    {0,-12} {1} -> HEAD" -f $f, (git -C $Root log -1 --format='%h %s' $known).Trim())
    } else {
        $unknown += $f
    }
}

if ($unknown.Count -gt 0) {
    foreach ($f in $unknown) {
        $copy = Join-Path $PSScriptRoot ([IO.Path]::GetFileNameWithoutExtension($f) + ".jetson.py")
        & scp -q "${Target}:$RemoteDir/$f" $copy
    }
    Fail ("version(s) du Jetson absente(s) de tout commit : $($unknown -join ', ') (modifiee(s) sur place).`n" +
          "       Copie(s) gardee(s) dans UnderScannerAPI\*.jetson.py - fusionne, commite, puis relance.")
}
if ($changed.Count -eq 0) { Write-Host "    Tout est a jour. Rien a faire." -ForegroundColor Green; exit 0 }

if ($DryRun) { Write-Host "DryRun: aucune modification (a deployer : $($changed -join ', '))." -ForegroundColor Yellow; exit 0 }

# ---- 4. Upload next to the old files (one scp), check, swap, restart ----
Step "Envoi ($($changed -join ', '))"
$stage = Join-Path $env:TEMP "underscanner-deploy"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory $stage | Out-Null
$local = @()
foreach ($f in $changed) {
    $dst = Join-Path $stage "$f.new"
    Export-GitBlob "HEAD:UnderScannerAPI/$f" $dst
    $local += $dst
}
Invoke-Native "scp" (@("-q") + $local + @("${Target}:$RemoteDir/"))
Remove-Item -Recurse -Force $stage

Step "Verification syntaxe, remplacement, redemarrage (mot de passe sudo)"
# Syntax-check copies in /tmp (their .pyc land there, not in the service dir); the swap only
# happens if every file passes. A file that is new gets no .bak, so the rollback removes it.
$check = "rm -rf /tmp/usc-check && mkdir -p /tmp/usc-check"
$swap = ""
foreach ($f in $changed) {
    $check += " && cp $f.new /tmp/usc-check/$f"
    $swap += " && if [ -f $f ]; then cp $f $f.bak; else rm -f $f.bak; fi && mv $f.new $f"
}
$check += " && python3 -m py_compile " + (($changed | ForEach-Object { "/tmp/usc-check/$_" }) -join " ")
$cmd = "cd $RemoteDir && $check$swap && sudo systemctl restart underscanner"
Invoke-Native "ssh" @("-t", $Target, $cmd)

# ---- 5. Did it come back? ----
Step "Attente du service"
$st = Wait-Api 30
if ($st) {
    Write-Host "    OK: $($st.status) v$($st.version), heure Jetson $($st.time)" -ForegroundColor Green
    exit 0
}

Write-Host "Le service ne repond pas : derniers logs, puis retour a la version precedente." -ForegroundColor Red
& ssh $Target "journalctl -u underscanner -n 40 --no-pager"
$restore = ($changed | ForEach-Object { "if [ -f $_.bak ]; then cp $_.bak $_; else rm -f $_; fi" }) -join " && "
Invoke-Native "ssh" @("-t", $Target, "cd $RemoteDir && $restore && sudo systemctl restart underscanner")
if (Wait-Api 30) { Fail "nouvelle version KO, ancienne version restauree et relancee." }
Fail "nouvelle version KO et l'ancienne ne repond pas non plus : voir journalctl sur le Jetson."
