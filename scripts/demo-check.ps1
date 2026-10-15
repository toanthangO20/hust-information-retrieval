# Kiem tra moi thu can cho buoi bao cao (chay 5 phut truoc): Java, jar, chi muc, mo hinh, so lieu slide, MongoDB, web.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\demo-check.ps1
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$fail = 0
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "  [XX] $m" -ForegroundColor Red; $script:fail++ }
function Port($p) {
  $c = New-Object Net.Sockets.TcpClient
  try { $a = $c.BeginConnect('127.0.0.1', $p, $null, $null); $ok = $a.AsyncWaitHandle.WaitOne(400) -and $c.Connected } catch { $ok = $false }
  $c.Close(); return $ok
}
function Newest($path, $filter) {
  if (-not (Test-Path $path)) { return $null }
  Get-ChildItem $path -Recurse -File -Filter $filter | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

Write-Host "Java va ban build"
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path "$javaHome\bin\java.exe")) { $javaHome = 'C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot' }
if (Test-Path "$javaHome\bin\java.exe") { Ok "Java: $javaHome" } else { Bad 'Khong tim thay Java 11+ (dat JAVA_HOME)' }
$jar = Get-Item 'target\hust-crawler-1.0.jar' -ErrorAction SilentlyContinue
if (-not $jar) { Bad 'Chua build: tools\apache-maven-3.9.9\bin\mvn package' }
else {
  $src = Newest 'src\main' '*.*'
  if ($src -and $src.LastWriteTime -gt $jar.LastWriteTime) { Warn "jar cu hon ma nguon ($($src.Name) sua sau khi build) -> build lai: mvn package -DskipTests" }
  else { Ok "jar moi nhat ($($jar.LastWriteTime.ToString('dd/MM HH:mm')))" }
}

Write-Host "Du lieu va mo hinh"
foreach ($p in @('output\urls_valid.txt', 'dataset\ver1\manifest.json', 'index\ver1', 'index\ver1x', 'eval\qrels\test.tsv')) {
  if (Test-Path $p) { Ok $p } else { Bad "thieu $p" }
}
foreach ($p in @('index\ver1\dense_e5small_int8_256.bin', 'models\multilingual-e5-small\model_int8.onnx', 'data\extract\v1\pages.final.jsonl')) {
  if (Test-Path $p) { Ok $p } else { Warn "thieu $p (dense/hybrid hoac do thi se khong chay truc tiep duoc)" }
}

Write-Host "Slide bao cao"
$res = Get-Item 'docs\data\results.js' -ErrorAction SilentlyContinue
if (-not $res) { Bad 'thieu docs\data\results.js -> chay scripts\report.bat' }
else {
  $run = @('eval\runs', 'eval\qrels', 'eval\extract', 'eval\report') | ForEach-Object { Newest $_ '*.*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($run -and $run.LastWriteTime -gt $res.LastWriteTime) { Warn "so lieu slide cu hon $($run.FullName.Substring($root.Length + 1)) -> chay scripts\report.bat" }
  else { Ok "docs\data\results.js cap nhat ($($res.LastWriteTime.ToString('dd/MM HH:mm')))" }
}

Write-Host "Dich vu"
if (Port 27017) { Ok 'MongoDB dang chay (127.0.0.1:27017)' } else { Warn 'MongoDB chua chay -> mo terminal khac: scripts\mongo.bat (chi can cho phan demo MongoDB)' }
if (Port 8080) {
  try {
    $t = Measure-Command { $r = Invoke-RestMethod 'http://127.0.0.1:8080/api/search?q=hoc%20bong&size=3' -TimeoutSec 10 }
    Ok ("Web dang chay: 'hoc bong' -> {0} ket qua, {1:N0} ms" -f $r.total, $t.TotalMilliseconds)
  } catch { Bad "Cong 8080 mo nhung /api/search loi: $($_.Exception.Message)" }
  try {
    $t = Measure-Command { $c = Invoke-RestMethod 'http://127.0.0.1:8080/api/compare-info' -TimeoutSec 60 }
    $off = @($c.systems | Where-Object { $_.unavailable })
    if ($off.Count -eq 0) { Ok ("So sanh mo hinh san sang: {0} mo hinh, {1} truy van co nhan" -f $c.systems.Count, $c.topics.Count) }
    else { Warn ("So sanh mo hinh: thieu " + (($off | ForEach-Object { $_.id }) -join ', ')) }
  } catch { Warn "/api/compare-info loi: $($_.Exception.Message)" }
} else { Warn 'Web chua chay -> mo terminal khac: scripts\search.bat serve' }

Write-Host ''
if ($fail -eq 0) { Write-Host 'San sang. Mo slide: start docs\BAO_CAO_TRUC_QUAN.html' -ForegroundColor Green } else { Write-Host "$fail loi can xu ly truoc khi bao cao." -ForegroundColor Red }
exit $fail
