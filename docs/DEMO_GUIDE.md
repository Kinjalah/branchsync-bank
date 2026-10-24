# Complete implementation demonstration

Use fresh demo data for the exact numbers below. With existing data, use the same actions and report your actual starting/ending balances.

## A. Show the banking product first

1. Start the application and open http://localhost:8090.
2. Sign in as `kinjal` with `Kinjal@12345`.
3. Explain the overview: two owned accounts, total available ₹1,53,500, actual recorded activity, credits/debits chart, card and service shortcuts.
4. Open Accounts & cards to show savings/current accounts and card controls.

The app never displays another customer's account in Kinjal's dashboard. The account ending 0001 has ₹1,25,000 initially; the account ending 0002 has ₹28,500.

## B. Make and verify a transfer

1. Move money → fund from `10010001`.
2. Choose the saved beneficiary Aarav, account `10020001`.
3. Enter ₹1,000 and note `Project demonstration transfer`.
4. Review the account, recipient and amount; confirm.
5. Open the receipt. Use Print / save PDF if you want a receipt artifact on your computer.

Expected: Kinjal savings ₹1,24,000; Aarav savings ₹87,400. Sign in as Aarav in a separate browser profile to verify the received credit. Same-profile tabs share cookies, so do not use them as separate identities.

A request ID identifies the operation. The persisted ID prevents an identical successful request from being applied again. On uncertain errors retain it; do not blindly submit a fresh ID.

## C. Pay a fictional bill

1. Sign in as Kinjal → Bill payments → Electricity.
2. Choose savings, consumer `DEMO-ELEC-1234`, amount ₹250.
3. Review and confirm.

Expected savings balance: ₹1,23,750. View the receipt and the new bill payment in Activity & statements. This is a local simulated bill settlement, not a payment to a real provider.

## D. Show savings and controlled redemption

1. Fixed deposits → Open a deposit.
2. Savings account, ₹2,000, 6 months.
3. Review and confirm.

Expected: savings ₹1,21,750; an active deposit holds ₹2,000. The overview's fixed-deposit figure reflects it.

4. Redeem it early and confirm.
5. Expected: principal returned, savings ₹1,23,750, deposit CLOSED. Early closure has no interest.

On or after maturity the backend calculates 6% simple annual interest over the original term and credits it with principal. Do not alter the computer clock for the demo; the domain test covers the matured path.

## E. Show genuine role separation with cash requests

1. Customer → Service requests → Cash deposit.
2. Savings account, ₹500; details `Cash received at the demo branch counter`.
3. Submit. Balance remains ₹1,23,750 while the request is PENDING.
4. In a different browser profile sign in as `teller` / `Teller@12345`.
5. Request queue → review the cash deposit → add a decision note and approve.
6. Return to Kinjal's dashboard and refresh/navigate to overview.

Expected: request COMPLETED and savings ₹1,24,250. The staff audit trail attributes approval to the teller. The customer cannot approve it through the UI or by forging an API call.

For a withdrawal, submit a CASH_WITHDRAWAL request; teller approval validates/debits available funds. Pending requests can be cancelled by their owner. Staff can reject them with a recorded note.

## F. Demonstrate the loan lifecycle

1. Customer → Loans → Apply, savings, principal ₹5,000, purpose `Study project equipment`.
2. Staff reviews and approves with a decision note.
3. Expected: savings ₹1,29,250; active loan principal/outstanding ₹5,000.
4. Customer → Make repayment of ₹1,000.
5. Expected: savings ₹1,28,250; outstanding ₹4,000.
6. Repay the remaining ₹4,000 if desired; loan becomes CLOSED and savings ₹1,24,250.

The loan model deliberately tracks principal only. It does not pretend to provide credit scoring, EMI scheduling or a complete lending interest engine.

## G. Account/card controls and statements

- Customer can freeze/unfreeze the demo card and set a daily debit limit. Card availability is independent of account transfers.
- Teller can freeze the actual bank account. A customer transfer from or into that account is rejected until unfreezing.
- Administrator can suspend customer access. Existing sessions are invalidated; restoration requires signing in again.
- Activity & statements supports reference/note/account search, type filtering, receipts and date-filtered CSV export.
- An owned account's statement is accessible; another customer's statement is forbidden to the customer.
- Profile updates persist. Changing password invalidates old sessions.

## H. New-customer onboarding

1. Open a separate browser profile → Open a demo account.
2. Use fictional contact data and a password of at least 12 characters with uppercase, lowercase and a number.
3. Sign in: account starts at zero and KYC is PENDING.
4. Show that banking updates are unavailable before verification.
5. Teller → Customers & KYC → profile → Verify demo KYC.
6. Customer refreshes: banking services can now be used, subject to funds/limits.
7. Submit a cash deposit request and approve it as teller to fund the account.

KYC is a manual educational workflow, not a real identity-document verification integration.

## I. Reveal the real distributed implementation

In another browser profile sign in as `admin` / `Admin@123456` → DCS operations.

### 1. Replication evidence

Show all three nodes ready and normally Node 3 leader. Complete a customer action through Branch 1 while watching the console. Every successful mutation has a replicated state sequence and a canonical snapshot fingerprint.

Explain that sequences count nonfinancial changes too. Card freezes, KYC decisions and approvals increment state sequence, even when no monetary transaction is appended.

### 2. RMI and ordering evidence

Choose Node 1's timeline to show FORWARD messages. Node 3 logs APPLIED and replica acknowledgments. Follower logs show REPLICATE. Logical counters change through local/send/receive events; they need not match across nodes.

### 3. Leader failure

Finish requests and wait for equal replica fingerprints. Click Stop process on Node 3 and confirm. This terminates the real child JVM. The others detect failed peer calls and run the Bully-style election. Wait for Node 2 as coordinator.

Customer: choose Branch 1, transfer ₹1 to your own current account (new operation). Confirm the banking service continues on the surviving replicas.

### 4. Recovery

Admin: Start & recover Node 3. Observe RECOVERY synchronized, equal fingerprints and higher-ID Node 3 eventually becoming leader again. Wait for agreement before the next operation.

### 5. Availability boundary

After agreement, stop Node 3 and Node 2. Node 1 remains available for reads/authentication, but refuses a new banking write without a synchronized follower. Restore Node 2 and Node 3 from the same console. The console prevents stopping the final node.

Do not claim that this proves network-partition safety, consensus or arbitrary mid-transaction crash recovery. It demonstrates the specified controlled process-crash model.

## J. Concurrency and restart evidence

Stop the interactive application, then run:

```powershell
py -3 tests\integration.py
```

The suite submits concurrent conflicting debits through different branches: each attempts to spend 80% of the same available account balance. Exactly one succeeds; the other is rejected. It also tests ownership checks, CSRF, staff permissions, full-cluster persistence and failover.

For a manual persistence demonstration, record balances/requests, stop with Ctrl+C, restart with start.ps1 and sign in again. The gateway session does not survive restart, but account data, request outcomes and idempotency records do.

## Recommended evidence to capture yourself

1. Customer sign-in and personalized overview.
2. Transfer review and transaction receipt.
3. Aarav's independently authenticated received credit.
4. Cash request pending, then teller approval and increased balance.
5. New customer pending KYC, followed by staff verification.
6. Deposit open/close and loan approval/repayment.
7. CSV statement and card/account controls.
8. Admin console with three matching replica fingerprints.
9. Actual leader stopped, replacement leader and successful transaction.
10. Restarted node recovery and matching fingerprints.
11. Test output showing authorization, concurrency and persistence checks.

Do not label earlier v1 screenshots as evidence for these new workflows. Capture the upgraded running application.
