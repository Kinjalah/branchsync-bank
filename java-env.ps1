$ErrorActionPreference = 'Stop'
function Get-BankJava {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        return "$env:JAVA_HOME\bin\java.exe"
    }
    $Compiler = Get-Command javac -ErrorAction SilentlyContinue
    if ($Compiler) {
        $Sibling = Join-Path (Split-Path $Compiler.Source) 'java.exe'
        if (Test-Path $Sibling) { return $Sibling }
    }
    if (Test-Path 'C:\Program Files\Java\jdk-17\bin\javac.exe') {
        return 'C:\Program Files\Java\jdk-17\bin\java.exe'
    }
    throw 'JDK 17 or later is required. Set JAVA_HOME to your installed JDK folder.'
}
