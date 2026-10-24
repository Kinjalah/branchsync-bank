$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& "$PSScriptRoot\build.ps1"
& "$PSScriptRoot\run.ps1"
