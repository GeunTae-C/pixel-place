param([Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
$source=Join-Path $PSScriptRoot 'mysql-test-instance.ps1'
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'MySQL script syntax'}
$function=$ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-FuturePhysicalBytes'},$true)
if($function.Count -ne 1){throw 'actual future function missing'}
. ([scriptblock]::Create($function[0].Extent.Text))
function Get-Bytes([string]$Path){if($Path -eq $large){return $script:workUsed};return $script:walUsed}
$large='F:/pixel-place-work/sessions/test';$RunId='cap1-storage-test';$workUsed=0L;$walUsed=0L
$MySqlGrowthBytes=250GB;$EvidenceBytes=200GB;$physicalReservation=$null
if((Get-FuturePhysicalBytes 0 0) -ne 455GB+64MB){throw 'legacy reservation changed'}
$physicalReservation=[pscustomobject]@{MysqlUsageBytes=45GB;EvidenceUsageBytes=30GB;WorkspaceUsageBytes=6GB;WalUsageBytes=1GB;NextReservationBytes=5GB+64MB}
$future=Get-FuturePhysicalBytes 0 0
if($future -ne 87GB+64MB){throw 'CAP1 U instead of L'}
$free=$future+10GB
if($free-$future -ne 10GB -or $free-1-$future -ge 10GB){throw 'exact free boundary'}
$workUsed=1GB;$walUsed=512MB
if((Get-FuturePhysicalBytes 2GB 3GB) -ne $future-6GB-512MB){throw 'remaining additional physical usage'}
$workUsed=7GB;$walUsed=2GB
if((Get-FuturePhysicalBytes 46GB 31GB) -ne 5GB+64MB){throw 'no negative reservation or next reserve refund'}
[IO.File]::WriteAllText($Output,(@{servicesStarted=0;actualFunction='Get-FuturePhysicalBytes';cases=5;passed=$true;mysqlSourceHash=(Get-FileHash -LiteralPath $source).Hash}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
