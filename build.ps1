$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\java-env.ps1"
$JavaRunner = Get-BankJava
$Compiler = Join-Path (Split-Path $JavaRunner) 'javac.exe'
Write-Host 'Building BranchSync Bank with:'
& $Compiler -version
New-Item -ItemType Directory -Force out | Out-Null
$Sources = Get-ChildItem src -Filter *.java -Recurse | ForEach-Object { $_.FullName }
& $Compiler -encoding UTF-8 --release 17 --add-modules jdk.httpserver -d out $Sources
if ($LASTEXITCODE -ne 0) { throw 'Build failed. Use a complete JDK 17 or later.' }
Write-Host 'Build successful. No Maven, npm or database setup needed.'
