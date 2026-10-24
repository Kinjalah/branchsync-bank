# Validation record

The supplied reports were generated against the actual source in this package using OpenJDK 17.0.20 on Linux.

- `TEST_RESULTS.txt`: 72 integration checks through HTTP, server-side sessions and three real RMI JVM nodes. Includes banking services, role/ownership checks, CSRF, throttling, concurrent conflicting debits, real leader termination, recovery and full restart persistence.
- `TEMPLATE_RESULTS.txt`: 21 runtime template checks covering sign-in, registration, every customer/staff/admin screen, receipt rendering, role navigation and escaping of customer-controlled text. These checks execute the supplied JavaScript functions with safe fixtures produced by integration tests.
- `DOMAIN_RESULTS.txt`: 6 domain checks, including matured-deposit interest, early closure, role enforcement and amount precision.
- All Java sources compile using Java 17 language/API compatibility and explicit UTF-8.
- Every frontend JavaScript file passes the Node.js syntax checker.

A browser automation surface was unavailable in this environment. Template tests do **not** constitute browser visual verification or end-to-end button-interaction testing. Responsive layouts and interactions were implemented and statically reviewed, but should be checked in Chrome/Edge on the user's Windows machine.

The Windows PowerShell/cmd launchers were written/reviewed but could not be executed on this Linux environment. The same Java entrypoint and child-process implementation were exercised by integration tests; this does not assert Windows-specific process behavior was tested here.

## Reproduce

From a built project, after stopping the interactive app:

```text
python3 tests/integration.py
node tests/templates.cjs
```

On Windows `py -3 tests\integration.py` is equivalent. Node.js is only needed for the template test, not for the application.

For the domain checks on Windows, with JAVA_HOME set:

```powershell
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 --release 17 -cp out -d out tests\DomainChecks.java
& "$env:JAVA_HOME\bin\java.exe" -cp out branchsync.tests.DomainChecks
```

On Linux:

```bash
javac -encoding UTF-8 --release 17 -cp out -d out tests/DomainChecks.java
java -cp out branchsync.tests.DomainChecks
```

The integration suite uses isolated temporary runtime data. It refuses to start if node ports are busy and never resets the interactive app's data. Jenkins on 8080 is unaffected.
