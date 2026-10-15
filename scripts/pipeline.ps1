# Chay lai duong ong xu ly tu du lieu da crawl den so lieu bao cao.
# Buoc nao da co dau ra thi bo qua (-Force de chay lai tat ca). Crawl va snapshot (tai mang) khong nam o day:
#   scripts\crawl.bat update      scripts\extract.bat snapshot
# Usage: powershell -ExecutionPolicy Bypass -File scripts\pipeline.ps1 [-Force] [-From <so buoc>]
param([switch]$Force, [int]$From = 1)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
New-Item -ItemType Directory -Force logs | Out-Null
$log = Join-Path $root 'logs\pipeline.log'
Add-Content $log ("`r`n=== " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + " pipeline Force=$Force From=$From")

# so, ten, dau ra (bo qua neu da co), dieu kien dau vao, lenh
$steps = @(
  @{ n = 1; name = 'Bo du lieu ver1';                out = 'dataset\ver1\documents.jsonl';                    need = 'output\urls_valid.txt';            cmd = 'scripts\search.bat dataset' },
  @{ n = 2; name = 'Chi muc Lucene ver1';            out = 'index\ver1\segments_1';                           need = 'dataset\ver1\documents.jsonl';     cmd = 'scripts\search.bat index' },
  @{ n = 3; name = 'Vec-to day (e5-small)';          out = 'index\ver1\dense_e5small_int8_256.bin';           need = 'models\multilingual-e5-small';     cmd = 'scripts\eval.bat embed' },
  @{ n = 4; name = 'Boc tach khoi + metadata + lien ket'; out = 'data\extract\v1\pages.jsonl';                need = 'data\raw';                         cmd = 'scripts\extract.bat run --out data\extract\v1 --threads 8' },
  @{ n = 5; name = 'Do thi lien ket + PageRank';     out = 'data\extract\v1\pages.final.jsonl';               need = 'data\extract\v1\pages.jsonl';      cmd = 'scripts\extract.bat graph --out data\extract\v1' },
  @{ n = 6; name = 'Chi muc thu nghiem ver1x';       out = 'index\ver1x\segments_1';                          need = 'data\extract\v1\pages.final.jsonl'; cmd = 'scripts\extract.bat index --out data\extract\v1 --index index\ver1x' },
  @{ n = 7; name = 'Dai khoi tap vang (cho slide)';  out = 'eval\extract\strips_test.json';                   need = 'data\raw';                         cmd = 'scripts\extract.bat strips' },
  @{ n = 8; name = 'So lieu bao cao truc quan';      out = $null;                                             need = 'eval\runs';                        cmd = 'scripts\report.bat --no-open' }
)

$total = [Diagnostics.Stopwatch]::StartNew()
foreach ($s in $steps) {
  if ($s.n -lt $From) { continue }
  $label = "[{0}/{1}] {2}" -f $s.n, $steps.Count, $s.name
  if (-not $Force -and $s.out -and (Test-Path $s.out)) { Write-Host "$label : da co $($s.out), bo qua" -ForegroundColor DarkGray; continue }
  if ($s.need -and -not (Test-Path $s.need)) { Write-Host "$label : thieu $($s.need), bo qua" -ForegroundColor Yellow; Add-Content $log "$label SKIP thieu $($s.need)"; continue }
  Write-Host "$label : $($s.cmd)" -ForegroundColor Cyan
  $sw = [Diagnostics.Stopwatch]::StartNew()
  cmd /c $s.cmd
  $code = $LASTEXITCODE
  $sw.Stop()
  $t = '{0:N1} s' -f $sw.Elapsed.TotalSeconds
  Add-Content $log "$label exit=$code $t"
  if ($code -ne 0) { Write-Host "$label : LOI (exit $code) sau $t. Xem log o tren; chay tiep tu buoc nay: -From $($s.n)" -ForegroundColor Red; exit $code }
  Write-Host "$label : xong ($t)" -ForegroundColor Green
}
$total.Stop()
Write-Host ("Hoan tat sau {0:N1} s. Nhat ky: logs\pipeline.log" -f $total.Elapsed.TotalSeconds) -ForegroundColor Green
