# PetroFlow ERP audit / fix report

Date: 2026-11-01  
Branch: `arena/b54884a6-petrolflow-erp`  
Status: **PARTIAL — source fixes applied and statically/pure-unit verified; client-demo acceptance is BLOCKED**

## 1. Executive status

This pass inspected the existing React/Vite frontend, Java 21 backend, MySQL schema, auth/permission helpers, test harness, and deployment scripts. It did **not** rebuild or rebrand the product.

Applied source-only fixes compile and pass the safe non-database checks available in this sandbox:

- Backend ECJ compile: **PASS** (16 non-fatal warnings).
- Safe Java harness (`SKIP_DB_TESTS=true`): **38 assertions PASS**.
- Frontend TypeScript typecheck: **PASS**.
- Frontend production build: **PASS**.
- `git diff --check`: **PASS**.

The following acceptance items are **not complete** and must not be represented as complete:

- No live MySQL/staging connection was supplied or discovered.
- No database schema/master-data verification was possible.
- No backup was taken.
- No DEMO-BAN / DEMO-HIN demo operational data was inserted.
- No live API, browser UI, hardware, or destructive integration suite was run.
- Gradle/full JDK tooling was unavailable; compilation used a cached ECJ compiler on a Java 21 JRE.

## 2. Database / demo-data blocker

The user selected isolated staging access, but no actual staging configuration appeared in the workspace or environment. `.env` and `backend/.env` were absent at last check.

Because of that, this pass did **not**:

- connect to a guessed database,
- run `seed.sql`,
- run `--reset-db`,
- drop/truncate/delete data,
- create or modify stations/users/products/tanks/nozzles/customers/suppliers,
- insert demo transactions,
- activate simulated or real hardware.

Per-station demo record counts for this pass:

| Station | Code | Demo records inserted | Verification status |
|---|---:|---:|---|
| Baner, Pune | DEMO-BAN | 0 | Blocked: staging access/config not supplied |
| Hinjewadi, Pune | DEMO-HIN | 0 | Blocked: staging access/config not supplied |

The legacy `backend/db/mysql/seed.sql` contains Sunrise Fuels `SUN-BAN` / `SUN-HIN` masters. It is **not** evidence that the requested `DEMO-BAN` / `DEMO-HIN` masters exist, and it was not executed.

## 3. Modules and interactive surfaces inspected

This was a source-level audit, not a completed browser click-through.

| Module | Surfaces inspected | Result |
|---|---|---|
| Dashboard / combined dashboard | KPI queries, station scoping, payables/receivables, device online count, trend/alerts | Source fixes applied; live UI unverified |
| Auth / stations / users | Permission helpers, station authorization, operator assignment, attendance station guards | Source fixes applied; live auth unverified |
| Products / prices | Effective-price lookup, historical price lookup for shifts/credit sales | Source fixes applied; live price history unverified |
| Tanks / stock | Opening stock, adjustments, transfers, dips, ledger, negative-stock guards, row locks | Source fixes applied; live inventory unverified |
| Dispensers / nozzles / readings | Shift nozzle mapping, simulated/countable reading rules, manual capture paths | Inspected; no hardware activated |
| Suppliers / deliveries | Delivery create/receive/dispute, supplier ledger, lubricant purchases, payout date parsing | Source fixes applied; invoice-level payment allocation still limited |
| Shifts / day close | Open/capture/close/approve/reverse, settlements, denominations, date filters, day lock | Source fixes applied; live shift regression unverified |
| Sales / credit sales | Credit amount validation, customer row locks, credit-limit checks, historical pricing | Source fixes applied; standalone credit-sale stock posting remains a design limitation |
| Hisab-Kitab / finance | Payments, receipts, payouts, expenses, customer/supplier ledgers, reconciliation | Source fixes applied; live financial regression unverified |
| Reconciliation | Independent cash/digital variance, insert-only revisions, approval note, latest-revision approval | Source fixes applied; live reconciliation unverified |
| Density | Legacy thermal estimate, screening bands, delete audit, compliance wording | Source fixes applied; ASTM/IS compliance explicitly not claimed |
| Employees / attendance | Attendance marking, duplicate users, time validation, station move guard, payable-day summary | Source fixes applied; live attendance unverified |
| Lubricants | SKU stock, sale/purchase/adjustment, credit sale, payment-mode validation, row locks | Source fixes applied; live lubricant regression unverified |
| Compliance | Create/patch/delete station authorization, linked asset validation, date/lead validation | Source fixes applied; live compliance unverified |
| Reports / exports | DSR, stock loss, ageing, CSV export error feedback, latest reconciliation revision | Source fixes applied; live export download unverified |
| Settings / admin | Settings validation, transactional save, audit, platform/admin surfaces | Source fixes applied; live admin unverified |

