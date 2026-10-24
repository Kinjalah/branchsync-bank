$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\java-env.ps1"
if (!(Test-Path .\out\branchsync\web\ApiServer.class)) { throw 'Build first: powershell -ExecutionPolicy Bypass -File .\build.ps1' }
$JavaRunner = Get-BankJava
Write-Host 'Starting the gateway and its three child banking nodes.'
Write-Host 'Node processes can be controlled from the administrator DCS dashboard.'
Write-Host 'Ctrl+C stops this gateway and the nodes it started. Jenkins is unaffected.'
& $JavaRunner --add-modules jdk.httpserver -cp out branchsync.web.ApiServer
if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error. Read the message above; check for an older BranchSync instance using the same ports.' }
