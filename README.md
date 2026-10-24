# BranchSync Bank

**A complete banking application prototype with customer, teller and administrator workspaces, backed by three real Java RMI server processes.**

The interface and services have been rebuilt around normal banking tasks. DCS concepts are integrated into the actual implementation: account and authorization changes are ordered by a leader, replicated to independent nodes, traced with Lamport clocks and recovered after controlled process failures.

## Start on Windows

1. Stop the older BranchSync gateway and banking nodes. Keep Jenkins running on 8080.
2. Extract this package into a **new folder** and open `BranchSyncBank` in VS Code.
3. In its PowerShell terminal:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

4. Wait about 10 seconds and open **http://localhost:8090**.

Or double-click `Start-Bank.cmd`. The launcher uses JAVA_HOME, an available javac runtime, or the known JDK 17 installation path. Only JDK 17+ is supported.

**One terminal now starts the gateway and all three child node processes.** Use the administrator's DCS console to stop/restart individual nodes. Ctrl+C in the launcher stops the gateway and the nodes it owns.

## Included demo sign-ins

| Role | Username | Password |
|---|---|---|
| Customer | kinjal | Kinjal@12345 |
| Second customer | aarav | Aarav@123456 |
| Teller | teller | Teller@12345 |
| Administrator | admin | Admin@123456 |

These are intentional demo credentials. Every password is stored as a separate salted PBKDF2-HMAC-SHA256 hash (600,000 iterations). A new customer can register from the sign-in page and requires a teller KYC decision before transacting.

## Functional services

| Customer workspace | Teller / administrator workspace |
|---|---|
| Personalized account overview and actual cash-flow chart | Branch overview and outstanding request queue |
| Own-account and saved-beneficiary transfers | Customer profiles and manual demo KYC decisions |
| Add/remove internal beneficiaries | Cash-deposit and cash-withdrawal approvals |
| Electricity, broadband, mobile and water demo bill payments | Loan approval/rejection and principal disbursal |
| Printable transaction receipts | Cheque-book, card-replacement and support decisions |
| Account statements exported as date-filtered CSV | Account freeze/unfreeze |
| Demo card freeze/unfreeze and daily debit limits | Attributed audit trail and transaction inspection |
| Open fixed deposits; early or matured redemption | Admin-only customer suspension/restoration |
| Loan requests, tracking, partial/full principal repayments | Admin-only real process controls and DCS evidence |
| Cash/service requests and cancellation while pending | Replica sequence, fingerprint, election and recovery views |
| Profile updates, password change and sign out | Same server-side session and role enforcement |

## Architecture

```text
src/branchsync/
  Setup.java                  Seed fictional users, accounts and local cluster key
  domain/Models.java          Domain records and replicated state
  application/BankingService.java
                              Business rules, ownership checks and authorization
  cluster/BankRemote.java     Remote service contract
  cluster/BankNode.java       RMI nodes, leader ordering, clocks and replication
  cluster/LoopbackSockets.java
                              Internal RMI restricted to loopback
  cluster/ProcessController.java
                              Gateway-owned node process lifecycle
  infrastructure/Config.java
  infrastructure/SnapshotStore.java
                              Configuration and atomic snapshot replacement
  security/Passwords.java     PBKDF2 password handling
  security/Sessions.java      Server-side sessions and login throttling
  web/ApiServer.java          HTTP routes, CSRF, session checks and exports
  web/Views.java              Explicit response projection; excludes credentials
  common/Json.java            JSON and canonical state fingerprint support
web/
  index.html
  assets/styles.css
  assets/mark.svg
  assets/core.js              API, shared formatting, authentication templates
  assets/shell.js             Role-specific navigation and application shell
  assets/pages/customer.js    Customer banking screens
  assets/pages/staff.js       Staff and DCS operations screens
  assets/controllers.js       Forms, confirmations, receipts and interactions
```

There are no third-party Java or frontend dependencies. No Maven/npm installation or database server is required. All assets are local and work offline.

## Read these guides

- `docs/RUN_WINDOWS.md` — exact setup, upgrade, ports and troubleshooting.
- `docs/DEMO_GUIDE.md` — complete multi-role demonstration and expected changes.
- `docs/ARCHITECTURE.md` — request flow, storage, consistency, security and boundaries.
- `docs/API.md` — endpoints, command fields and role enforcement.
- `docs/TEST_RESULTS.txt` — real-process integration test results.
- `docs/TEMPLATE_RESULTS.txt` — all-screen runtime rendering checks.

## Linux / WSL

```bash
bash build.sh
bash run.sh
```

Use a Linux JDK 17+ in WSL. Open http://localhost:8090 after startup.

## Tests

Stop your running BranchSync instance first. Jenkins may remain on 8080.

```powershell
py -3 tests\integration.py
```

or `python3 tests/integration.py`. Python 3.9+ is only needed for tests. The suite uses port 18090, three real Java nodes, temporary storage and temporary fictional customer data. It never resets your runtime data or kills unrelated processes.

If Node.js is available, template checks can also be run after integration tests:

```text
node tests/templates.cjs
```

## Persistence and upgrade

Runtime data lives under `runtime/`: a cluster key, seed snapshot, per-node snapshots and node logs. This folder is created on first startup and is excluded from this ZIP. Do not copy the previous project's `data/` into it: the enhanced account and identity model has a new storage format. The old application and its files can be retained separately as a backup.

Do not edit or remove live runtime snapshots. To reset demo accounts, stop the application and rename the entire runtime directory before restarting; see the run guide.

## Honest scope

This is a substantial **local educational banking prototype**, not a licensed bank or production banking core. Bill settlements and cards are simulated; no external payment rails, identity-verification provider or physical delivery service is connected. Loans track principal, not a full interest/EMI engine. Data is persisted in replicated snapshots, not an external SQL database.

The DCS failure demo is fixed-membership localhost crash/restart after completed replication. Bully-style election and replica acknowledgments are not Raft/Paxos consensus and do not guarantee safety under network partitions or mid-transaction failures. The gateway remains a single process. Sessions use HttpOnly/SameSite cookies on local HTTP; a real deployment needs HTTPS and additional operational/security engineering.