Placeholder scan: the previously found `window.alert` export/share failure paths in `Reports.tsx` were replaced with toast feedback. No completed interactive-element inventory can be claimed without browser verification.

## 4. Root causes and fixes applied

### 4.1 Network errors and duplicate submits

Root cause: fetch rejections could surface as raw browser errors, and several forms generated a new idempotency key on every retry.

Fixes:

- `frontend/src/lib/api.ts`
  - request/download fetch failures now become `ApiError(0, NETWORK_ERROR)` with retry guidance;
  - aborts are rethrown.
- `frontend/src/lib/idempotency.ts` (new)
  - stable key per serialized payload;
  - reset only after confirmed success;
  - tracker variant for mutations whose payload is known only inside `mutationFn`.
- Wired stable idempotency into:
  - Finance payment/expense,
  - Customers credit sale/receipt,
  - Suppliers payout,
  - Shifts open/close/reverse/day-close/reopen,
  - Deliveries create/receive/dispute,
  - Lubricants movement,
  - Density check,
  - Tanks dip/adjustment/opening/transfer,
  - Reconciliation save.

### 4.2 Financial validation and concurrency

Root cause: payment party/direction/mode combinations were under-validated; money could be rounded after delta calculations; customer balances were updated without row locks; some financial audit writes were best-effort.

Fixes:

- `backend/src/main/java/in/petrolflow/FinanceRules.java` (new)
  - customer receipts / supplier payouts only;
  - exactly one party id;
  - settled modes: CASH/UPI/CARD/BANK/CHEQUE;
  - CREDIT rejected as a settled payment mode;
  - positive/non-negative money with max 2 decimals;
  - credit amount must equal quantity × rate when both are present;
  - independent cash/digital reconciliation variances with offsetting-error protection.
- `FinanceApi.java`
  - payment/credit/expense validation wired to `FinanceRules`;
  - customer/payment row locks on balance-affecting paths;
  - historical price lookup for credit sales;
  - expense tender/category validation;
  - expense list joins creator name;
  - financial audit writes use transaction-participating `Auth.auditRequired`.
- `Auth.java`
  - added `auditRequired` with before/after JSON;
  - legacy non-financial audit remains best-effort.

### 4.3 Reconciliation history and approval

Root cause: reconciliation re-saves deleted/overwrote prior rows; approval used a fixed note; UI showed only cash variance and retained counted values across station/date changes.

Fixes:

- `FinanceApi.java`
  - insert-only reconciliation revisions;
  - identical re-save returns existing latest row;
  - changing an approved reconciliation requires a revision reason;
  - only the latest revision for a station/date can be approved;
  - approval requires a real note;
  - station row lock serializes revisions without a schema change.
- `frontend/src/pages/Reconciliation.tsx`
  - loads existing latest row for selected station/date;
  - shows independent cash and digital variances;
  - approval modal with reviewer note;
  - revision reason field for approved rows;
  - stable idempotency key;
  - invalidates reconciliation/dashboard queries after save/approval.
- `ReportApi.java`
  - DSR reads latest reconciliation revision.

### 4.4 Shifts and day close

Root cause: backend ignored frontend from/to filters; close accepted negative/duplicate settlements and denominations; expected cash used closing cash even when CASH received differed; day close was not atomic; operator assignment was under-validated; pricing used current time instead of shift time.

Fixes in `ShiftApi.java`:

- from/to business-date filters with validation;
- operator must be active and assigned to the station (owner exception);
- opening cash validated as non-negative money;
- station row lock around open/day-close/reopen;
- close locks shift row and validates:
  - settlement modes,
  - duplicate modes,
  - non-negative received amounts,
  - closingCash equals CASH received,
  - supported INR denominations,
  - duplicate denominations,
  - denomination total equals closing cash;
- expected cash uses actual CASH received;
- shift pricing uses shift opened-at historical price;
- adjustment litres included in net litres;
- negative testing litres rejected;
- close/reverse/day-close/reopen use strict audit.

`Domain.priceAt` gained an explicit timestamp overload.

### 4.5 Inventory guards

