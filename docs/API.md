# HTTP API

Base URL: http://localhost:8090 by default. All mutation bodies use `application/x-www-form-urlencoded`. Money input is decimal rupees; JSON monetary fields are integer paise.

## Sessions

| Method | Endpoint | Access / behavior |
|---|---|---|
| POST | /api/login | username, password; creates an HttpOnly session cookie and returns user + csrf |
| POST | /api/register | username, password, name, email, phone, id, branch; creates pending-KYC CUSTOMER |
| GET | /api/session | Authenticated user projection and CSRF token |
| POST | /api/logout | Requires session and X-CSRF-Token; removes session |
| GET | /api/app | Role-filtered accounts, users, transactions, beneficiaries, deposits, loans, requests and audit |

The browser supplies the returned csrf value as the `X-CSRF-Token` header on authenticated POST requests. Cookies are sent by the same-origin browser. Do not put passwords or session tokens into URLs.

The customer projection excludes other customers' account records, the user directory and staff audit. It includes only relevant financial records. Internal commands and password hashes are never projected.

## Banking commands

POST `/api/command`, authenticated with CSRF. Common fields:

- `id`: 8–80 letters/digits/hyphens, normally a UUID. Retain for uncertain retries.
- `branch`: 1, 2 or 3; selects the entry RMI node.
- `action`: one of the actions below.

Actor identity is taken from the server session. A supplied actor field is discarded. Permissions and ownership are checked again in the domain layer.

| Action | Role | Additional fields |
|---|---|---|
| BENEFICIARY_ADD | Customer | account, nickname |
| BENEFICIARY_DELETE | Customer | beneficiary |
| TRANSFER | Customer | account, destination, amount, description (may be empty) |
| BILL_PAY | Customer | account, biller, consumer, amount |
| CARD_TOGGLE | Customer | account |
| LIMIT_UPDATE | Customer | account, amount |
| FD_OPEN | Customer | account, amount, months (6/12/24) |
| FD_CLOSE | Customer | deposit |
| REQUEST_CREATE | Customer | account, type, details, amount for monetary requests |
| REQUEST_CANCEL | Customer | request |
| LOAN_REPAY | Customer | account, loan, amount |
| REQUEST_DECIDE | Teller/Admin | request, decision (APPROVE/REJECT), note |
| KYC_DECIDE | Teller/Admin | user, kyc (VERIFIED/REJECTED) |
| ACCOUNT_STATUS | Teller/Admin | account, status (ACTIVE/FROZEN) |
| USER_STATUS | Admin | user, status (ACTIVE/SUSPENDED); customers only, not self |
| PROFILE_UPDATE | Any signed-in role | name, email, phone |
| PASSWORD_CHANGE | Any signed-in role | currentPassword, newPassword |

Request types: CASH_DEPOSIT, CASH_WITHDRAWAL, LOAN, CHEQUE_BOOK, CARD_REPLACEMENT, SUPPORT. Supported billers: ELECTRICITY, BROADBAND, MOBILE, WATER.

Success response:

```json
{"ok":true,"message":"Transfer completed","sequence":1,"data":{"reference":"BS000001"}}
```

Command business/authorization rejections return HTTP 400 with `ok:false`, a message, unchanged sequence and empty data. HTTP-route authorization failures return 401/403. Cluster availability/uncertain outcomes return HTTP 503. Do not infer successful balance changes from the absence of a browser response; inspect history with the retained ID.

The `sequence` is the aggregate state-change sequence, not a count of monetary transactions. Successful nonfinancial commands consume sequence numbers too.

## Statements and DCS operations

| Method | Endpoint | Behavior |
|---|---|---|
| GET | /api/statement?account=...&from=YYYY-MM-DD&to=YYYY-MM-DD | CSV export; customer must own account; staff may inspect customer accounts |
| GET | /api/cluster | Admin only; live node roles, clocks, sequences, fingerprints and event logs |
| POST | /api/cluster/control | Admin + CSRF; branch=1/2/3, operation=START/STOP; real gateway-owned process control |

Date filters are inclusive UTC transaction dates. If omitted, the statement covers all records for the selected account. Text cells are escaped for CSV safety. The statement endpoint returns CSV directly rather than JSON.

## Internal RMI contract

- `status(secret)`
- `submit(command, clock, secret)`
- `replicate(state, clock, sender, secret)`
- `election(candidate, clock, secret)`
- `coordinator(leader, clock, secret)`

These services are loopback-only and require the generated runtime cluster key. They are internal infrastructure, not public browser endpoints. Do not expose them on a network or copy the key into frontend files.
