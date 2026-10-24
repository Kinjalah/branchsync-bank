# Windows run guide

## 1. Stop the old application

In the older project's three Java node windows and its web gateway window, press Ctrl+C. Do not stop Jenkins.

This upgraded application uses:

| Component | Port |
|---|---:|
| Jenkins (existing application) | 8080 — unchanged |
| New BranchSync Bank portal | 8090 |
| Node registries | 5001, 5002, 5003 |
| Node remote objects | 5101, 5102, 5103 |

An old banking node on these ports will prevent startup. Stop only the old BranchSync processes in their own terminals; do not kill all Java processes.

## 2. Extract the new project

Extract the ZIP and keep the new `BranchSyncBank` folder separate from your original `BranchSync` folder.

Suggested location:

```text
C:\Users\HP\Desktop\DCS Project\BranchSyncBank
```

Open it in VS Code. Its root must contain `start.ps1`, `build.ps1`, `run.ps1`, `src`, `web` and `docs`.

Your original application's saved data is not automatically migrated. This package starts a new fictional banking dataset with a richer identity/account model. Keep old files as your backup.

## 3. Select Java 17

In the PowerShell terminal:

```powershell
cd 'C:\Users\HP\Desktop\DCS Project\BranchSyncBank'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
& "$env:JAVA_HOME\bin\javac.exe" -version
```

Expected: javac 17.0.12 or another JDK version at least 17. You can also use your complete Adoptium JDK 21 installation by setting JAVA_HOME to its actual installed directory.

This avoids your system's Java 8 runtime. Source compilation explicitly uses UTF-8, so rupee symbols and interface messages compile correctly on Windows.

## 4. Build and run

```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

Expected messages include:

```text
Build successful. No Maven, npm or database setup needed.
Starting the gateway and its three child banking nodes.
BranchSync Bank is starting: http://localhost:8090
```

Initial seed generation hashes four demo passwords and may take a few seconds. Allow roughly 10 seconds for node startup, election and recovery.

Open **http://localhost:8090** in Chrome or Edge.

A single console owns all three child Java node processes. Individual node logs are written to `runtime\logs\node1.log`, `node2.log` and `node3.log`.

### Alternative launch

Double-click `Start-Bank.cmd`. It starts the same PowerShell workflow. If it cannot find JDK 17+, use the explicit JAVA_HOME terminal method above.

### Re-run without rebuilding

After a successful build:

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

## 5. Sign in

| Account | Username | Password | Workspace |
|---|---|---|---|
| Kinjal | kinjal | Kinjal@12345 | Customer |
| Aarav | aarav | Aarav@123456 | Customer |
| Teller | teller | Teller@12345 | Staff |
| Administrator | admin | Admin@123456 | Staff + DCS controls |

The demo-account panel fills credentials; you still click Sign in. The server determines the actual role, independent of which sign-in tab was selected.

For simultaneous roles use different browser profiles, or one normal window and a separate Incognito/InPrivate session. Tabs in the same browser profile share the session cookie, so signing in as staff in one tab also changes the session used by other tabs.

New registration creates a zero-balance account and pending KYC. Sign in as the teller in another browser session, open Customers & KYC, inspect the new profile and verify its demo KYC.

## 6. Understand the data

Fresh Kinjal accounts:

| Account | Type | Opening balance |
|---|---|---:|
| 10010001 | Everyday Savings | ₹1,25,000 |
| 10010002 | Everyday Current | ₹28,500 |

Fresh Aarav account: `10020001`, opening balance ₹86,400. Aarav is already saved as Kinjal's beneficiary.

All balances and history persist. Repeat launches do not reset them. DCS sequence numbers count every successful state change, including card controls and approvals; monetary transaction history can have gaps between those sequence numbers.

## 7. Operate the cluster from the interface

Sign in as admin → DCS operations.

- Confirm all three nodes are ready and show equal state sequences/fingerprints.
- Node 3 should normally be leader.
- Use Stop process on Node 3 after pending transactions have finished.
- Wait for Node 2 to become leader.
- Perform a customer transaction through Branch 1 or Branch 2 in another browser profile.
- Return to the admin console and start Node 3.
- Observe recovery, matching fingerprints and Node 3 regaining leadership after synchronization.

The console starts/stops real gateway-owned Java processes. It does not merely change an online/offline label. The last node cannot be stopped through the interface, so an administrator can still authenticate and restore the others.

A selected offline entry branch returns an availability error. Customers can select another branch from the top bar. Branch selection is explicit; the interface does not claim automatic gateway failover.

## 8. Stop safely

Finish transactions and allow replicas to agree. Press Ctrl+C in the launcher console. The JVM shutdown hook stops the gateway's child node processes. Jenkins is unaffected.

Do not start a second copy while the first is running. Do not force-kill the gateway during a transaction for the supported demo.

If the host forcibly kills the gateway and leaves orphan node processes, identify the actual node processes before stopping them. Normal Ctrl+C is the supported shutdown procedure.

## 9. Change the portal port if needed

The default is already 8090. To choose another free local port before launch:

```powershell
$env:BRANCHSYNC_PORT = '8095'
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

Open http://localhost:8095. Allowed browser origins are derived from the configured port; no source replacement is needed. Jenkins remains unaffected.

To return to default in that terminal:

```powershell
Remove-Item Env:BRANCHSYNC_PORT -ErrorAction SilentlyContinue
```

## 10. Reset fictional data with a backup

Stop the application first. Rename the whole runtime folder:

```powershell
if (Test-Path .\runtime) {
    Rename-Item .\runtime ("runtime-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

A fresh cluster key, seeded users and snapshots will be created. Preserve the backup if you need earlier evidence. Do not mix snapshots or keys from different runtime directories.

## 11. Automated tests

Stop the running application before tests. Python is optional for normal app usage.

```powershell
py -3 tests\integration.py
```

If your command is `python`, use `python tests\integration.py`. Tests reserve banking-node ports and HTTP 18090, so they do not conflict with Jenkins. They use isolated temporary files and the same Java implementation. Tests start/stop only their own gateway and its children.

Node.js is optional for additional all-screen template checks:

```powershell
node tests\templates.cjs
```

## Troubleshooting

| Symptom | Resolution |
|---|---|
| Java class-version error | Set JAVA_HOME to JDK 17+; use supplied scripts |
| javac not found | Install/use a complete JDK, not only a JRE |
| Port 500x/510x already in use | Stop the old BranchSync node in its terminal |
| Port 8090 already in use | Stop the old gateway or configure BRANCHSYNC_PORT |
| Portal displays a startup error | Wait for seed generation and node election, then retry sign-in |
| Password rejected after editing it | Use the new password; seed passwords do not overwrite saved changes |
| Too many login attempts | Wait one minute and retry with correct credentials |
| Customer account cannot transact | Check KYC, account status, user access, available balance and daily limit |
| A cash request did not increase balance | Teller approval is required |
| Transfer destination not available | Save the other customer's internal account as a beneficiary |
| Node unavailable after failure | Select another live branch in the top bar |
| No synchronized follower | Start a second node from admin DCS operations and wait for catch-up |
| Different role appears in another tab | Tabs share cookies; use a separate browser profile for each role |
| Unknown/uncertain transaction outcome | Retain the request ID, inspect transaction/request history, then use same-ID retry if appropriate |
| VS Code red underlines | Open the project root and select JDK 17+ for the Java extension; the supplied compiler command is authoritative |
| Want to view a node's console | Open its actual runtime/logs/nodeN.log file; logs persist across restarts |

No Maven, npm, MySQL, Docker, external fonts or internet access is required to run the app.