Root cause: stock movements could be written without tank row locks or projected negative-stock checks; unknown movement types defaulted positive; backdated operations used current timestamps.

Fixes in `Domain.java` / `FinanceApi.java` / `ShiftApi.java`:

- tank row lock before movement;
- tenant/station/product ownership check;
- projected negative book stock rejected;
- unknown movement types rejected;
- explicit movement types/signs;
- business occurred-at timestamps passed for delivery/adjustment/opening/transfer/shift movements.

### 4.6 Density honesty

Root cause: UI/backend/tests described a legacy coefficient calculation as ASTM/IS compliance evidence.

Fixes:

- `Domain.java`
  - labels now say “Uncertified legacy thermal estimate” and “screening band”;
  - explicitly not statutory compliance.
- `frontend/src/pages/Density.tsx`
  - removed ASTM/IS compliance wording;
  - “within/outside band” instead of certified spec language.
- `BackendTests.java`
  - expectations updated to assert uncertified labels.

The numeric legacy estimate was retained for backward compatibility. It is **not** certified.

### 4.7 Dashboard / reports / supplier ledger

Fixes:

- `ReportApi.java`
  - station receivables use station-scoped credit balances;
  - station payables computed from received fuel deliveries + lubricant purchases − non-reversed payouts;
  - device online count derived from heartbeat timeout instead of hardcoded zero;
  - month validation for stock-loss/ageing paths;
  - DSR latest reconciliation revision.
- `FinanceApi.java`
  - supplier ledger includes lubricant purchases;
  - note clarifies supplier opening balance is all-station only because it is not station-allocated.
- `frontend/src/pages/Reports.tsx`
  - export/share failures use toast instead of `window.alert`.

### 4.8 Attendance, lubricants, compliance, settings

Fixes in `OpsApi.java`:

- attendance:
  - duplicate user entries rejected;
  - HH:mm in/out validation;
  - out >= in;
  - PRESENT/HALF_DAY require inTime;
  - existing user/date attendance cannot silently move to another station;
  - month range validation.
- lubricants:
  - product/customer row locks;
  - sale/payment mode whitelist;
  - amount must equal quantity × rate when supplied;
  - credit customer balance update uses locked customer row.
- compliance:
  - patch/delete load row and enforce station authorization;
  - linked dispenser/tank must belong to station;
  - issued/expires/lead validation;
  - strict audit on update/delete.
- settings:
  - unknown keys rejected;
  - boolean/number/email validation;
  - range checks for heartbeat/alert settings;
  - transactional save with row lock and strict audit.

## 5. Files changed

Backend:

- `backend/src/main/java/in/petrolflow/Auth.java`
- `backend/src/main/java/in/petrolflow/Domain.java`
- `backend/src/main/java/in/petrolflow/FinanceApi.java`
- `backend/src/main/java/in/petrolflow/FinanceRules.java` (new)
- `backend/src/main/java/in/petrolflow/OpsApi.java`
- `backend/src/main/java/in/petrolflow/ReportApi.java`
- `backend/src/main/java/in/petrolflow/ShiftApi.java`
- `backend/src/test/java/in/petrolflow/BackendTests.java`

Frontend:

- `frontend/src/lib/api.ts`
- `frontend/src/lib/idempotency.ts` (new)
- `frontend/src/pages/Customers.tsx`
- `frontend/src/pages/Deliveries.tsx`
- `frontend/src/pages/Density.tsx`
- `frontend/src/pages/Finance.tsx`
- `frontend/src/pages/Lubricants.tsx`
- `frontend/src/pages/Reconciliation.tsx`
- `frontend/src/pages/Reports.tsx`
- `frontend/src/pages/Shifts.tsx`
- `frontend/src/pages/Suppliers.tsx`
- `frontend/src/pages/Tanks.tsx`

Documentation:

- `docs/AUDIT_AND_FIX_REPORT.md` (this file)

No schema migration, seed execution, or database write was performed.

## 6. APIs touched or behaviorally affected

- `/finance/payments`
- `/finance/payments/:id/settle`
- `/finance/payments/:id/reverse`
- `/finance/expenses`
- `/finance/expenses/:id`
- `/finance/credit-sales`
- `/finance/customer-ledger`
- `/finance/supplier-ledger`
- `/finance/reconciliation`
- `/finance/reconciliation/:id/approve`
- `/inventory/deliveries`
- `/inventory/deliveries/:id/receive`
- `/inventory/deliveries/:id/dispute`
- `/inventory/adjustments`
- `/inventory/opening-stock`
- `/inventory/transfers`
- `/shifts`
- `/shifts/:id/capture`
- `/shifts/:id/close`
- `/shifts/:id/approve`
- `/shifts/:id/reverse`
- `/shifts/:id/nozzles/:nozzleId`
- `/shifts/day-close`
- `/shifts/day-close/reopen`
- `/lubricants/movements`
- `/density`
- `/density/:id`
- `/compliance`
- `/compliance/:id`
- `/attendance/mark`
- `/attendance/summary`
- `/settings`
- `/reports/owner-dashboard`
- `/reports/dsr`
- `/reports/stock-loss`
- `/reports/ageing`
- `/reports/export/*`

## 7. Test/build results

### Passed in this sandbox

1. Backend compile using cached ECJ on Java 21 JRE:
   - all main/test sources compiled;
   - 16 non-fatal warnings (unused imports/methods, serialVersionUID, unnecessary suppressions).
2. Safe backend harness:
   - command: `SKIP_DB_TESTS=true java -cp <classes> in.petrolflow.BackendTests`
   - result: `ALL 38 JAVA BACKEND ASSERTIONS PASSED.`
   - covers Money/Clock/Json, passwords/permissions, MySQL initialization without CREATE DATABASE/USE, FinanceRules, stock signs, density label honesty.
3. Frontend:
   - `npm run typecheck -w @petroflow/web` — PASS
   - `npm run build -w @petroflow/web` — PASS
4. `git diff --check` — PASS

### Not run / blocked

- Gradle build/test: full JDK/Gradle unavailable.
- Default `BackendTests` MySQL HTTP suite: destructive (`resetDatabase`) and no disposable staging schema was supplied.
- `scripts/start-e2e-api.mjs`: always passes `--reset-db`; unsafe for staging and not run.
- Playwright/browser UI: no browser installed/verified.
- Live API/database persistence, station isolation, idempotency replay, financial/inventory regressions: blocked by staging access.
- Hardware/gateway/simulator: not connected or activated.

## 8. Known limitations remaining

1. **Demo data**: not inserted; DEMO-BAN/DEMO-HIN existence unverified.
2. **Standalone fuel credit sales**: still ledger-first; they do not yet have a safe nozzle/shift-linked stock posting model. Adding stock deduction blindly could double-count shift totalizers.
3. **Delivery invoice payments**: supplier payouts are station/supplier-level; invoice-level allocation is not modeled in the current payments schema.
4. **Supplier opening balance**: shown only in all-station ledger because it is not station-allocated.
5. **Density**: legacy estimate is explicitly uncertified; no ASTM/IS compliance claim.
6. **Expense delete**: existing hard-delete endpoint remains; strict audit was added, but a soft-delete/void migration was not introduced.
7. **Full interactive inventory**: cannot be honestly completed without browser/API verification.
8. **Reconciliation uniqueness**: insert-only revisions are serialized by station row lock; no new unique station/date index was added because schema changes were avoided.

## 9. Safe deployment / next steps

1. Provide an isolated staging copy containing the actual DEMO-BAN and DEMO-HIN masters.
2. Configure backend DB environment securely (do not paste production credentials into chat):
   - `PETROFLOW_DB_HOST`
   - `PETROFLOW_DB_PORT`
   - `PETROFLOW_DB_NAME`
   - `PETROFLOW_DB_USER`
   - `PETROFLOW_DB_PASSWORD`
3. Verify staging identity read-only:
   - tenant id/name,
   - station ids/codes `DEMO-BAN`, `DEMO-HIN`,
   - existing users/products/tanks/nozzles/customers/suppliers,
   - current balances/stock,
   - schema columns and demo flags.
4. Take and verify a database backup.
5. Run non-destructive integration tests against a disposable schema only.
6. Build frontend and backend with the normal JDK 21/Gradle toolchain.
7. Insert demo operational data only through an idempotent, transactional, insert-only script restricted to the two verified demo stations, reusing existing masters and marking `is_demo=1` where supported.
8. Verify both stations separately:
   - ledger signs,
   - received delivery litres,
   - meter continuity,
   - non-negative stock,
   - credit/outstanding balances,
   - independent cash/digital reconciliation,
   - attendance/density/lubricants,
   - weekly/monthly reports.
9. Run browser click-through and network-error/duplicate-submit tests.
10. Only then declare client-demo readiness.

**Do not run `--reset-db`, blind `seed.sql`, or destructive suites against staging/production.**
