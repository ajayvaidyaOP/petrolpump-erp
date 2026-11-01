package in.petrolflow;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure Java 21 unit + MySQL 8 HTTP integration test suite for PetroFlow ERP.
 */
public final class BackendTests {
    private static int passed = 0;

    private BackendTests() {}

    public static void main(String[] args) throws Exception {
        System.out.println("Running PetroFlow Java 21 backend verification suite...");
        testMoneyAndJson();
        testPasswordsAndPermissions();
        testMysqlConnectionInitializationWithoutDatabaseCreation();
        testFinanceRules();
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("SKIP_DB_TESTS", "false"))) {
            testHttpAndDatabaseIntegration();
        }
        System.out.println("ALL " + passed + " JAVA BACKEND ASSERTIONS PASSED.");
        System.exit(0);
    }

    private static void testMoneyAndJson() {
        check("123.46".equals(Money.money("123.456")), "Money.money 2dp rounding");
        check("45.500".equals(Money.litres("45.5")), "Money.litres 3dp formatting");
        check("104.650".equals(Money.rate("104.65")), "Money.rate 3dp formatting");
        check("25.0".equals(Money.percent(new BigDecimal("250"), "1000", 1)), "Money.percent calculation");
        check(Clock.todayIst().matches("\\d{4}-\\d{2}-\\d{2}"), "Clock.todayIst ISO format");
        check("2026-10-30".equals(Clock.addDays("2026-10-29", 1)), "Clock.addDays");

        Map<String, Object> row = Web.map("station_id", "stn_1", "total_value", "500.00", "features", "[\"A\",\"B\"]");
        Map<String, Object> shaped = Json.shape(row, List.of("features"));
        check("stn_1".equals(shaped.get("stationId")), "Json.shape snake_case -> camelCase");
        check(shaped.get("features") instanceof List<?> list && list.size() == 2, "Json.shape JSON column parsing");
        String encoded = Json.stringify(shaped);
        Map<String, Object> roundTrip = Json.object(Json.parse(encoded));
        check("500.00".equals(roundTrip.get("totalValue")), "Json parse/stringify round-trip");
    }

    private static void testPasswordsAndPermissions() {
        String hashed = Passwords.hash("PetroFlow@2025");
        check(hashed.startsWith("pbkdf2$"), "PBKDF2 prefix");
        check(Passwords.verify("PetroFlow@2025", hashed), "PBKDF2 valid password verify");
        check(!Passwords.verify("WrongPassword1", hashed), "PBKDF2 invalid password reject");

        List<String> ownerPerms = Permissions.forRole("OWNER");
        check(ownerPerms.contains("employees.write") && !ownerPerms.contains("platform.manage"), "Owner permissions");
        List<String> staffClean = Permissions.sanitize(List.of("sales.read", "platform.manage", "employees.write", "unknown.perm"));
        check(staffClean.equals(List.of("sales.read")), "Staff permission sanitization blocks escalations");
    }

    private static void testMysqlConnectionInitializationWithoutDatabaseCreation() throws Exception {
        try (MockMysqlFaultServer mock = new MockMysqlFaultServer()) {
            Config config = Config.load(Map.ofEntries(
                    Map.entry("PETROFLOW_DB_HOST", "127.0.0.1"),
                    Map.entry("PETROFLOW_DB_PORT", String.valueOf(mock.port())),
                    Map.entry("PETROFLOW_DB_NAME", "precreated_schema_probe"),
                    Map.entry("PETROFLOW_DB_USER", "schema_scoped_probe"),
                    Map.entry("PETROFLOW_DB_PASSWORD", ""),
                    Map.entry("PETROFLOW_DB_POOL_MAX", "2")));
            try {
                Db.open(config);
                check(mock.handshakeSchemas.contains("precreated_schema_probe"),
                        "Mysql initial handshake selects the configured existing schema");
                check(mock.sqlCommands.stream().noneMatch(sql -> sql.toUpperCase(java.util.Locale.ROOT).contains("CREATE DATABASE")),
                        "Mysql connection initialization never issues CREATE DATABASE");
                check(mock.sqlCommands.stream().noneMatch(sql -> sql.trim().toUpperCase(java.util.Locale.ROOT).startsWith("USE ")),
                        "Mysql connection initialization never issues USE");
                check(mock.sqlCommands.stream().anyMatch(sql -> sql.equalsIgnoreCase("SET NAMES utf8mb4")),
                        "Mysql connection initialization preserves session configuration");
            } finally {
                Db.close();
            }
        }
    }


    private static void testFinanceRules() {
        FinanceRules.PaymentParty receipt = FinanceRules.validatePayment("customer", "receipt", "cus_1", null, "upi");
        check("CUSTOMER".equals(receipt.partyType()) && "RECEIPT".equals(receipt.direction()) && "cus_1".equals(receipt.customerId()),
                "FinanceRules normalizes customer receipt");
        FinanceRules.PaymentParty payout = FinanceRules.validatePayment("SUPPLIER", "PAYOUT", null, "sup_1", "BANK");
        check("sup_1".equals(payout.supplierId()) && payout.customerId() == null, "FinanceRules normalizes supplier payout");
        expectValidation(() -> FinanceRules.validatePayment("CUSTOMER", "PAYOUT", "cus_1", null, "CASH"), "customer payout rejected");
        expectValidation(() -> FinanceRules.validatePayment("SUPPLIER", "PAYOUT", "cus_1", "sup_1", "CASH"), "mixed parties rejected");
        expectValidation(() -> FinanceRules.validatePayment("CUSTOMER", "RECEIPT", "cus_1", null, "CREDIT"), "credit is not a settled payment mode");
        expectValidation(() -> FinanceRules.positiveMoney("0", "amount"), "zero payment rejected");
        expectValidation(() -> FinanceRules.positiveMoney("10.001", "amount"), "sub-paise payment rejected");
        expectValidation(() -> FinanceRules.nonNegativeMoney("-1", "cashCounted"), "negative counted cash rejected");
        check("100.10".equals(Money.money(FinanceRules.positiveMoney("100.1", "amount"))), "positive money normalized to 2dp");
        check("1050.00".equals(Money.money(FinanceRules.creditAmount(null, "10", "105"))), "credit amount derived from litres × rate");
        check("1050.00".equals(Money.money(FinanceRules.creditAmount("1050.00", "10", "105"))), "consistent credit amount accepted");
        expectValidation(() -> FinanceRules.creditAmount("1049.99", "10", "105"), "inconsistent credit amount rejected");
        expectValidation(() -> FinanceRules.creditAmount(null, "10", null), "quantity credit sale requires rate");
        FinanceRules.Variances balanced = FinanceRules.reconciliation(new BigDecimal("1000"), new BigDecimal("1000.50"), new BigDecimal("500"), new BigDecimal("499.50"));
        check(balanced.balanced() && "BALANCED".equals(balanced.status()), "reconciliation within ₹1 per channel is balanced");
        FinanceRules.Variances offset = FinanceRules.reconciliation(new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("500"), new BigDecimal("600"));
        check(!offset.balanced() && "VARIANCE".equals(offset.status()) && offset.displayed().compareTo(new BigDecimal("-100.00")) == 0,
                "offsetting cash/digital errors never hide variance");
        FinanceRules.Variances digitalOnly = FinanceRules.reconciliation(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("520"));
        check(!digitalOnly.balanced() && digitalOnly.digital().compareTo(new BigDecimal("20.00")) == 0, "digital-only variance detected independently");
        check(Domain.signedLitres("DISPENSED", "10").compareTo(new BigDecimal("-10")) == 0, "dispensed stock sign negative");
        check(Domain.signedLitres("ADJUSTMENT", "-5").compareTo(new BigDecimal("-5")) == 0, "adjustment preserves sign");
        expectValidation(() -> Domain.signedLitres("UNKNOWN", "5"), "unknown stock movement type rejected");
        Domain.DensityEvaluation density = Domain.evaluateDensityAt15c("DIESEL", "835.0", "15.0");
        check(density.specificationUsed().contains("Uncertified") && density.specificationUsed().contains("not statutory compliance"),
                "density label does not claim ASTM/IS compliance");
    }

    private static void expectValidation(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError("Expected validation failure: " + label);
        } catch (ApiException expected) {
            check(expected.status == 422 || expected.status == 400, label);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testHttpAndDatabaseIntegration() throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("PETROFLOW_TEST_DB_DISPOSABLE", "false"))) {
            throw new IllegalStateException("Destructive backend integration tests require PETROFLOW_TEST_DB_DISPOSABLE=true");
        }
        int testPort = Integer.parseInt(System.getenv().getOrDefault("TEST_API_PORT", "4199"));
        String testDb = System.getenv().getOrDefault("TEST_DB_NAME", "petroflow_test");
        if (!"petroflow_test".equals(testDb)) {
            throw new IllegalStateException("Refusing destructive integration tests outside designated disposable schema petroflow_test: " + testDb);
        }
        Config base = Config.load(Map.of("PORT", String.valueOf(testPort), "PETROFLOW_DB_NAME", testDb));
        if (!("127.0.0.1".equals(base.dbHost) || "localhost".equalsIgnoreCase(base.dbHost))
                || base.dbPort != 3306
                || !"petroflow_test_app".equals(base.dbUser)) {
            throw new IllegalStateException("Destructive integration tests require the isolated local CI MySQL service and petroflow_test_app account");
        }
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("CI", "false"))
                || !"true".equalsIgnoreCase(System.getenv().getOrDefault("GITHUB_ACTIONS", "false"))) {
            throw new IllegalStateException("Destructive backend integration tests are restricted to GitHub Actions disposable MySQL");
        }
        Db.open(base);
        Map<String, Object> connectionIdentity = Db.one("SELECT DATABASE() AS selected_database, CURRENT_USER() AS principal");
        check(testDb.equals(Db.str(connectionIdentity, "selected_database")),
                "Backend integration connects to the pre-created disposable test schema");
        check(Db.str(connectionIdentity, "principal") != null
                        && Db.str(connectionIdentity, "principal").startsWith("petroflow_test_app@"),
                "Backend integration uses the schema-scoped CI test principal");
        Main.resetDatabase(base);
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        Main.seedIfEmpty(base);

        verifyTransactionSafety();

        Web web = Main.buildWeb(base);
        web.listen();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String url = "http://127.0.0.1:" + testPort + "/api/v1";

            // 1. Health check
            HttpResponse<String> health = get(client, url + "/health", null);
            check(health.statusCode() == 200, "GET /health returns 200");

            // 2. Bad login -> 401
            HttpResponse<String> badLogin = post(client, url + "/auth/login", null, Map.of("email", "owner@sunrisefuels.in", "password", "bad"));
            check(badLogin.statusCode() == 401, "POST /auth/login rejects wrong password with 401");

            // 3. Valid owner login + preview cookie check
            HttpRequest previewLoginReq = HttpRequest.newBuilder(URI.create(url + "/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-Host", "5173-sandbox.e2b.app")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(Map.of("email", "owner@sunrisefuels.in", "password", base.demoPassword))))
                    .build();
            HttpResponse<String> loginRes = client.send(previewLoginReq, HttpResponse.BodyHandlers.ofString());
            check(loginRes.statusCode() == 200, "POST /auth/login accepts valid demo owner");
            String setCookie = loginRes.headers().firstValue("Set-Cookie").orElse("");
            check(setCookie.contains("SameSite=None") && setCookie.contains("Secure") && !setCookie.contains("Partitioned"),
                    "Preview Set-Cookie uses SameSite=None; Secure without Partitioned");

            Map<String, Object> loginData = Json.object(Json.object(Json.parse(loginRes.body())).get("data"));
            String ownerToken = String.valueOf(loginData.get("sessionToken"));
            check(ownerToken != null && ownerToken.length() > 20, "sessionToken issued in non-production");

            // 4. /auth/me & stations
            HttpResponse<String> meRes = get(client, url + "/auth/me", ownerToken);
            check(meRes.statusCode() == 200, "GET /auth/me returns 200 with Bearer sessionToken");
            Map<String, Object> meData = Json.object(Json.object(Json.parse(meRes.body())).get("data"));
            List<?> stations = (List<?>) meData.get("stations");
            check(stations != null && stations.size() >= 2, "Demo owner has 2 stations");
            String stationId = String.valueOf(Json.object(stations.get(0)).get("id"));

            // 5. DSR & Combined CSV export (`stationId=all`)
            HttpResponse<String> dsrRes = get(client, url + "/reports/dsr?stationId=" + stationId + "&date=2026-10-29", ownerToken);
            check(dsrRes.statusCode() == 200, "GET /reports/dsr returns 200");
            Map<String, Object> dsrData = Json.object(Json.object(Json.parse(dsrRes.body())).get("data"));
            check(dsrData.get("transactions") instanceof List<?> && dsrData.get("deliveries") instanceof List<?>,
                    "DSR includes transactions and deliveries arrays for combined DSR merge");

            HttpResponse<String> csvAll = get(client, url + "/reports/export/sales-register.csv?stationId=all&month=2026-10", ownerToken);
            check(csvAll.statusCode() == 200 && csvAll.body().startsWith("station,date,shift,product,litres,amount"),
                    "Sales register CSV supports stationId=all");

            // 6. Device registration (`pfk_` prefix + `isSimulated: false`) & Gateway ingestion idempotency
            HttpResponse<String> devRes = post(client, url + "/devices", ownerToken, Web.map(
                    "stationId", stationId,
                    "code", "GW-JAVA-TEST",
                    "name", "Java Test Controller",
                    "type", "CONTROLLER",
                    "isSimulated", false));
            check(devRes.statusCode() == 201, "POST /devices creates device (201)");
            Map<String, Object> devData = Json.object(Json.object(Json.parse(devRes.body())).get("data"));
            String apiKey = String.valueOf(devData.get("apiKey"));
            check(apiKey.startsWith("pfk_"), "Issued device API key starts with pfk_");
            check("0".equals(String.valueOf(devData.get("isSimulated"))), "Real device has isSimulated = 0");

            HttpRequest gwEvReq = HttpRequest.newBuilder(URI.create(url + "/gateway/events"))
                    .header("Content-Type", "application/json")
                    .header("X-Device-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(Web.map("events", List.of(Web.map(
                            "eventId", "ev_fixed_001",
                            "eventType", "HEARTBEAT",
                            "sequence", 1,
                            "payload", Web.map("ok", true)))))))
                    .build();
            HttpResponse<String> gwFirst = client.send(gwEvReq, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> gwDup = client.send(gwEvReq, HttpResponse.BodyHandlers.ofString());
            check(gwFirst.statusCode() == 200 && gwDup.statusCode() == 200, "Gateway events endpoint is idempotent");
            Map<String, Object> dupData = Json.object(Json.object(Json.parse(gwDup.body())).get("data"));
            check("1".equals(String.valueOf(dupData.get("duplicates"))), "Duplicate eventId counted as duplicate");

            // 7. RBAC: Owner cannot access /admin/metrics (403), Super Admin can (200)
            HttpResponse<String> forbiddenAdmin = get(client, url + "/admin/metrics", ownerToken);
            check(forbiddenAdmin.statusCode() == 403, "Tenant owner blocked from /admin/metrics (403)");

            HttpResponse<String> saLogin = post(client, url + "/auth/login", null, Map.of("email", "superadmin@petroflow.dev", "password", base.demoPassword));
            String saToken = String.valueOf(Json.object(Json.object(Json.parse(saLogin.body())).get("data")).get("sessionToken"));
            check(get(client, url + "/admin/metrics", saToken).statusCode() == 200, "Super admin accesses /admin/metrics (200)");
            check(get(client, url + "/admin/audit", saToken).statusCode() == 200, "Super admin accesses /admin/audit (200)");
            check(get(client, url + "/admin/plans", saToken).statusCode() == 200, "Super admin accesses /admin/plans (200)");

            // 8. Step 1: Tank ledger contract & opening stock rules
            verifyStep1TankLedgerAndOpeningStock(client, url, ownerToken);

            // 9. Step 2: Adjustment signs, reason, station/tenant isolation, transfer product check, water dip, movements joins
            verifyStep2TankAdjustmentsAndTransfers(client, url, ownerToken);

            // 10. Step 3: Deliveries amount calculation, receiveNow, decantation fields, actual receivedLitres, duplicate-receipt guard
            verifyStep3Deliveries(client, url, ownerToken);

            // 11. Step 4: Payment settlementRef, atomic payment reversal, customer balance & ledger restoration, duplicate reversal guard
            verifyStep4PaymentsAndReversal(client, url, ownerToken);

            // 12. Step 5: Cash & digital reconciliation exact formula, separate variances, idempotent date upsert, approval
            verifyStep5Reconciliation(client, url, ownerToken);

            // 13. Step 6: Lubricant SKU patch, sale amount, inclusive GST extraction, credit posting & stock guard
            verifyStep6Lubricants(client, url, ownerToken);

            // 14. Step 7: Server-side ASTM D1250 / IS 1448 Table 53B density @ 15°C & BS-VI spec validation
            verifyStep7Density(client, url, ownerToken);

            // 15. Step 8: Attendance ('LEAVE' case, weekOff in payableDays, station/tenant scoping) & DSR stock fields
            verifyStep8AttendanceAndDsr(client, url, ownerToken);

            // 16. Step 9: Day-close enforcement across all operational/financial mutations & post-commit Idempotency-Key
            verifyStep9DayCloseAndIdempotency(client, url, ownerToken);

            // 17. Step 10: Active nozzle/tank station & product consistency validation
            verifyStep10CatalogValidation(client, url, ownerToken);

            // 18. Priority 1: Multi-JVM atomic idempotency across separate JVM processes
            verifyPriority1MultiJvmAtomicIdempotency(client, url, base, testDb, ownerToken);

            // 19. Priority 2: ASTM D1250-80 Table 53B domain & IS 1460:2017 BS-VI 845.0 ceiling compliance
            verifyPriority2DensityCompliance(client, url, ownerToken);

            // 20. Priority 3: Station & tenant security across OWNER, STATION-1 MANAGER, and STATION-1 STAFF
            verifyPriority3StationAndTenantSecurity(client, url, ownerToken);

            // 21. Audit Remediation F-05 through F-10 regression verification across OWNER, STN1 MANAGER, STN1 STAFF, STN2 STAFF
            verifyAuditRemediationF05ToF10(client, url, ownerToken);

            // 22. F-16 & A-01: Supplier directory openingBalance isolation, DECIMAL(20,2) aggregate precision & ledger parity
            verifyF16SupplierOpeningBalanceIsolation(client, url, ownerToken);
            verifyA01SupplierOpeningBalanceAggregatePrecision(client, url, ownerToken);

            // 23. F-17: Idempotency short lease recovery, active-tx protection, rollback, and concurrent retry safety
            verifyF17IdempotencyLeaseRecovery(client, url, web, base, testDb, ownerToken);

            // 24. F-18: Bounded MySQL connection pooling, tx single-connection pinning, timeouts, and zero leaks
            verifyF18MysqlConnectionPooling(base, testDb);

            // 25. Phase 0 security remediation: A5 login throttling over HTTP + A4 production demo guard
            verifyLoginThrottleAndDemoGuard(client, url, base, testDb);

            // 26. Phase 0 completion: D1/D2 first-owner bootstrap, D3 advisory-locked throttle, D6 reset guard
            verifyBootstrapFlow(client, url, base);
            verifyThrottleAdvisoryLock(base);

            // Reset clean demo seed in isolated test DB at end of test run
            Main.resetDatabase(base);
            Main.apply(base.mysqlDir().resolve("schema.sql"));
            Main.seedIfEmpty(base);
        } finally {
            web.stop();
            Db.close();
        }
    }

    private static void verifyTransactionSafety() throws Exception {
        long beforeSettings = Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` LIKE 'tx.test.%'");
        try {
            Db.tx(() -> {
                Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, '', '', 'tx.test.rt', '1', ?)",
                        Clock.newId("stg"), Clock.nowIso());
                throw ApiException.conflict("Simulated RuntimeException failure");
            });
        } catch (ApiException expected) {
            // expected
        }
        check(Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = 'tx.test.rt'") == 0,
                "Db.tx rolls back uncommitted writes on RuntimeException");

        try {
            Db.tx(() -> {
                Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, '', '', 'tx.test.err', '1', ?)",
                        Clock.newId("stg"), Clock.nowIso());
                throw new AssertionError("Simulated Error failure");
            });
        } catch (AssertionError expected) {
            // expected
        }
        check(Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = 'tx.test.err'") == 0,
                "Db.tx rolls back uncommitted writes on Error and leaves connection clean");

        String counterKey = "tx.test.concurrent";
        Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, '', '', ?, '0', ?)",
                Clock.newId("stg"), counterKey, Clock.nowIso());
        int threads = 8;
        int incrementsPerThread = 5;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < incrementsPerThread; i++) {
                    Db.tx(() -> {
                        Map<String, Object> row = Db.one("SELECT id, value FROM app_settings WHERE `key` = ?", counterKey);
                        int cur = Integer.parseInt(String.valueOf(row.get("value")));
                        Db.exec("UPDATE app_settings SET value = ? WHERE id = ?", String.valueOf(cur + 1), row.get("id"));
                        return null;
                    });
                }
            }));
        }
        for (var f : futures) f.get();
        pool.shutdown();
        String finalCount = Db.str(Db.one("SELECT value FROM app_settings WHERE `key` = ?", counterKey), "value");
        Db.exec("DELETE FROM app_settings WHERE `key` = ?", counterKey);
        check(String.valueOf(threads * incrementsPerThread).equals(finalCount)
                        && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` LIKE 'tx.test.%'") == beforeSettings,
                "Db.tx serializes concurrent multi-thread read-modify-write transactions safely");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep1TankLedgerAndOpeningStock(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        Map<String, Object> stock1 = dataMap(get(client, url + "/inventory/stock?stationId=" + stn1, ownerToken));
        List<Map<String, Object>> tanks1 = (List<Map<String, Object>>) stock1.get("tanks");
        Map<String, Object> seededTank = tanks1.get(0);
        String seededTankId = String.valueOf(seededTank.get("id"));
        String productId = String.valueOf(seededTank.get("productId"));

        // 1a. Tank ledger response contract matches Tanks.tsx
        HttpResponse<String> ledgerRes = get(client, url + "/inventory/tanks/" + seededTankId + "/ledger", ownerToken);
        check(ledgerRes.statusCode() == 200, "Step 1: GET /inventory/tanks/:id/ledger returns 200");
        Map<String, Object> ledgerData = dataMap(ledgerRes);
        check(seededTank.get("bookStockLitres").equals(ledgerData.get("closingBookStock"))
                        && seededTank.get("bookStockLitres").equals(ledgerData.get("bookStockLitres")),
                "Step 1: Tank ledger closingBookStock matches tank bookStockLitres");
        List<Map<String, Object>> ledgerRows = (List<Map<String, Object>>) ledgerData.get("ledger");
        check(ledgerRows != null && !ledgerRows.isEmpty()
                        && ledgerRows.get(0).containsKey("signedLitres")
                        && ledgerRows.get(0).containsKey("runningLitres")
                        && ledgerRows.get(0).get("tankCode") != null
                        && ledgerRows.get(0).get("productName") != null
                        && ledgerData.get("closingBookStock").equals(ledgerRows.get(0).get("runningLitres")),
                "Step 1: Tank ledger rows include signedLitres, chronological runningLitres, tankCode, and productName");

        // 1b. Opening stock rejected on tank that already has movements
        HttpResponse<String> dupSeededOpen = post(client, url + "/inventory/opening-stock", ownerToken,
                Web.map("tankId", seededTankId, "litres", "1000"));
        check(dupSeededOpen.statusCode() == 409, "Step 1: Opening stock rejected (409) when movements already exist");

        // 1c. Create empty tank and post opening stock with frontend {tankId, litres} contract
        HttpResponse<String> newTankRes = post(client, url + "/catalog/tanks", ownerToken, Web.map(
                "stationId", stn1, "code", "TK-S1-OPEN", "name", "Step1 Opening Tank",
                "productId", productId, "capacityLitres", "10000", "deadStockLitres", "200", "status", "ACTIVE"));
        check(newTankRes.statusCode() == 201, "Step 1: Created empty tank for opening stock test");
        String emptyTankId = String.valueOf(dataMap(newTankRes).get("id"));

        check(post(client, url + "/inventory/opening-stock", ownerToken, Web.map("tankId", emptyTankId, "litres", "0")).statusCode() == 422,
                "Step 1: Zero opening stock rejected (422)");
        check(post(client, url + "/inventory/opening-stock", ownerToken, Web.map("tankId", emptyTankId, "litres", "15000")).statusCode() == 422,
                "Step 1: Over-capacity opening stock rejected (422)");

        // Station-scoped staff cannot read ledger or set opening stock on Station 2 tank
        String staffToken = ensureStation1StaffToken(tenantId, stn1, List.of("stock.read", "stock.write"));
        String stn2TankId = String.valueOf(Db.one("SELECT id FROM tanks WHERE station_id = ? LIMIT 1", stn2).get("id"));
        check(get(client, url + "/inventory/tanks/" + stn2TankId + "/ledger", staffToken).statusCode() == 403,
                "Step 1: Station-1 staff blocked (403) from Station-2 tank ledger");
        check(post(client, url + "/inventory/opening-stock", staffToken, Web.map("tankId", stn2TankId, "litres", "500")).statusCode() == 403,
                "Step 1: Station-1 staff blocked (403) from Station-2 opening stock");

        HttpResponse<String> openOk = post(client, url + "/inventory/opening-stock", ownerToken,
                Web.map("tankId", emptyTankId, "litres", "2500.5"));
        check(openOk.statusCode() == 201 && "2500.500".equals(dataMap(openOk).get("bookStockLitres")),
                "Step 1: Opening stock accepts {tankId, litres} and records 2500.500 L");
        check(post(client, url + "/inventory/opening-stock", ownerToken,
                Web.map("tankId", emptyTankId, "litres", "500")).statusCode() == 409,
                "Step 1: Repeated opening stock on same tank rejected (409)");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep2TankAdjustmentsAndTransfers(HttpClient client, String url, String ownerToken) throws Exception {
        // Safeguard 3 unit checks on Domain.signedLitres across all movement types
        check(new java.math.BigDecimal("1000.000").compareTo(Domain.signedLitres("OPENING", "1000.000")) == 0,
                "Step 2: OPENING movement sign is positive");
        check(new java.math.BigDecimal("4000.000").compareTo(Domain.signedLitres("DELIVERY", "4000.000")) == 0,
                "Step 2: DELIVERY movement sign is positive");
        check(new java.math.BigDecimal("-250.500").compareTo(Domain.signedLitres("DISPENSED", "250.500")) == 0,
                "Step 2: DISPENSED movement sign is negative");
        check(new java.math.BigDecimal("-100.000").compareTo(Domain.signedLitres("TRANSFER_OUT", "100.000")) == 0,
                "Step 2: TRANSFER_OUT movement sign is negative");
        check(new java.math.BigDecimal("100.000").compareTo(Domain.signedLitres("TRANSFER_IN", "100.000")) == 0,
                "Step 2: TRANSFER_IN movement sign is positive");
        check(new java.math.BigDecimal("-12.500").compareTo(Domain.signedLitres("ADJUSTMENT", "-12.500")) == 0,
                "Step 2: Negative ADJUSTMENT (loss write-off) sign is negative");
        check(new java.math.BigDecimal("8.250").compareTo(Domain.signedLitres("ADJUSTMENT", "8.250")) == 0,
                "Step 2: Positive ADJUSTMENT (gain) sign is positive");

        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        List<Map<String, Object>> tanks1 = (List<Map<String, Object>>) dataMap(get(client, url + "/inventory/stock?stationId=" + stn1, ownerToken)).get("tanks");
        String msTank1 = String.valueOf(tanks1.get(0).get("id"));
        String msProduct = String.valueOf(tanks1.get(0).get("productId"));
        String hsdTank1 = String.valueOf(tanks1.get(1).get("id"));

        // Negative loss adjustment reduces book stock & preserves reason
        java.math.BigDecimal beforeLoss = Money.d(Domain.bookStock(msTank1));
        HttpResponse<String> lossRes = post(client, url + "/inventory/adjustments", ownerToken,
                Web.map("tankId", msTank1, "quantityLitres", "-12.5", "reason", "Evaporation write-off check"));
        check(lossRes.statusCode() == 201
                        && Money.litres(beforeLoss.subtract(new java.math.BigDecimal("12.5"))).equals(dataMap(lossRes).get("bookStockLitres")),
                "Step 2: Negative adjustment (-12.5 L) subtracts from book stock");
        Map<String, Object> lossMov = Db.one("SELECT note FROM stock_movements WHERE tank_id = ? AND type = 'ADJUSTMENT' ORDER BY created_at DESC, id DESC LIMIT 1", msTank1);
        check("Evaporation write-off check".equals(Db.str(lossMov, "note")),
                "Step 2: Adjustment preserves frontend 'reason' in stock_movements.note");

        // Positive gain adjustment increases book stock
        java.math.BigDecimal beforeGain = Money.d(Domain.bookStock(msTank1));
        HttpResponse<String> gainRes = post(client, url + "/inventory/adjustments", ownerToken,
                Web.map("tankId", msTank1, "quantityLitres", "8.25", "reason", "Calibration gain verification"));
        check(gainRes.statusCode() == 201
                        && Money.litres(beforeGain.add(new java.math.BigDecimal("8.25"))).equals(dataMap(gainRes).get("bookStockLitres")),
                "Step 2: Positive adjustment (+8.25 L) adds to book stock");
        check(post(client, url + "/inventory/adjustments", ownerToken,
                Web.map("tankId", msTank1, "quantityLitres", "0", "reason", "Zero should fail")).statusCode() == 422,
                "Step 2: Zero adjustment rejected (422)");

        // Shift reversal restores positive stock & repeated shift reversal is blocked (409)
        Map<String, Object> closedShift = Db.one("SELECT id FROM shifts WHERE station_id = ? AND status = 'CLOSED' ORDER BY business_date DESC LIMIT 1", stn1);
        String shiftId = Db.str(closedShift, "id");
        Map<String, Object> dispensedMov = Db.one("SELECT tank_id, quantity_litres FROM stock_movements WHERE reference_type = 'SHIFT' AND reference_id = ? AND type = 'DISPENSED' LIMIT 1", shiftId);
        String revTankId = Db.str(dispensedMov, "tank_id");
        java.math.BigDecimal revTankBefore = Domain.bookStock(revTankId);
        java.math.BigDecimal dispensedQty = Money.d(dispensedMov.get("quantity_litres")).abs();
        HttpResponse<String> rev1 = post(client, url + "/shifts/" + shiftId + "/reverse", ownerToken,
                Web.map("reason", "Meter entry correction test"));
        check(rev1.statusCode() == 200
                        && Money.litres(revTankBefore.add(dispensedQty)).equals(Money.litres(Domain.bookStock(revTankId))),
                "Step 2: Shift reversal adds back exact dispensed litres");
        HttpResponse<String> rev2 = post(client, url + "/shifts/" + shiftId + "/reverse", ownerToken,
                Web.map("reason", "Duplicate shift reversal attempt"));
        check(rev2.statusCode() == 409
                        && Money.litres(revTankBefore.add(dispensedQty)).equals(Money.litres(Domain.bookStock(revTankId))),
                "Step 2: Repeated shift reversal rejected (409) without double-inflating stock");

        // Transfers: reject cross-product, reject cross-station unauthorized staff, preserve reason on valid same-product transfer
        check(post(client, url + "/inventory/transfers", ownerToken,
                Web.map("fromTankId", msTank1, "toTankId", hsdTank1, "quantityLitres", "50", "reason", "Wrong fuel grade")).statusCode() == 422,
                "Step 2: Transfer between different fuel products rejected (422)");

        String staffToken = ensureStation1StaffToken(tenantId, stn1, List.of("stock.read", "stock.write"));
        String stn2MsTank = Db.str(Db.one("SELECT id FROM tanks WHERE station_id = ? AND product_id = ? LIMIT 1", stn2, msProduct), "id");
        check(post(client, url + "/inventory/transfers", staffToken,
                Web.map("fromTankId", stn2MsTank, "toTankId", msTank1, "quantityLitres", "50", "reason", "Unauthorized cross-station transfer")).statusCode() == 403,
                "Step 2: Station-1 staff blocked (403) from transferring out of Station-2 tank");
        check(post(client, url + "/inventory/adjustments", staffToken,
                Web.map("tankId", stn2MsTank, "quantityLitres", "-5", "reason", "Unauthorized cross-station adjust")).statusCode() == 403,
                "Step 2: Station-1 staff blocked (403) from adjusting Station-2 tank");

        // Valid same-station, same-product transfer (msTank1 -> TK-S1-OPEN created in Step 1)
        String auxMsTank = Db.str(Db.one("SELECT id FROM tanks WHERE station_id = ? AND code = 'TK-S1-OPEN'", stn1), "id");
        java.math.BigDecimal fromBefore = Domain.bookStock(msTank1);
        java.math.BigDecimal toBefore = Domain.bookStock(auxMsTank);
        HttpResponse<String> xferOk = post(client, url + "/inventory/transfers", ownerToken,
                Web.map("fromTankId", msTank1, "toTankId", auxMsTank, "quantityLitres", "125.5", "reason", "Tank maintenance decant"));
        check(xferOk.statusCode() == 201
                        && Money.litres(fromBefore.subtract(new java.math.BigDecimal("125.5"))).equals(Money.litres(Domain.bookStock(msTank1)))
                        && Money.litres(toBefore.add(new java.math.BigDecimal("125.5"))).equals(Money.litres(Domain.bookStock(auxMsTank))),
                "Step 2: Valid same-product transfer moves exact stock out and in atomically");
        Map<String, Object> xferNote = Db.one("SELECT note FROM stock_movements WHERE tank_id = ? AND type = 'TRANSFER_IN' ORDER BY created_at DESC LIMIT 1", auxMsTank);
        check("Tank maintenance decant".equals(Db.str(xferNote, "note")),
                "Step 2: Transfer preserves frontend 'reason' in stock_movements.note");

        // Water dip surfaced in tankSummary + tankCode/productName surfaced in /inventory/movements
        post(client, url + "/catalog/tanks/" + msTank1 + "/measurements", ownerToken,
                Web.map("measuredLitres", "14100", "waterDipCm", "2.4", "method", "MANUAL"));
        List<Map<String, Object>> refreshedTanks = (List<Map<String, Object>>) dataMap(get(client, url + "/inventory/stock?stationId=" + stn1, ownerToken)).get("tanks");
        Map<String, Object> msCard = refreshedTanks.stream().filter(t -> msTank1.equals(t.get("id"))).findFirst().orElseThrow();
        check("2.4".equals(String.valueOf(msCard.get("lastWaterDipCm"))),
                "Step 2: Tank summary includes lastWaterDipCm");

        List<Map<String, Object>> movRows = (List<Map<String, Object>>) Json.object(Json.parse(
                get(client, url + "/inventory/movements?stationId=" + stn1, ownerToken).body())).get("data");
        check(!movRows.isEmpty() && movRows.get(0).get("tankCode") != null && movRows.get(0).get("productName") != null,
                "Step 2: GET /inventory/movements includes tankCode and productName");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep3Deliveries(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        String supplierId = Db.str(Db.one("SELECT id FROM suppliers WHERE tenant_id = ? LIMIT 1", tenantId), "id");
        Map<String, Object> auxTank = Db.one("SELECT id, product_id FROM tanks WHERE station_id = ? AND code = 'TK-S1-OPEN'", stn1);
        String tankId = Db.str(auxTank, "id");
        String productId = Db.str(auxTank, "product_id");
        String wrongProductId = Db.str(Db.one("SELECT id FROM fuel_products WHERE tenant_id = ? AND id != ? LIMIT 1", tenantId, productId), "id");

        // Reject product mismatch against tank
        check(post(client, url + "/inventory/deliveries", ownerToken, Web.map(
                "stationId", stn1, "supplierId", supplierId, "invoiceNumber", "INV-BAD-PROD",
                "invoiceDate", Clock.todayIst(), "productId", wrongProductId, "tankId", tankId,
                "quantityLitres", "500", "ratePerLitre", "95.00")).statusCode() == 422,
                "Step 3: Delivery with mismatched tank/product rejected (422)");

        // 3a. Create with receiveNow=true + actual receivedLitres + decantation metadata
        java.math.BigDecimal stockBeforeNow = Domain.bookStock(tankId);
        HttpResponse<String> rcvNowRes = post(client, url + "/inventory/deliveries", ownerToken, Web.map(
                "stationId", stn1,
                "supplierId", supplierId,
                "invoiceNumber", "INV-S3-NOW-01",
                "invoiceDate", Clock.todayIst(),
                "productId", productId,
                "tankId", tankId,
                "quantityLitres", "1000",
                "ratePerLitre", "95.50",
                "taxAmount", "450.00",
                "vehicleNumber", "MH12TT4455",
                "driverName", "Ramesh Patil",
                "ttSealNumbers", "SEAL-101,SEAL-102",
                "sampleSealNo", "SMP-55",
                "densityInvoice", "744.0",
                "densityObserved", "736.0",
                "tempObservedC", "27.0",
                "receiveNow", true,
                "receivedLitres", "994.250"));
        check(rcvNowRes.statusCode() == 201, "Step 3: Delivery with receiveNow=true created (201)");
        Map<String, Object> rcvNowData = dataMap(rcvNowRes);
        check("95500.00".equals(rcvNowData.get("taxableAmount")) && "95950.00".equals(rcvNowData.get("totalAmount")),
                "Step 3: Delivery computes taxableAmount (95500.00) and totalAmount (95950.00) server-side");
        check("RECEIVED".equals(rcvNowData.get("status"))
                        && "994.250".equals(rcvNowData.get("receivedLitres"))
                        && "-5.750".equals(rcvNowData.get("discrepancyLitres"))
                        && "MH12TT4455".equals(rcvNowData.get("vehicleNumber"))
                        && "SEAL-101,SEAL-102".equals(rcvNowData.get("ttSealNumbers"))
                        && rcvNowData.get("densityAt15c") != null,
                "Step 3: receiveNow=true marks RECEIVED, stores decantation fields, and computes discrepancyLitres");
        check(Money.litres(stockBeforeNow.add(new java.math.BigDecimal("994.250"))).equals(Money.litres(Domain.bookStock(tankId))),
                "Step 3: receiveNow=true adds actual receivedLitres (994.250 L) to tank book stock atomically");

        // Duplicate receive on already RECEIVED delivery must be rejected (409) and never re-add stock
        String rcvNowId = String.valueOf(rcvNowData.get("id"));
        check(post(client, url + "/inventory/deliveries/" + rcvNowId + "/receive", ownerToken,
                Web.map("receivedLitres", "994.250")).statusCode() == 409
                        && Money.litres(stockBeforeNow.add(new java.math.BigDecimal("994.250"))).equals(Money.litres(Domain.bookStock(tankId))),
                "Step 3: Receiving an already-RECEIVED delivery rejected (409) without duplicating stock");

        // 3b. Draft delivery -> receive with shortage & decantation fields -> duplicate receive blocked
        java.math.BigDecimal stockBeforeDraft = Domain.bookStock(tankId);
        HttpResponse<String> draftRes = post(client, url + "/inventory/deliveries", ownerToken, Web.map(
                "stationId", stn1, "supplierId", supplierId, "invoiceNumber", "INV-S3-DRAFT-02",
                "invoiceDate", Clock.todayIst(), "productId", productId, "tankId", tankId,
                "quantityLitres", "800", "ratePerLitre", "94.00", "taxAmount", "0", "receiveNow", false));
        check(draftRes.statusCode() == 201 && "DRAFT".equals(dataMap(draftRes).get("status"))
                        && Money.litres(stockBeforeDraft).equals(Money.litres(Domain.bookStock(tankId))),
                "Step 3: Draft delivery does not move tank stock");
        String draftId = String.valueOf(dataMap(draftRes).get("id"));

        // Cross-station staff cannot receive Station-1 delivery if assigned only to Station-2
        String stn2OnlyStaff = ensureStation1StaffToken(tenantId, stn2, List.of("deliveries.read", "deliveries.write"));
        check(post(client, url + "/inventory/deliveries/" + draftId + "/receive", stn2OnlyStaff,
                Web.map("receivedLitres", "798.5")).statusCode() == 403,
                "Step 3: Staff without station access blocked (403) from receiving delivery");

        HttpResponse<String> rcvDraft = post(client, url + "/inventory/deliveries/" + draftId + "/receive", ownerToken, Web.map(
                "receivedLitres", "798.500", "dipBeforeCm", "42.0", "dipAfterCm", "78.5",
                "chamberDetails", "C1 800L", "notes", "Shortage 1.5L noted"));
        Map<String, Object> rcvDraftData = dataMap(rcvDraft);
        check(rcvDraft.statusCode() == 200
                        && "RECEIVED".equals(rcvDraftData.get("status"))
                        && "798.500".equals(rcvDraftData.get("receivedLitres"))
                        && "-1.500".equals(rcvDraftData.get("discrepancyLitres"))
                        && "42.0".equals(rcvDraftData.get("dipBeforeCm"))
                        && Money.litres(stockBeforeDraft.add(new java.math.BigDecimal("798.500"))).equals(Money.litres(Domain.bookStock(tankId))),
                "Step 3: POST /receive records actual receivedLitres (798.500 L), dips, and discrepancyLitres");
        check(post(client, url + "/inventory/deliveries/" + draftId + "/receive", ownerToken,
                Web.map("receivedLitres", "798.500")).statusCode() == 409,
                "Step 3: Second POST /receive rejected (409)");

        // 3c. Disputed delivery cannot be received + list joins/status filter work
        HttpResponse<String> dispDraft = post(client, url + "/inventory/deliveries", ownerToken, Web.map(
                "stationId", stn1, "supplierId", supplierId, "invoiceNumber", "INV-S3-DISP-03",
                "invoiceDate", Clock.todayIst(), "productId", productId, "tankId", tankId,
                "quantityLitres", "500", "ratePerLitre", "94.00", "receiveNow", false));
        String dispId = String.valueOf(dataMap(dispDraft).get("id"));
        check(post(client, url + "/inventory/deliveries/" + dispId + "/dispute", ownerToken,
                Web.map("reason", "Broken compartment seal")).statusCode() == 200,
                "Step 3: Draft delivery marked DISPUTED");
        check(post(client, url + "/inventory/deliveries/" + dispId + "/receive", ownerToken,
                Web.map("receivedLitres", "500")).statusCode() == 409,
                "Step 3: Disputed delivery cannot be received (409)");

        Map<String, Object> filteredBody = Json.object(Json.parse(
                get(client, url + "/inventory/deliveries?stationId=" + stn1 + "&status=DISPUTED&search=INV-S3-DISP", ownerToken).body()));
        List<Map<String, Object>> filteredRows = (List<Map<String, Object>>) filteredBody.get("data");
        check(filteredRows.size() == 1
                        && "DISPUTED".equals(filteredRows.get(0).get("status"))
                        && filteredRows.get(0).get("supplierName") != null
                        && filteredRows.get(0).get("tankCode") != null
                        && filteredBody.get("meta") != null,
                "Step 3: GET /inventory/deliveries supports status/search filters, joins, and pagination meta");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep4PaymentsAndReversal(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        Map<String, Object> cust = Db.one("SELECT id, balance FROM customers WHERE tenant_id = ? LIMIT 1", tenantId);
        String customerId = Db.str(cust, "id");
        java.math.BigDecimal initialBalance = Money.d(cust.get("balance"));

        // 4a. Record UPI receipt without settlementRef -> POSTED; reduces customer balance and ledger closingBalance
        HttpResponse<String> payRes = post(client, url + "/finance/payments", ownerToken, Web.map(
                "stationId", stn1, "direction", "RECEIPT", "partyType", "CUSTOMER", "customerId", customerId,
                "amount", "1500.00", "mode", "UPI", "reference", "UTR-S4-1001", "notes", "UPI collection"));
        check(payRes.statusCode() == 201, "Step 4: UPI customer receipt created (201)");
        Map<String, Object> payData = dataMap(payRes);
        String paymentId = String.valueOf(payData.get("id"));
        check("POSTED".equals(payData.get("status")), "Step 4: Unsettled UPI payment starts in POSTED status");

        java.math.BigDecimal afterPayBalance = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
        check(Money.money(initialBalance.subtract(new java.math.BigDecimal("1500.00"))).equals(Money.money(afterPayBalance)),
                "Step 4: Customer balance reduced by ₹1500.00 upon receipt");

        // 4b. Settle persists settlementRef & rejects repeat settlement
        check(patch(client, url + "/finance/payments/" + paymentId + "/settle", ownerToken, Web.map("settlementRef", "")).statusCode() == 422,
                "Step 4: Blank settlementRef rejected (422)");
        HttpResponse<String> settleRes = patch(client, url + "/finance/payments/" + paymentId + "/settle", ownerToken,
                Web.map("settlementRef", "BANK-SETTLE-9988"));
        check(settleRes.statusCode() == 200
                        && "SETTLED".equals(dataMap(settleRes).get("status"))
                        && "BANK-SETTLE-9988".equals(dataMap(settleRes).get("settlementRef")),
                "Step 4: PATCH /settle persists settlementRef and transitions to SETTLED");
        check(patch(client, url + "/finance/payments/" + paymentId + "/settle", ownerToken,
                Web.map("settlementRef", "BANK-SETTLE-9988")).statusCode() == 409,
                "Step 4: Re-settling already SETTLED payment rejected (409)");

        // 4c. Cross-station staff blocked from reversing Station-1 payment
        String stn2OnlyStaff = ensureStation1StaffToken(tenantId, stn2, List.of("payments.read", "payments.write"));
        check(post(client, url + "/finance/payments/" + paymentId + "/reverse", stn2OnlyStaff,
                Web.map("reason", "Wrong customer")).statusCode() == 403,
                "Step 4: Station-2 staff blocked (403) from reversing Station-1 payment");

        // 4d. Atomic reversal restores customer balance AND credit ledger closingBalance; repeat reversal rejected (409)
        HttpResponse<String> revRes = post(client, url + "/finance/payments/" + paymentId + "/reverse", ownerToken,
                Web.map("reason", "Bounced / wrong UTR"));
        check(revRes.statusCode() == 200 && "REVERSED".equals(dataMap(revRes).get("status")),
                "Step 4: POST /reverse marks payment REVERSED");

        java.math.BigDecimal restoredBalance = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
        check(Money.money(initialBalance).equals(Money.money(restoredBalance)),
                "Step 4: Payment reversal restores customer balance to exact pre-payment amount");

        Map<String, Object> ledgerData = dataMap(get(client, url + "/finance/customer-ledger?customerId=" + customerId, ownerToken));
        List<Map<String, Object>> ledgerRows = (List<Map<String, Object>>) ledgerData.get("ledger");
        boolean hasReversalEntry = ledgerRows.stream().anyMatch(e -> "PAYMENT_REVERSAL".equals(e.get("type")) && paymentId.equals(e.get("paymentId")));
        check(Money.money(initialBalance).equals(ledgerData.get("closingBalance")) && hasReversalEntry,
                "Step 4: Customer ledger records PAYMENT_REVERSAL and closingBalance matches customers.balance");

        // Repeat reversal must fail with 409 and leave balance untouched
        check(post(client, url + "/finance/payments/" + paymentId + "/reverse", ownerToken,
                Web.map("reason", "Duplicate click")).statusCode() == 409
                        && Money.money(initialBalance).equals(Money.money(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"))),
                "Step 4: Repeated payment reversal rejected (409) without double-inflating customer balance");

        // 4e. Payments register filters (mode + date range) work
        String today = Clock.todayIst();
        List<Map<String, Object>> upiToday = dataList(get(client,
                url + "/finance/payments?stationId=" + stn1 + "&from=" + today + "&to=" + today + "&mode=UPI", ownerToken));
        check(upiToday.stream().anyMatch(p -> paymentId.equals(p.get("id")) && p.get("createdByName") != null),
                "Step 4: GET /finance/payments supports from/to/mode filters and createdByName join");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep5Reconciliation(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));
        String bizDate = "2026-10-15";
        String midDayIso = "2026-10-15T06:30:00.000Z"; // 12:00 IST on 2026-10-15

        String customerId = Db.str(Db.one("SELECT id FROM customers WHERE tenant_id = ? LIMIT 1", tenantId), "id");
        String supplierId = Db.str(Db.one("SELECT id FROM suppliers WHERE tenant_id = ? LIMIT 1", tenantId), "id");
        String lubeId = Db.str(Db.one("SELECT id FROM lube_products WHERE tenant_id = ? LIMIT 1", tenantId), "id");

        // 1) Settled shift on stn2 (total_value=5000, opening_cash=1000, UPI=1500, CREDIT=500 -> cashSales=3000)
        String settledShiftId = Clock.newId("shf");
        Db.exec("INSERT INTO shifts (id, tenant_id, station_id, shift_number, business_date, opened_at, closed_at, opening_cash, closing_cash, total_litres, total_value, status, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?, '1000.00', '4000.00', '50.000', '5000.00', 'CLOSED', ?, ?)",
                settledShiftId, tenantId, stn2, bizDate, midDayIso, midDayIso, midDayIso, midDayIso);
        Db.exec("INSERT INTO shift_settlements (id, shift_id, tenant_id, station_id, mode, expected_amount, received_amount, difference_amount, created_at) VALUES (?, ?, ?, ?, 'CASH', '4000.00', '4000.00', '0.00', ?)",
                Clock.newId("stl"), settledShiftId, tenantId, stn2, midDayIso);
        Db.exec("INSERT INTO shift_settlements (id, shift_id, tenant_id, station_id, mode, expected_amount, received_amount, difference_amount, created_at) VALUES (?, ?, ?, ?, 'UPI', '1500.00', '1500.00', '0.00', ?)",
                Clock.newId("stl"), settledShiftId, tenantId, stn2, midDayIso);
        Db.exec("INSERT INTO shift_settlements (id, shift_id, tenant_id, station_id, mode, expected_amount, received_amount, difference_amount, created_at) VALUES (?, ?, ?, ?, 'CREDIT', '500.00', '500.00', '0.00', ?)",
                Clock.newId("stl"), settledShiftId, tenantId, stn2, midDayIso);

        // 2) Totalizer-only closed shift WITHOUT shift_settlements (must NOT inflate cashSales; goes to shiftAggregateValue)
        Db.exec("INSERT INTO shifts (id, tenant_id, station_id, shift_number, business_date, opened_at, closed_at, opening_cash, total_litres, total_value, status, created_at, updated_at) VALUES (?, ?, ?, 2, ?, ?, ?, '0.00', '22.000', '2200.00', 'CLOSED', ?, ?)",
                Clock.newId("shf"), tenantId, stn2, bizDate, midDayIso, midDayIso, midDayIso, midDayIso);

        // 3) Reversed shift WITH shift_settlements (must be completely excluded!)
        String revShiftId = Clock.newId("shf");
        Db.exec("INSERT INTO shifts (id, tenant_id, station_id, shift_number, business_date, opened_at, closed_at, opening_cash, total_litres, total_value, status, created_at, updated_at) VALUES (?, ?, ?, 3, ?, ?, ?, '0.00', '10.000', '999.00', 'REVERSED', ?, ?)",
                revShiftId, tenantId, stn2, bizDate, midDayIso, midDayIso, midDayIso, midDayIso);
        Db.exec("INSERT INTO shift_settlements (id, shift_id, tenant_id, station_id, mode, expected_amount, received_amount, difference_amount, created_at) VALUES (?, ?, ?, ?, 'CASH', '999.00', '999.00', '0.00', ?)",
                Clock.newId("stl"), revShiftId, tenantId, stn2, midDayIso);

        // 4) Lube counter sales: ₹300 CASH + ₹200 CARD
        Db.exec("INSERT INTO lube_movements (id, tenant_id, station_id, product_id, type, quantity, rate, amount, payment_mode, occurred_at, created_at) VALUES (?, ?, ?, ?, 'SALE', '-1', '300.00', '300.00', 'CASH', ?, ?)",
                Clock.newId("lum"), tenantId, stn2, lubeId, midDayIso, midDayIso);
        Db.exec("INSERT INTO lube_movements (id, tenant_id, station_id, product_id, type, quantity, rate, amount, payment_mode, occurred_at, created_at) VALUES (?, ?, ?, ?, 'SALE', '-1', '200.00', '200.00', 'CARD', ?, ?)",
                Clock.newId("lum"), tenantId, stn2, lubeId, midDayIso, midDayIso);

        // 5) Receipts & Payouts via API (plus one reversed receipt that must NOT be counted)
        post(client, url + "/finance/payments", ownerToken, Web.map(
                "stationId", stn2, "direction", "RECEIPT", "partyType", "CUSTOMER", "customerId", customerId,
                "amount", "400.00", "mode", "CASH", "occurredAt", midDayIso));
        post(client, url + "/finance/payments", ownerToken, Web.map(
                "stationId", stn2, "direction", "RECEIPT", "partyType", "CUSTOMER", "customerId", customerId,
                "amount", "600.00", "mode", "UPI", "occurredAt", midDayIso));
        HttpResponse<String> revPay = post(client, url + "/finance/payments", ownerToken, Web.map(
                "stationId", stn2, "direction", "RECEIPT", "partyType", "CUSTOMER", "customerId", customerId,
                "amount", "999.00", "mode", "CASH", "occurredAt", midDayIso));
        post(client, url + "/finance/payments/" + dataMap(revPay).get("id") + "/reverse", ownerToken, Web.map("reason", "Test reversal"));

        post(client, url + "/finance/payments", ownerToken, Web.map(
                "stationId", stn2, "direction", "PAYOUT", "partyType", "SUPPLIER", "supplierId", supplierId,
                "amount", "250.00", "mode", "CASH", "occurredAt", midDayIso));
        post(client, url + "/finance/payments", ownerToken, Web.map(
                "stationId", stn2, "direction", "PAYOUT", "partyType", "SUPPLIER", "supplierId", supplierId,
                "amount", "350.00", "mode", "BANK", "occurredAt", midDayIso));

        // 6) Expenses via API: ₹150 CASH + ₹50 UPI
        post(client, url + "/finance/expenses", ownerToken, Web.map(
                "stationId", stn2, "description", "Tea & snacks", "amount", "150.00", "paidVia", "CASH", "occurredAt", midDayIso));
        post(client, url + "/finance/expenses", ownerToken, Web.map(
                "stationId", stn2, "description", "Stationery UPI", "amount", "50.00", "paidVia", "UPI", "occurredAt", midDayIso));

        Map<String, Object> reconGet = dataMap(get(client, url + "/finance/reconciliation?stationId=" + stn2 + "&businessDate=" + bizDate, ownerToken));
        Map<String, Object> exp = Json.object(reconGet.get("expected"));
        check("3300.00".equals(exp.get("cashSales")) && "1700.00".equals(exp.get("digitalSales")),
                "Step 5: Reconciliation computes cashSales (3300.00) and digitalSales (1700.00) without double-counting");
        check("400.00".equals(exp.get("cashReceipts")) && "600.00".equals(exp.get("digitalReceipts"))
                        && "250.00".equals(exp.get("cashPayouts")) && "350.00".equals(exp.get("digitalPayouts"))
                        && "150.00".equals(exp.get("cashExpenses")) && "50.00".equals(exp.get("digitalExpenses")),
                "Step 5: Reconciliation computes receipts, payouts, and expenses excluding reversed payments");
        check("3300.00".equals(exp.get("cash")) && "1900.00".equals(exp.get("digital"))
                        && "2200.00".equals(exp.get("shiftAggregateValue")) && "500.00".equals(exp.get("creditSales")),
                "Step 5: Expected cash (3300.00), digital (1900.00), unsplit shiftAggregateValue (2200.00), and creditSales (500.00) match formula");

        // Offsetting cash shortage (-100) + digital excess (+100) MUST still be VARIANCE, never masked as BALANCED
        HttpResponse<String> offsetSave = post(client, url + "/finance/reconciliation", ownerToken, Web.map(
                "stationId", stn2, "businessDate", bizDate, "cashCounted", "3200.00", "digitalSettled", "2000.00", "notes", "Offsetting mismatch"));
        Map<String, Object> offsetRow = dataMap(offsetSave);
        check(offsetSave.statusCode() == 201
                        && "VARIANCE".equals(offsetRow.get("status"))
                        && "-100.00".equals(offsetRow.get("cashVariance"))
                        && "100.00".equals(offsetRow.get("digitalVariance"))
                        && "-100.00".equals(offsetRow.get("variance")),
                "Step 5: Offsetting cash (-100) and digital (+100) variances still flag status=VARIANCE");

        // Re-save same business date with digital-only shortage (-80) -> insert-only revision
        Map<String, Object> digShortRow = dataMap(post(client, url + "/finance/reconciliation", ownerToken, Web.map(
                "stationId", stn2, "businessDate", bizDate, "cashCounted", "3300.00", "digitalSettled", "1820.00", "notes", "UPI short")));
        check("VARIANCE".equals(digShortRow.get("status"))
                        && "0.00".equals(digShortRow.get("cashVariance"))
                        && "-80.00".equals(digShortRow.get("digitalVariance"))
                        && "-80.00".equals(digShortRow.get("variance")),
                "Step 5: Digital-only shortage flags status=VARIANCE with exact digitalVariance (-80.00)");

        // Approve variance -> status=APPROVED; duplicate approval -> 409
        String recId = String.valueOf(digShortRow.get("id"));
        check("APPROVED".equals(dataMap(post(client, url + "/finance/reconciliation/" + recId + "/approve", ownerToken,
                Web.map("note", "Reviewed bank delay"))).get("status")),
                "Step 5: Variance reconciliation approved -> status=APPROVED");
        check(post(client, url + "/finance/reconciliation/" + recId + "/approve", ownerToken,
                Web.map("note", "Again")).statusCode() == 409,
                "Step 5: Duplicate reconciliation approval rejected (409)");

        // Re-save approved date with exact balanced counts -> new revision requires a reason.
        Map<String, Object> balRow = dataMap(post(client, url + "/finance/reconciliation", ownerToken, Web.map(
                "stationId", stn2, "businessDate", bizDate, "cashCounted", "3300.00", "digitalSettled", "1900.00", "notes", "All settled",
                "revisionReason", "Bank settlement confirmed after approval")));
        check("BALANCED".equals(balRow.get("status")) && "0.00".equals(balRow.get("variance")),
                "Step 5: Matching cash and digital counts produce status=BALANCED and 0.00 variance");

        Map<String, Object> reconAfter = dataMap(get(client, url + "/finance/reconciliation?stationId=" + stn2 + "&businessDate=" + bizDate, ownerToken));
        List<Map<String, Object>> history = (List<Map<String, Object>>) reconAfter.get("history");
        long sameDayCount = history.stream().filter(h -> bizDate.equals(String.valueOf(h.get("businessDate")).substring(0, 10))).count();
        check(sameDayCount == 3, "Step 5: Re-saving same business date preserves insert-only revision history");

        // Cross-station staff blocked from Station-2 reconciliation
        String stn1OnlyStaff = ensureStation1StaffToken(tenantId, stn1, List.of("reconciliation.read", "reconciliation.write"));
        check(get(client, url + "/finance/reconciliation?stationId=" + stn2 + "&businessDate=" + bizDate, stn1OnlyStaff).statusCode() == 403,
                "Step 5: Station-1 staff blocked (403) from Station-2 reconciliation");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep6Lubricants(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        // 6a. Create & PATCH lubricant SKU (brand, packSize, gstRatePct, purchaseRate, saleRate, hsn)
        HttpResponse<String> skuRes = post(client, url + "/lubricants", ownerToken, Web.map(
                "code", "LUB-S6-4T", "name", "Servo 4T Zoom", "brand", "Servo",
                "packSize", "900 mL", "hsn", "27101980", "gstRatePct", "18", "purchaseRate", "320", "saleRate", "400"));
        check(skuRes.statusCode() == 201, "Step 6: Lubricant SKU created (201)");
        String lubeId = String.valueOf(dataMap(skuRes).get("id"));

        HttpResponse<String> patchRes = patch(client, url + "/lubricants/" + lubeId, ownerToken, Web.map(
                "name", "Castrol Activ 4T", "brand", "Castrol", "packSize", "1 L",
                "gstRatePct", "18", "purchaseRate", "480", "saleRate", "590"));
        Map<String, Object> patched = dataMap(patchRes);
        check(patchRes.statusCode() == 200
                        && "Castrol".equals(patched.get("brand"))
                        && "1 L".equals(patched.get("packSize"))
                        && "480.00".equals(patched.get("purchaseRate"))
                        && "590.00".equals(patched.get("saleRate")),
                "Step 6: PATCH /lubricants/:id persists brand, packSize, purchaseRate, and saleRate");

        // 6b. Selling before stock exists must fail with 422
        check(post(client, url + "/lubricants/movements", ownerToken, Web.map(
                "stationId", stn1, "productId", lubeId, "type", "SALE", "quantity", "2", "paymentMode", "CASH")).statusCode() == 422,
                "Step 6: Selling out-of-stock lubricant rejected (422)");

        // 6c. Purchase 10 packs @ ₹480 -> amount = ₹4800.00
        HttpResponse<String> purRes = post(client, url + "/lubricants/movements", ownerToken, Web.map(
                "stationId", stn1, "productId", lubeId, "type", "PURCHASE", "quantity", "10", "rate", "480"));
        check(purRes.statusCode() == 201 && "4800.00".equals(dataMap(purRes).get("amount")),
                "Step 6: Lubricant PURCHASE computes amount = qty * rate (4800.00)");

        // 6d. Credit sale without customerId rejected (422); with customerId posts atomically to customers.balance + credit_entries
        check(post(client, url + "/lubricants/movements", ownerToken, Web.map(
                "stationId", stn1, "productId", lubeId, "type", "SALE", "quantity", "2", "paymentMode", "CREDIT")).statusCode() == 422,
                "Step 6: Credit lubricant sale without customerId rejected (422)");

        Map<String, Object> cust = Db.one("SELECT id, balance FROM customers WHERE tenant_id = ? LIMIT 1", tenantId);
        String customerId = Db.str(cust, "id");
        java.math.BigDecimal balBefore = Money.d(cust.get("balance"));

        // Sell 2 packs @ default saleRate 590.00 -> gross = ₹1180.00; 18% inclusive GST = 1180 * 18 / 118 = ₹180.00!
        HttpResponse<String> saleRes = post(client, url + "/lubricants/movements", ownerToken, Web.map(
                "stationId", stn1, "productId", lubeId, "type", "SALE", "quantity", "2",
                "paymentMode", "CREDIT", "customerId", customerId, "vehicleNumber", "MH12LU9900"));
        Map<String, Object> saleData = dataMap(saleRes);
        check(saleRes.statusCode() == 201
                        && "1180.00".equals(saleData.get("amount"))
                        && "180.00".equals(saleData.get("gstAmount"))
                        && "-2".equals(saleData.get("quantity")),
                "Step 6: Lubricant SALE computes gross amount (1180.00) and inclusive 18% GST (180.00)");

        java.math.BigDecimal balAfter = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
        check(Money.money(balBefore.add(new java.math.BigDecimal("1180.00"))).equals(Money.money(balAfter)),
                "Step 6: Credit lubricant sale increments customers.balance by ₹1180.00 atomically");

        Map<String, Object> ledger = dataMap(get(client, url + "/finance/customer-ledger?customerId=" + customerId, ownerToken));
        check(Money.money(balAfter).equals(ledger.get("closingBalance")),
                "Step 6: Credit lubricant sale appears in customer credit ledger with matching closingBalance");

        List<Map<String, Object>> movList = dataList(get(client, url + "/lubricants/movements?stationId=" + stn1 + "&page=1&pageSize=10", ownerToken));
        check(movList.stream().anyMatch(m -> saleData.get("id").equals(m.get("id")) && "180.00".equals(m.get("gstAmount"))),
                "Step 6: GET /lubricants/movements returns accurate inclusive gstAmount (180.00)");

        HttpResponse<String> gstCsv = get(client, url + "/reports/export/lubricants-gst.csv?stationId=" + stn1 + "&month=" + Clock.todayIst().substring(0, 7), ownerToken);
        check(gstCsv.statusCode() == 200 && gstCsv.body().contains("1000.00,90.00,90.00,180.00"),
                "Step 6: lubricants-gst.csv exports taxable (1000.00), CGST (90.00), SGST (90.00), and GST (180.00)");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep7Density(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        String petrolTankId = Db.str(Db.one(
                "SELECT t.id FROM tanks t JOIN fuel_products p ON p.id = t.product_id WHERE t.station_id = ? AND p.category = 'PETROL' LIMIT 1", stn1), "id");
        String dieselTankId = Db.str(Db.one(
                "SELECT t.id FROM tanks t JOIN fuel_products p ON p.id = t.product_id WHERE t.station_id = ? AND p.category = 'DIESEL' LIMIT 1", stn1), "id");

        // Reject g/cm³ unit error (0.742 instead of 742.0 kg/m³)
        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "observedDensity", "0.742", "observedTempC", "28.0")).statusCode() == 422,
                "Step 7: Density in g/cm³ (0.742) rejected with 422 requiring kg/m³");

        // Petrol in-spec: 738.8 kg/m³ @ 31.0°C -> ASTM D1250 Table 53B = 753.0 kg/m³ (IS 2796 720–775)
        HttpResponse<String> petRes = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "738.8", "observedTempC", "31.0", "source", "NOZZLE", "note", "Morning check"));
        Map<String, Object> petData = dataMap(petRes);
        check(petRes.statusCode() == 201
                        && "738.8".equals(petData.get("observedDensity"))
                        && "31.0".equals(petData.get("observedTempC"))
                        && "753.0".equals(petData.get("densityAt15c"))
                        && "720".equals(petData.get("specMin"))
                        && "775".equals(petData.get("specMax"))
                        && ((Number) petData.get("withinSpec")).intValue() == 1
                        && String.valueOf(petData.get("specificationUsed")).contains("Petrol screening band (720–775 kg/m³)"),
                "Step 7: Petrol 738.8 @ 31.0°C -> 753.0 kg/m³ @ 15°C (ASTM D1250 Table 53B, IS 2796 720–775 withinSpec=1)");

        // Diesel in-spec: 830.0 kg/m³ @ 30.5°C -> ASTM D1250 Table 53B = 840.7 kg/m³ (IS 1460:2017 810–845)
        HttpResponse<String> dslRes = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "830.0", "observedTempC", "30.5", "source", "TANK"));
        Map<String, Object> dslData = dataMap(dslRes);
        check(dslRes.statusCode() == 201
                        && "840.7".equals(dslData.get("densityAt15c"))
                        && "810".equals(dslData.get("specMin"))
                        && "845".equals(dslData.get("specMax"))
                        && ((Number) dslData.get("withinSpec")).intValue() == 1
                        && String.valueOf(dslData.get("specificationUsed")).contains("Diesel screening band (810–845 kg/m³)"),
                "Step 7: Diesel 830.0 @ 30.5°C -> 840.7 kg/m³ @ 15°C (ASTM D1250 Table 53B, IS 1460:2017 810–845 withinSpec=1)");

        // Adulterated / out-of-spec petrol (780.0 @ 28.0°C -> > 775 kg/m³) MUST record withinSpec=0 even if client sends withinSpec=true
        HttpResponse<String> badPet = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "780.0", "observedTempC", "28.0", "withinSpec", true));
        Map<String, Object> badData = dataMap(badPet);
        check(badPet.statusCode() == 201 && ((Number) badData.get("withinSpec")).intValue() == 0,
                "Step 7: Out-of-spec petrol flagged withinSpec=0 server-side ignoring spoofed client withinSpec=true");

        // Station-2 staff cannot log density on Station-1 tank
        String stn2OnlyStaff = ensureStation1StaffToken(tenantId, stn2, List.of("density.read", "density.write"));
        check(post(client, url + "/density", stn2OnlyStaff, Web.map(
                "tankId", petrolTankId, "observedDensity", "740.0", "observedTempC", "25.0")).statusCode() == 403,
                "Step 7: Station-2 staff blocked (403) from logging density on Station-1 tank");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep8AttendanceAndDsr(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        // Assign isolated staff user strictly to stn1 so we can test cross-station assignment check too
        ensureStation1StaffToken(tenantId, stn1, List.of("attendance.read", "attendance.write"));
        String staffId = Db.str(Db.one("SELECT id FROM users WHERE email = 's1.staff.isolated@sunrisefuels.in'"), "id");

        // Marking stn1-only staff on stn2 must be rejected (422)
        check(post(client, url + "/attendance/mark", ownerToken, Web.map(
                "stationId", stn2, "date", "2026-09-01",
                "entries", List.of(Web.map("userId", staffId, "status", "PRESENT")))).statusCode() == 422,
                "Step 8: Marking attendance for staff unassigned to station rejected (422)");

        // Mark September 2026 attendance on stn1: 2 PRESENT, 1 HALF_DAY, 1 WEEK_OFF, 1 lowercase 'leave', 1 ABSENT
        // Expected payableDays = 2 + 0.5 + 1 = 3.5; leave = 1.0
        post(client, url + "/attendance/mark", ownerToken, Web.map("stationId", stn1, "date", "2026-09-01",
                "entries", List.of(Web.map("userId", staffId, "status", "PRESENT"))));
        post(client, url + "/attendance/mark", ownerToken, Web.map("stationId", stn1, "date", "2026-09-02",
                "entries", List.of(Web.map("userId", staffId, "status", "PRESENT"))));
        post(client, url + "/attendance/mark", ownerToken, Web.map("stationId", stn1, "date", "2026-09-03",
                "entries", List.of(Web.map("userId", staffId, "status", "HALF_DAY"))));
        post(client, url + "/attendance/mark", ownerToken, Web.map("stationId", stn1, "date", "2026-09-04",
                "entries", List.of(Web.map("userId", staffId, "status", "WEEK_OFF"))));
        post(client, url + "/attendance/mark", ownerToken, Web.map("stationId", stn1, "date", "2026-09-05",
                "entries", List.of(Web.map("userId", staffId, "status", "leave"))));
        post(client, url + "/attendance/mark", ownerToken, Web.map("stationId", stn1, "date", "2026-09-06",
                "entries", List.of(Web.map("userId", staffId, "status", "ABSENT"))));

        Map<String, Object> sumData = dataMap(get(client, url + "/attendance/summary?stationId=" + stn1 + "&month=2026-09", ownerToken));
        List<Map<String, Object>> empRows = (List<Map<String, Object>>) sumData.get("employees");
        Map<String, Object> staffSum = empRows.stream().filter(r -> staffId.equals(r.get("userId"))).findFirst().orElseThrow();
        check(((Number) staffSum.get("leave")).doubleValue() == 1.0
                        && ((Number) staffSum.get("weekOff")).doubleValue() == 1.0
                        && ((Number) staffSum.get("payableDays")).doubleValue() == 3.5,
                "Step 8: Attendance normalizes 'leave' -> LEAVE (1.0) and includes weekOff in payableDays (3.5)");

        // Station-2 summary must NOT show Station-1-only staff or Station-1 attendance counts
        Map<String, Object> stn2Sum = dataMap(get(client, url + "/attendance/summary?stationId=" + stn2 + "&month=2026-09", ownerToken));
        List<Map<String, Object>> stn2Emps = (List<Map<String, Object>>) stn2Sum.get("employees");
        check(stn2Emps.stream().noneMatch(r -> staffId.equals(r.get("userId"))),
                "Step 8: Station-2 attendance summary excludes staff assigned only to Station-1");

        // DSR daily stock fields check on 2026-10-29 (seeded date with dips & shifts) + today
        Map<String, Object> dsr29 = dataMap(get(client, url + "/reports/dsr?stationId=" + stn1 + "&date=2026-10-29", ownerToken));
        List<Map<String, Object>> dsrStock = (List<Map<String, Object>>) dsr29.get("stock");
        Map<String, Object> firstTank = dsrStock.get(0);
        check(firstTank.get("openingBook") != null
                        && firstTank.get("deliveredToday") != null
                        && firstTank.get("dispensedBook") != null
                        && firstTank.get("bookClosing") != null
                        && firstTank.get("measured") != null
                        && firstTank.get("varianceFlag") instanceof Boolean,
                "Step 8: GET /reports/dsr populates openingBook, deliveredToday, dispensedBook, bookClosing, measured, and varianceFlag");
        java.math.BigDecimal expectedClose = Money.d(firstTank.get("openingBook"))
                .add(Money.d(firstTank.get("deliveredToday")))
                .subtract(Money.d(firstTank.get("dispensedBook")));
        check(Money.litres(expectedClose).equals(firstTank.get("bookClosing")),
                "Step 8: DSR tank stock reconciles openingBook + deliveredToday - dispensedBook == bookClosing");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep9DayCloseAndIdempotency(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        Map<String, Object> cust = Db.one("SELECT id, balance FROM customers WHERE tenant_id = ? LIMIT 1", tenantId);
        String customerId = Db.str(cust, "id");
        java.math.BigDecimal balStart = Money.d(cust.get("balance"));

        // 9a. Failed validation request must NOT cache Idempotency-Key
        String idemKey1 = "idem-s9-pay-01";
        HttpResponse<String> badAttempt = postWithIdem(client, url + "/finance/payments", ownerToken, idemKey1,
                Web.map("stationId", stn1, "customerId", customerId, "amount", "-50", "mode", "CASH"));
        check(badAttempt.statusCode() == 422, "Step 9: Invalid payment with Idempotency-Key returns 422");

        HttpResponse<String> firstOk = postWithIdem(client, url + "/finance/payments", ownerToken, idemKey1,
                Web.map("stationId", stn1, "customerId", customerId, "amount", "325.00", "mode", "CASH"));
        check(firstOk.statusCode() == 201, "Step 9: Retrying same Idempotency-Key after failed validation succeeds (201)");
        String firstPayId = String.valueOf(dataMap(firstOk).get("id"));

        // Exact replay (even with reversed key order in Map) returns cached 201 + Idempotent-Replayed: true without double-deducting
        HttpResponse<String> replayOk = postWithIdem(client, url + "/finance/payments", ownerToken, idemKey1,
                Web.map("mode", "CASH", "amount", "325.00", "customerId", customerId, "stationId", stn1));
        java.math.BigDecimal balAfterReplay = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
        check(replayOk.statusCode() == 201
                        && "true".equalsIgnoreCase(replayOk.headers().firstValue("Idempotent-Replayed").orElse(""))
                        && firstPayId.equals(String.valueOf(dataMap(replayOk).get("id")))
                        && Money.money(balStart.subtract(new java.math.BigDecimal("325.00"))).equals(Money.money(balAfterReplay)),
                "Step 9: Replaying Idempotency-Key returns exact original response without double-applying balance change");

        // Reusing same Idempotency-Key with different payload must return 409 CONFLICT
        HttpResponse<String> conflictRes = postWithIdem(client, url + "/finance/payments", ownerToken, idemKey1,
                Web.map("stationId", stn1, "customerId", customerId, "amount", "999.00", "mode", "CASH"));
        check(conflictRes.statusCode() == 409, "Step 9: Reusing Idempotency-Key with different payload rejected (409)");

        // Concurrent identical requests with same Idempotency-Key execute mutation once
        java.math.BigDecimal balBeforeConc = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
        String concKey = "idem-s9-concurrent-credit";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var latch = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<HttpResponse<String>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                latch.await();
                return postWithIdem(client, url + "/finance/credit-sales", ownerToken, concKey,
                        Web.map("stationId", stn1, "customerId", customerId, "amount", "210.00", "note", "Concurrent slip"));
            }));
        }
        latch.countDown();
        java.util.Set<String> returnedIds = new java.util.HashSet<>();
        boolean all201 = true;
        for (var f : futures) {
            HttpResponse<String> r = f.get();
            if (r.statusCode() != 201) all201 = false;
            returnedIds.add(String.valueOf(dataMap(r).get("id")));
        }
        pool.shutdown();
        java.math.BigDecimal balAfterConc = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
        check(all201 && returnedIds.size() == 1
                        && Money.money(balBeforeConc.add(new java.math.BigDecimal("210.00"))).equals(Money.money(balAfterConc)),
                "Step 9: 4 concurrent identical Idempotency-Key requests create 1 credit sale and update balance once");

        // 9b. Day-close enforcement across operational & financial mutations
        String lockedDate = "2026-10-10";
        String stn2TankId = Db.str(Db.one("SELECT id FROM tanks WHERE station_id = ? LIMIT 1", stn2), "id");
        String stn2NozzleId = Db.str(Db.one("SELECT id FROM nozzles WHERE station_id = ? LIMIT 1", stn2), "id");
        String supplierId = Db.str(Db.one("SELECT id FROM suppliers WHERE tenant_id = ? LIMIT 1", tenantId), "id");

        HttpResponse<String> lockRes = post(client, url + "/shifts/day-close", ownerToken,
                Web.map("stationId", stn2, "businessDate", lockedDate, "notes", "Audit lock"));
        check(lockRes.statusCode() == 201, "Step 9: Business date 2026-10-10 locked on Station 2");

        check(post(client, url + "/finance/expenses", ownerToken,
                Web.map("stationId", stn2, "description", "Backdated exp", "amount", "100", "occurredAt", lockedDate)).statusCode() == 409,
                "Step 9: Expense on locked business date rejected (409)");
        check(post(client, url + "/finance/payments", ownerToken,
                Web.map("stationId", stn2, "customerId", customerId, "amount", "100", "mode", "CASH", "occurredAt", lockedDate)).statusCode() == 409,
                "Step 9: Payment on locked business date rejected (409)");
        check(post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn2, "customerId", customerId, "amount", "100", "occurredAt", lockedDate)).statusCode() == 409,
                "Step 9: Credit sale on locked business date rejected (409)");
        check(post(client, url + "/finance/reconciliation", ownerToken,
                Web.map("stationId", stn2, "businessDate", lockedDate, "cashCounted", "0", "digitalSettled", "0")).statusCode() == 409,
                "Step 9: Reconciliation save on locked business date rejected (409)");
        check(post(client, url + "/inventory/deliveries", ownerToken,
                Web.map("stationId", stn2, "supplierId", supplierId, "invoiceNumber", "INV-LOCKED-1", "invoiceDate", lockedDate, "tankId", stn2TankId, "quantityLitres", "500", "ratePerLitre", "90")).statusCode() == 409,
                "Step 9: Delivery on locked business date rejected (409)");
        check(post(client, url + "/density", ownerToken,
                Web.map("stationId", stn2, "tankId", stn2TankId, "businessDate", lockedDate, "observedDensity", "740.0", "observedTempC", "28.0")).statusCode() == 409,
                "Step 9: Density check on locked business date rejected (409)");
        check(post(client, url + "/attendance/mark", ownerToken,
                Web.map("stationId", stn2, "date", lockedDate, "entries", List.of())).statusCode() == 409,
                "Step 9: Attendance mark on locked business date rejected (409)");
        check(post(client, url + "/catalog/tanks/" + stn2TankId + "/measurements", ownerToken,
                Web.map("measuredLitres", "10000", "measuredAt", lockedDate)).statusCode() == 409,
                "Step 9: Tank dip measurement on locked business date rejected (409)");
        check(post(client, url + "/readings/manual", ownerToken,
                Web.map("nozzleId", stn2NozzleId, "readingValue", "1500.000", "readingAt", lockedDate)).statusCode() == 409,
                "Step 9: Manual meter reading on locked business date rejected (409)");

        // Reopen date -> mutation allowed again
        check(post(client, url + "/shifts/day-close/reopen", ownerToken,
                Web.map("stationId", stn2, "businessDate", lockedDate, "reason", "Audit adjustment authorized")).statusCode() == 200,
                "Step 9: Locked business date reopened with audit reason");
        check(post(client, url + "/finance/expenses", ownerToken,
                Web.map("stationId", stn2, "description", "Authorized post-reopen expense", "amount", "100", "occurredAt", lockedDate)).statusCode() == 201,
                "Step 9: Expense allowed after reopening business date (201)");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStep10CatalogValidation(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));

        Map<String, Object> stn1PetrolTank = Db.one(
                "SELECT t.* FROM tanks t JOIN fuel_products fp ON fp.id = t.product_id WHERE t.station_id = ? AND fp.category = 'PETROL' AND t.status = 'ACTIVE' LIMIT 1",
                stn1);
        Map<String, Object> stn1DieselTank = Db.one(
                "SELECT t.* FROM tanks t JOIN fuel_products fp ON fp.id = t.product_id WHERE t.station_id = ? AND fp.category = 'DIESEL' AND t.status = 'ACTIVE' LIMIT 1",
                stn1);
        Map<String, Object> stn2PetrolTank = Db.one(
                "SELECT t.* FROM tanks t WHERE t.station_id = ? AND t.product_id = ? LIMIT 1",
                stn2, stn1PetrolTank.get("product_id"));
        Map<String, Object> stn1Dispenser = Db.one("SELECT id FROM dispensers WHERE station_id = ? LIMIT 1", stn1);

        String petrolId = Db.str(stn1PetrolTank, "product_id");
        String dispId = Db.str(stn1Dispenser, "id");

        // 1. Active nozzle without tankId -> 422
        check(post(client, url + "/catalog/nozzles", ownerToken,
                Web.map("stationId", stn1, "dispenserId", dispId, "productId", petrolId, "tankId", "", "number", 15, "label", "N15-UNMAPPED", "status", "ACTIVE")).statusCode() == 422,
                "Step 10: Creating ACTIVE nozzle without tankId rejected (422)");

        // 2. Nozzle mapped to tank on another station -> 422
        check(post(client, url + "/catalog/nozzles", ownerToken,
                Web.map("stationId", stn1, "dispenserId", dispId, "productId", petrolId, "tankId", stn2PetrolTank.get("id"), "number", 15, "label", "N15-XSTN", "status", "ACTIVE")).statusCode() == 422,
                "Step 10: Creating nozzle mapped to cross-station tank rejected (422)");

        // 3. Nozzle mapped to tank of different fuel product -> 422
        check(post(client, url + "/catalog/nozzles", ownerToken,
                Web.map("stationId", stn1, "dispenserId", dispId, "productId", petrolId, "tankId", stn1DieselTank.get("id"), "number", 15, "label", "N15-MISMATCH", "status", "ACTIVE")).statusCode() == 422,
                "Step 10: Creating PETROL nozzle mapped to DIESEL tank rejected (422)");

        // 4. Dedicated tank + valid ACTIVE nozzle -> 201
        HttpResponse<String> newTankRes = post(client, url + "/catalog/tanks", ownerToken,
                Web.map("stationId", stn1, "code", "T-STEP10", "name", "Step 10 Tank", "productId", petrolId, "capacityLitres", "12000", "deadStockLitres", "300"));
        check(newTankRes.statusCode() == 201, "Step 10: Dedicated ACTIVE tank created (201)");
        String step10TankId = String.valueOf(dataMap(newTankRes).get("id"));

        HttpResponse<String> validNozzleRes = post(client, url + "/catalog/nozzles", ownerToken,
                Web.map("stationId", stn1, "dispenserId", dispId, "productId", petrolId, "tankId", step10TankId, "number", 15, "label", "N15-VALID", "totalizerUnit", "L", "status", "ACTIVE"));
        check(validNozzleRes.statusCode() == 201, "Step 10: Valid ACTIVE nozzle mapped to same-station same-product ACTIVE tank created (201)");
        String nozzleId = String.valueOf(dataMap(validNozzleRes).get("id"));

        // 5. Unmapping an ACTIVE nozzle -> 422
        check(patch(client, url + "/catalog/nozzles/" + nozzleId, ownerToken,
                Map.of("tankId", "")).statusCode() == 422,
                "Step 10: Unmapping an ACTIVE nozzle rejected (422)");

        // 6. Deactivating tank while ACTIVE nozzle is mapped -> 409
        check(patch(client, url + "/catalog/tanks/" + step10TankId, ownerToken,
                Map.of("status", "MAINTENANCE")).statusCode() == 409,
                "Step 10: Deactivating tank with mapped ACTIVE nozzle rejected (409)");

        // 7. Deactivate nozzle first -> then tank can enter MAINTENANCE -> reactivating nozzle while tank is MAINTENANCE rejected (422)
        check(patch(client, url + "/catalog/nozzles/" + nozzleId, ownerToken,
                Web.map("status", "MAINTENANCE", "tankId", "")).statusCode() == 200,
                "Step 10: Non-ACTIVE nozzle may be placed in MAINTENANCE and unmapped (200)");
        check(patch(client, url + "/catalog/tanks/" + step10TankId, ownerToken,
                Map.of("status", "MAINTENANCE")).statusCode() == 200,
                "Step 10: Tank enters MAINTENANCE once no ACTIVE nozzles remain mapped (200)");
        check(patch(client, url + "/catalog/nozzles/" + nozzleId, ownerToken,
                Web.map("status", "ACTIVE", "tankId", step10TankId)).statusCode() == 422,
                "Step 10: Reactivating nozzle onto MAINTENANCE tank rejected (422)");
    }

    @SuppressWarnings("unchecked")
    private static void verifyPriority1MultiJvmAtomicIdempotency(
            HttpClient client, String url, Config base, String testDb, String ownerToken) throws Exception {
        // 1. Structural check: Web.idempotencyLocks JVM-local lock array must be completely removed
        boolean hasJvmLocalLockArray = java.util.Arrays.stream(Web.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("idempotencyLocks") || f.getType().isArray());
        check(!hasJvmLocalLockArray, "Priority 1: JVM-local Web.idempotencyLocks removed");

        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));
        Map<String, Object> cust = Db.one("SELECT id, balance FROM customers WHERE tenant_id = ? LIMIT 1", tenantId);
        String customerId = Db.str(cust, "id");

        // 2. Spawn TWO genuinely separate OS JVM processes running in.petrolflow.Main against the SAME DB
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        int portA = 4291;
        int portB = 4292;
        Process jvmA = startChildApiJvm(javaBin, cp, portA, base.dbHost, base.dbPort, testDb);
        Process jvmB = startChildApiJvm(javaBin, cp, portB, base.dbHost, base.dbPort, testDb);
        try {
            long selfPid = ProcessHandle.current().pid();
            check(jvmA.pid() != jvmB.pid() && jvmA.pid() != selfPid && jvmB.pid() != selfPid,
                    "Priority 1: Two separate child JVM processes started (" + jvmA.pid() + ", " + jvmB.pid() + ")");
            String urlA = "http://127.0.0.1:" + portA + "/api/v1";
            String urlB = "http://127.0.0.1:" + portB + "/api/v1";
            waitForHealthyJvm(client, urlA + "/health");
            waitForHealthyJvm(client, urlB + "/health");

            // 3. 5 simultaneous CyclicBarrier(2) trials across JVM-A and JVM-B with identical Idempotency-Key
            BigDecimal balBeforeTrials = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
            var barrierPool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                for (int t = 1; t <= 5; t++) {
                    String key = "p1-multijvm-credit-trial-" + t;
                    String note = "MultiJVM Trial " + t;
                    var barrier = new java.util.concurrent.CyclicBarrier(2);
                    Map<String, Object> payloadA = Web.map("stationId", stn1, "customerId", customerId, "amount", "175.00", "note", note);
                    Map<String, Object> payloadB = Web.map("note", note, "amount", "175.00", "customerId", customerId, "stationId", stn1);
                    var fA = barrierPool.submit(() -> {
                        barrier.await();
                        return postWithIdem(client, urlA + "/finance/credit-sales", ownerToken, key, payloadA);
                    });
                    var fB = barrierPool.submit(() -> {
                        barrier.await();
                        return postWithIdem(client, urlB + "/finance/credit-sales", ownerToken, key, payloadB);
                    });
                    HttpResponse<String> rA = fA.get();
                    HttpResponse<String> rB = fB.get();
                    boolean repA = "true".equalsIgnoreCase(rA.headers().firstValue("Idempotent-Replayed").orElse(""));
                    boolean repB = "true".equalsIgnoreCase(rB.headers().firstValue("Idempotent-Replayed").orElse(""));
                    String idA = String.valueOf(dataMap(rA).get("id"));
                    String idB = String.valueOf(dataMap(rB).get("id"));
                    long rowCount = Db.count("SELECT COUNT(*) AS c FROM credit_entries WHERE tenant_id = ? AND note = ?", tenantId, note);
                    Map<String, Object> idemRow = Db.one("SELECT status, response_status FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?", tenantId, key);
                    check(rA.statusCode() == 201 && rB.statusCode() == 201
                                    && idA.equals(idB)
                                    && (repA ^ repB)
                                    && rowCount == 1
                                    && idemRow != null && "COMPLETED".equals(Db.str(idemRow, "status")),
                            "Priority 1: Simultaneous cross-JVM trial " + t + " committed 1 credit entry and replayed exact response");
                }

                BigDecimal balAfterTrials = Money.d(Db.one("SELECT balance FROM customers WHERE id = ?", customerId).get("balance"));
                check(Money.money(balBeforeTrials.add(new BigDecimal("875.00"))).equals(Money.money(balAfterTrials)),
                        "Priority 1: Customer balance incremented exactly 5 * 175.00 = 875.00 across 5 cross-JVM simultaneous trials");

                // 4. Simultaneous cross-JVM requests with SAME Idempotency-Key and DIFFERENT payload -> one 201, one 409
                String mismatchKey = "p1-multijvm-mismatch-01";
                var mismatchBarrier = new java.util.concurrent.CyclicBarrier(2);
                var mfA = barrierPool.submit(() -> {
                    mismatchBarrier.await();
                    return postWithIdem(client, urlA + "/finance/credit-sales", ownerToken, mismatchKey,
                            Web.map("stationId", stn1, "customerId", customerId, "amount", "200.00", "note", "Mismatch A"));
                });
                var mfB = barrierPool.submit(() -> {
                    mismatchBarrier.await();
                    return postWithIdem(client, urlB + "/finance/credit-sales", ownerToken, mismatchKey,
                            Web.map("stationId", stn1, "customerId", customerId, "amount", "999.00", "note", "Mismatch B"));
                });
                int msA = mfA.get().statusCode();
                int msB = mfB.get().statusCode();
                long mismatchRows = Db.count("SELECT COUNT(*) AS c FROM credit_entries WHERE tenant_id = ? AND note IN ('Mismatch A','Mismatch B')", tenantId);
                check(((msA == 201 && msB == 409) || (msA == 409 && msB == 201)) && mismatchRows == 1,
                        "Priority 1: Simultaneous cross-JVM mismatched payloads yield 201 + 409 with single committed mutation");

                // 5. Active IN_PROGRESS claim in DB blocks duplicate execution in child JVM and replays once COMPLETED
                String inflightKey = "p1-inflight-claim-01";
                Map<String, Object> inflightBody = Web.map("stationId", stn1, "customerId", customerId, "amount", "125.00", "note", "Inflight Claim");
                String inflightHash = Passwords.sha256(Json.stringify(new java.util.TreeMap<>(inflightBody)));
                String inflightClaimId = Clock.newId("idm");
                String now = Clock.nowIso();
                Db.exec("""
                        INSERT INTO idempotency_keys
                          (id, tenant_id, scope, `key`, request_hash, status, response_status, response_body, created_at, expires_at)
                        VALUES (?, ?, 'POST /finance/credit-sales', ?, ?, 'IN_PROGRESS', NULL, NULL, ?, ?)
                        """, inflightClaimId, tenantId, inflightKey, inflightHash, now, Clock.addHoursIso(now, 24));
                var completer = barrierPool.submit(() -> {
                    Thread.sleep(120);
                    Db.exec("UPDATE idempotency_keys SET status = 'COMPLETED', response_status = 201, response_body = ? WHERE id = ?",
                            "{\"data\":{\"id\":\"cre_synthetic_winner\",\"amount\":\"125.00\"}}", inflightClaimId);
                    return null;
                });
                HttpResponse<String> waitedRes = postWithIdem(client, urlA + "/finance/credit-sales", ownerToken, inflightKey, inflightBody);
                completer.get();
                long inflightMutations = Db.count("SELECT COUNT(*) AS c FROM credit_entries WHERE tenant_id = ? AND note = 'Inflight Claim'", tenantId);
                check(waitedRes.statusCode() == 201
                                && "true".equalsIgnoreCase(waitedRes.headers().firstValue("Idempotent-Replayed").orElse(""))
                                && "cre_synthetic_winner".equals(dataMap(waitedRes).get("id"))
                                && inflightMutations == 0,
                        "Priority 1: Active IN_PROGRESS claim prevents handler execution and replays winner once COMPLETED");

                // 6. 24-hour expiry: expired row is cleared and fresh request executes cleanly
                String expKey = "p1-expired-key-01";
                HttpResponse<String> expFirst = postWithIdem(client, urlA + "/finance/expenses", ownerToken, expKey,
                        Web.map("stationId", stn1, "description", "Expired key test", "amount", "55.00"));
                String firstExpId = String.valueOf(dataMap(expFirst).get("id"));
                Db.exec("UPDATE idempotency_keys SET expires_at = '2020-01-01T00:00:00.000Z' WHERE tenant_id = ? AND `key` = ?", tenantId, expKey);
                HttpResponse<String> expSecond = postWithIdem(client, urlB + "/finance/expenses", ownerToken, expKey,
                        Web.map("stationId", stn1, "description", "Expired key test", "amount", "55.00"));
                String secondExpId = String.valueOf(dataMap(expSecond).get("id"));
                check(expFirst.statusCode() == 201 && expSecond.statusCode() == 201
                                && !firstExpId.equals(secondExpId)
                                && expSecond.headers().firstValue("Idempotent-Replayed").isEmpty(),
                        "Priority 1: Expired (>24h) Idempotency-Key is purged and re-executes fresh across JVMs");

                // 7. Failed / rolled-back business handler never caches IN_PROGRESS or COMPLETED row
                String failKey = "p1-never-cache-fail-01";
                HttpResponse<String> failRes = postWithIdem(client, urlA + "/finance/expenses", ownerToken, failKey,
                        Web.map("stationId", stn1, "description", "Bad negative expense", "amount", "-10.00"));
                check(failRes.statusCode() == 422
                                && Db.count("SELECT COUNT(*) AS c FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?", tenantId, failKey) == 0,
                        "Priority 1: Failed business mutation rolls back IN_PROGRESS claim and caches nothing");
            } finally {
                barrierPool.shutdownNow();
            }
        } finally {
            jvmA.destroyForcibly();
            jvmB.destroyForcibly();
            jvmA.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            jvmB.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        // 8. Crash/exception atomicity test: uncaught exception after DB write inside handler rolls back BOTH business write and idempotency claim
        int crashPort = 4293;
        Config crashCfg = Config.load(Map.of("PORT", String.valueOf(crashPort), "PETROFLOW_DB_NAME", testDb));
        Web crashWeb = new Web(crashCfg);
        java.util.concurrent.atomic.AtomicBoolean shouldCrash = new java.util.concurrent.atomic.AtomicBoolean(true);
        crashWeb.post("/test-atomic-crash", ctx -> {
            Auth a = ctx.requireAuth();
            Db.tx(() -> {
                Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, ?, '', 'p1.crash.probe', 'written', ?)",
                        Clock.newId("stg"), a.tenantId(), Clock.nowIso());
                return null;
            });
            if (shouldCrash.get()) {
                throw new IllegalStateException("Simulated mid-request crash after business write");
            }
            ctx.created(Map.of("ok", true));
        });
        crashWeb.listen();
        try {
            String crashUrl = "http://127.0.0.1:" + crashPort + "/api/v1/test-atomic-crash";
            String crashKey = "p1-atomic-crash-key";
            HttpResponse<String> crashed = postWithIdem(client, crashUrl, ownerToken, crashKey, Map.of("probe", 1));
            check(crashed.statusCode() == 500
                            && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = 'p1.crash.probe'") == 0
                            && Db.count("SELECT COUNT(*) AS c FROM idempotency_keys WHERE `key` = ?", crashKey) == 0,
                    "Priority 1: Uncaught crash after nested Db.tx write rolls back BOTH business write and idempotency claim");

            shouldCrash.set(false);
            HttpResponse<String> recovered = postWithIdem(client, crashUrl, ownerToken, crashKey, Map.of("probe", 1));
            Map<String, Object> committedIdem = Db.one("SELECT status, response_status FROM idempotency_keys WHERE `key` = ?", crashKey);
            check(recovered.statusCode() == 201
                            && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = 'p1.crash.probe'") == 1
                            && committedIdem != null && "COMPLETED".equals(Db.str(committedIdem, "status"))
                            && Db.num(committedIdem, "response_status") == 201,
                    "Priority 1: Committed business write and COMPLETED idempotency record persist atomically together");
            Db.exec("DELETE FROM app_settings WHERE `key` = 'p1.crash.probe'");
        } finally {
            crashWeb.stop();
        }
    }

    private static Process startChildApiJvm(String javaBin, String cp, int port, String dbHost, int dbPort, String dbName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp, "in.petrolflow.Main");
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("HOST", "127.0.0.1");
        pb.environment().put("MYSQL_HOST", dbHost);
        pb.environment().put("MYSQL_PORT", String.valueOf(dbPort));
        pb.environment().put("PETROFLOW_DB_NAME", dbName);
        pb.environment().put("SEED_DEMO_DATA", "false");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread drainer = new Thread(() -> {
            try (var in = proc.getInputStream()) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (Exception ignored) {
            }
        });
        drainer.setDaemon(true);
        drainer.start();
        return proc;
    }

    private static void waitForHealthyJvm(HttpClient client, String healthUrl) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (get(client, healthUrl, null).statusCode() == 200) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Child JVM did not become healthy at " + healthUrl);
    }

    @SuppressWarnings("unchecked")
    private static void verifyPriority2DensityCompliance(HttpClient client, String url, String ownerToken) throws Exception {
        // 1. Verify existing Table 53B calculation stays exact across 12 authoritative ASTM D1250-80 points
        String[][] astm53bPoints = {
                {"PETROL", "712.0", "5.0", "702.9"},
                {"PETROL", "725.0", "15.0", "725.0"},
                {"PETROL", "735.0", "28.0", "746.6"},
                {"PETROL", "738.8", "31.0", "753.0"},
                {"PETROL", "755.0", "35.0", "772.2"},
                {"PETROL", "772.0", "40.0", "790.5"},
                {"PETROL", "776.0", "30.0", "787.1"},
                {"DIESEL", "815.0", "10.0", "811.4"},
                {"DIESEL", "825.0", "25.0", "832.0"},
                {"DIESEL", "830.0", "30.5", "840.7"},
                {"DIESEL", "838.0", "35.0", "851.8"},
                {"DIESEL", "840.0", "45.0", "860.7"}
        };
        for (String[] pt : astm53bPoints) {
            Domain.DensityEvaluation ev = Domain.evaluateDensityAt15c(pt[0], pt[1], pt[2]);
            check(pt[3].equals(ev.densityAt15c()),
                    "Priority 2: ASTM D1250-80 Table 53B exact match " + pt[1] + " @ " + pt[2] + "°C -> " + pt[3]);
        }

        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String petrolTankId = Db.str(Db.one(
                "SELECT t.id FROM tanks t JOIN fuel_products p ON p.id = t.product_id WHERE t.station_id = ? AND p.category = 'PETROL' LIMIT 1", stn1), "id");
        String dieselTankId = Db.str(Db.one(
                "SELECT t.id FROM tanks t JOIN fuel_products p ON p.id = t.product_id WHERE t.station_id = ? AND p.category = 'DIESEL' LIMIT 1", stn1), "id");

        // 2. IS 1460:2017 BS-VI Diesel ceiling: 845 -> withinSpec=1; 845.1 -> withinSpec=0
        HttpResponse<String> d845 = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "845", "observedTempC", "15.0", "source", "TANK"));
        Map<String, Object> d845Body = dataMap(d845);
        check(d845.statusCode() == 201
                        && "845.0".equals(d845Body.get("densityAt15c"))
                        && "810".equals(d845Body.get("specMin"))
                        && "845".equals(d845Body.get("specMax"))
                        && ((Number) d845Body.get("withinSpec")).intValue() == 1,
                "Priority 2: Diesel 845 kg/m³ @ 15°C accepted as IN_SPEC (withinSpec=1, specMax=845)");

        HttpResponse<String> d8451 = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "845.1", "observedTempC", "15.0", "source", "TANK"));
        Map<String, Object> d8451Body = dataMap(d8451);
        check(d8451.statusCode() == 201
                        && "845.1".equals(d8451Body.get("densityAt15c"))
                        && "845".equals(d8451Body.get("specMax"))
                        && ((Number) d8451Body.get("withinSpec")).intValue() == 0,
                "Priority 2: Diesel 845.1 kg/m³ @ 15°C flagged OUT_OF_SPEC (withinSpec=0, exceeds IS 1460:2017 845.0 ceiling)");

        // 3. Lower Table 53B boundary: 653 accepted; below 653 rejected (422) before calculation; cold extrapolation below 653 rejected (422)
        HttpResponse<String> at653 = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "653", "observedTempC", "15.0", "source", "NOZZLE"));
        check(at653.statusCode() == 201
                        && "653.0".equals(dataMap(at653).get("densityAt15c"))
                        && ((Number) dataMap(at653).get("withinSpec")).intValue() == 0,
                "Priority 2: Table 53B lower boundary 653 kg/m³ @ 15°C accepted (653.0, withinSpec=0 for petrol)");
        check("662.5".equals(Domain.evaluateDensityAt15c("PETROL", "653.0", "25.0").densityAt15c()),
                "Priority 2: Table 53B lower boundary 653.0 kg/m³ @ 25°C converges cleanly to 662.5 without premature rejection");

        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "observedDensity", "652.9", "observedTempC", "15.0")).statusCode() == 422,
                "Priority 2: Observed density below 653 (652.9 kg/m³) rejected with 422 before calculation");
        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "observedDensity", "550.0", "observedTempC", "25.0")).statusCode() == 422,
                "Priority 2: Observed density 550.0 kg/m³ (< 653.0) rejected with 422 before calculation");
        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", petrolTankId, "observedDensity", "653.0", "observedTempC", "5.0")).statusCode() == 422,
                "Priority 2: 653.0 kg/m³ @ 5.0°C rejected with 422 rather than extrapolating Table 53B below 653.0");

        // 4. Upper Table 53B boundary: 1075 accepted; above 1075 rejected (422) before calculation; hot extrapolation above 1075 rejected (422)
        HttpResponse<String> at1075 = post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "businessDate", Clock.todayIst(),
                "observedDensity", "1075", "observedTempC", "15.0", "source", "TANK"));
        check(at1075.statusCode() == 201
                        && "1075.0".equals(dataMap(at1075).get("densityAt15c"))
                        && ((Number) dataMap(at1075).get("withinSpec")).intValue() == 0,
                "Priority 2: Table 53B upper boundary 1075 kg/m³ @ 15°C accepted (1075.0, withinSpec=0 for diesel)");
        check("1068.6".equals(Domain.evaluateDensityAt15c("DIESEL", "1075.0", "5.0").densityAt15c()),
                "Priority 2: Table 53B upper boundary 1075.0 kg/m³ @ 5°C converges cleanly to 1068.6 without premature rejection");

        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "observedDensity", "1075.1", "observedTempC", "15.0")).statusCode() == 422,
                "Priority 2: Observed density above 1075 (1075.1 kg/m³) rejected with 422 before calculation");
        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "observedDensity", "1090.0", "observedTempC", "25.0")).statusCode() == 422,
                "Priority 2: Observed density 1090.0 kg/m³ (> 1075.0) rejected with 422 before calculation");
        check(post(client, url + "/density", ownerToken, Web.map(
                "stationId", stn1, "tankId", dieselTankId, "observedDensity", "1075.0", "observedTempC", "25.0")).statusCode() == 422,
                "Priority 2: 1075.0 kg/m³ @ 25.0°C rejected with 422 rather than extrapolating Table 53B above 1075.0");

        // 5. Unsupported fuel categories must NOT default to withinSpec=true (1)
        for (String unsupported : java.util.Arrays.asList("CNG", "LUBRICANT", "KEROSENE", "", null)) {
            Domain.DensityEvaluation uEv = Domain.evaluateDensityAt15c(unsupported, "745.0", "15.0");
            check(uEv.withinSpec() == 0 && uEv.specMin() == null && uEv.specMax() == null,
                    "Priority 2: Unsupported fuel category (" + unsupported + ") sets withinSpec=0 and null spec bounds");
        }
        HttpResponse<String> cngProd = post(client, url + "/catalog/products", ownerToken,
                Web.map("code", "CNG-P2", "name", "Compressed Natural Gas", "category", "CNG", "unit", "KG", "color", "#0284c7", "isActive", true));
        String cngProdId = String.valueOf(dataMap(cngProd).get("id"));
        HttpResponse<String> cngTank = post(client, url + "/catalog/tanks", ownerToken,
                Web.map("stationId", stn1, "code", "T-CNG-P2", "name", "CNG Storage", "productId", cngProdId, "capacityLitres", "5000", "deadStockLitres", "100"));
        String cngTankId = String.valueOf(dataMap(cngTank).get("id"));
        HttpResponse<String> cngDens = post(client, url + "/density", ownerToken,
                Web.map("stationId", stn1, "tankId", cngTankId, "businessDate", Clock.todayIst(),
                        "observedDensity", "745.0", "observedTempC", "15.0", "withinSpec", true));
        check(cngDens.statusCode() == 201
                        && ((Number) dataMap(cngDens).get("withinSpec")).intValue() == 0
                        && dataMap(cngDens).get("specMin") == null
                        && dataMap(cngDens).get("specMax") == null,
                "Priority 2: POST /density on unsupported CNG category records withinSpec=0 even if client spoofs withinSpec=true");
    }

    @SuppressWarnings("unchecked")
    private static void verifyPriority3StationAndTenantSecurity(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));
        String month = Clock.todayIst().substring(0, 7);

        // Configure STATION-1 MANAGER and STATION-1 STAFF (both strictly assigned ONLY to stn1)
        String stn1MgrToken = ensureStation1ManagerToken(tenantId, stn1);
        String stn1StaffToken = ensureStation1StaffToken(tenantId, stn1, List.of(
                "customers.read", "customers.write", "reports.read", "audit.read", "sales.read", "payments.read"));

        // Create Station-2 Exclusive Customer (activity ONLY on stn2: +5000 sale - 1000 payment = 4000.00)
        String stn2CustId = String.valueOf(dataMap(post(client, url + "/catalog/customers", ownerToken,
                Web.map("code", "P3-STN2-ONLY", "name", "Station2 Exclusive Fleet", "creditLimit", "100000", "creditDays", 15, "status", "ACTIVE"))).get("id"));
        check(post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn2, "customerId", stn2CustId, "amount", "5000.00", "note", "STN2 secret slip")).statusCode() == 201,
                "Priority 3 setup: Station-2 exclusive credit sale posted");
        check(post(client, url + "/finance/payments", ownerToken,
                Web.map("stationId", stn2, "customerId", stn2CustId, "amount", "1000.00", "mode", "UPI", "reference", "STN2-UPI-1")).statusCode() == 201,
                "Priority 3 setup: Station-2 exclusive payment posted (net 4000.00)");

        // Create Shared Multi-Station Customer (activity on BOTH stn1=1500.00 AND stn2=6000.00 -> total 7500.00)
        String sharedCustId = String.valueOf(dataMap(post(client, url + "/catalog/customers", ownerToken,
                Web.map("code", "P3-SHARED-12", "name", "Shared Highway Logistics", "creditLimit", "200000", "creditDays", 15, "status", "ACTIVE"))).get("id"));
        check(post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn1, "customerId", sharedCustId, "amount", "1500.00", "note", "STN1 shared slip")).statusCode() == 201,
                "Priority 3 setup: Shared customer Station-1 credit sale (1500.00) posted");
        check(post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn2, "customerId", sharedCustId, "amount", "6000.00", "note", "STN2 shared slip")).statusCode() == 201,
                "Priority 3 setup: Shared customer Station-2 credit sale (6000.00) posted");

        // Create second isolated tenant + station + customer for tenant-ownership negative tests
        String otherTenantId = Clock.newId("tnt");
        String otherStnId = Clock.newId("stn");
        String otherCustId = Clock.newId("cus");
        String now = Clock.nowIso();
        Db.exec("INSERT INTO tenants (id, name, slug, status, created_at, updated_at) VALUES (?, 'Other Pump Co', ?, 'ACTIVE', ?, ?)",
                otherTenantId, "other-" + System.nanoTime(), now, now);
        Db.exec("INSERT INTO stations (id, tenant_id, code, name, city, state, status, created_at, updated_at) VALUES (?, ?, 'OTH1', 'Other Station', 'Mumbai', 'MH', 'ACTIVE', ?, ?)",
                otherStnId, otherTenantId, now, now);
        Db.exec("INSERT INTO customers (id, tenant_id, code, name, credit_limit, credit_days, balance, status, created_at, updated_at) VALUES (?, ?, 'OTH-C1', 'Other Tenant Customer', '50000.00', 15, '0.00', 'ACTIVE', ?, ?)",
                otherCustId, otherTenantId, now, now);

        // Stamp distinct Station-1 and Station-2 audit logs so dashboard & /audit tests are deterministic
        Db.exec("INSERT INTO audit_logs (id, tenant_id, station_id, actor_id, actor_email, action, entity, entity_id, summary, created_at) VALUES (?, ?, ?, NULL, 'owner@sunrisefuels.in', 'P3_STN1_EVENT', 'Test', '1', 'STN1 visible event', ?)",
                Clock.newId("aud"), tenantId, stn1, Clock.nowIso());
        Db.exec("INSERT INTO audit_logs (id, tenant_id, station_id, actor_id, actor_email, action, entity, entity_id, summary, created_at) VALUES (?, ?, ?, NULL, 'owner@sunrisefuels.in', 'P3_STN2_SECRET', 'Test', '2', 'STN2 confidential event', ?)",
                Clock.newId("aud"), tenantId, stn2, Clock.nowIso());

        // --- 3A. FinanceApi.customerLedger ---
        HttpResponse<String> ownLedStn2 = get(client, url + "/finance/customer-ledger?customerId=" + stn2CustId, ownerToken);
        check(ownLedStn2.statusCode() == 200
                        && "4000.00".equals(dataMap(ownLedStn2).get("closingBalance"))
                        && ((List<?>) dataMap(ownLedStn2).get("ledger")).size() == 2,
                "Priority 3 [customerLedger]: OWNER sees full Station-2 customer ledger (4000.00, 2 rows)");
        check(get(client, url + "/finance/customer-ledger?customerId=" + stn2CustId, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/finance/customer-ledger?customerId=" + stn2CustId + "&stationId=" + stn2, stn1MgrToken).statusCode() == 403,
                "Priority 3 [customerLedger]: STATION-1 MANAGER blocked (403) from Station-2 exclusive customer ledger");
        check(get(client, url + "/finance/customer-ledger?customerId=" + stn2CustId, stn1StaffToken).statusCode() == 403
                        && get(client, url + "/finance/customer-ledger?customerId=" + stn2CustId + "&stationId=" + stn2, stn1StaffToken).statusCode() == 403,
                "Priority 3 [customerLedger]: STATION-1 STAFF blocked (403) from Station-2 exclusive customer ledger");

        HttpResponse<String> ownLedShared = get(client, url + "/finance/customer-ledger?customerId=" + sharedCustId, ownerToken);
        HttpResponse<String> mgrLedShared = get(client, url + "/finance/customer-ledger?customerId=" + sharedCustId, stn1MgrToken);
        HttpResponse<String> stfLedShared = get(client, url + "/finance/customer-ledger?customerId=" + sharedCustId, stn1StaffToken);
        List<Map<String, Object>> mgrSharedRows = (List<Map<String, Object>>) dataMap(mgrLedShared).get("ledger");
        List<Map<String, Object>> stfSharedRows = (List<Map<String, Object>>) dataMap(stfLedShared).get("ledger");
        check("7500.00".equals(dataMap(ownLedShared).get("closingBalance"))
                        && mgrLedShared.statusCode() == 200
                        && "1500.00".equals(dataMap(mgrLedShared).get("closingBalance"))
                        && "1500.00".equals(Json.object(dataMap(mgrLedShared).get("customer")).get("balance"))
                        && mgrSharedRows.size() == 1 && stn1.equals(mgrSharedRows.get(0).get("stationId"))
                        && stfLedShared.statusCode() == 200
                        && "1500.00".equals(dataMap(stfLedShared).get("closingBalance"))
                        && "1500.00".equals(Json.object(dataMap(stfLedShared).get("customer")).get("balance"))
                        && stfSharedRows.size() == 1 && stn1.equals(stfSharedRows.get(0).get("stationId")),
                "Priority 3 [customerLedger]: Shared customer shows 7500.00 to OWNER and strictly 1500.00 (0 STN2 rows) to STN1 MANAGER & STAFF");
        check(get(client, url + "/finance/customer-ledger?customerId=" + otherCustId, ownerToken).statusCode() == 404,
                "Priority 3 [customerLedger]: Cross-tenant customer returns 404");

        // --- 3B. FinanceApi.statement ---
        HttpResponse<String> ownStmtStn2 = get(client, url + "/finance/customer-statement?customerId=" + stn2CustId + "&month=" + month, ownerToken);
        check(ownStmtStn2.statusCode() == 200 && "4000.00".equals(dataMap(ownStmtStn2).get("closingBalance")),
                "Priority 3 [statement]: OWNER sees full Station-2 customer statement (4000.00)");
        check(get(client, url + "/finance/customer-statement?customerId=" + stn2CustId + "&month=" + month, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/finance/customer-statement?customerId=" + stn2CustId + "&month=" + month + "&stationId=" + stn2, stn1MgrToken).statusCode() == 403,
                "Priority 3 [statement]: STATION-1 MANAGER blocked (403) from Station-2 customer statement");
        check(get(client, url + "/finance/customer-statement?customerId=" + stn2CustId + "&month=" + month, stn1StaffToken).statusCode() == 403
                        && get(client, url + "/finance/customer-statement?customerId=" + stn2CustId + "&month=" + month + "&stationId=" + stn2, stn1StaffToken).statusCode() == 403,
                "Priority 3 [statement]: STATION-1 STAFF blocked (403) from Station-2 customer statement");

        HttpResponse<String> ownStmtShared = get(client, url + "/finance/customer-statement?customerId=" + sharedCustId + "&month=" + month, ownerToken);
        HttpResponse<String> mgrStmtShared = get(client, url + "/finance/customer-statement?customerId=" + sharedCustId + "&month=" + month, stn1MgrToken);
        HttpResponse<String> stfStmtShared = get(client, url + "/finance/customer-statement?customerId=" + sharedCustId + "&month=" + month, stn1StaffToken);
        check("7500.00".equals(dataMap(ownStmtShared).get("closingBalance"))
                        && mgrStmtShared.statusCode() == 200
                        && "1500.00".equals(dataMap(mgrStmtShared).get("closingBalance"))
                        && "1500.00".equals(Json.object(dataMap(mgrStmtShared).get("customer")).get("balance"))
                        && ((List<?>) dataMap(mgrStmtShared).get("lines")).size() == 1
                        && stfStmtShared.statusCode() == 200
                        && "1500.00".equals(dataMap(stfStmtShared).get("closingBalance"))
                        && ((List<?>) dataMap(stfStmtShared).get("lines")).size() == 1,
                "Priority 3 [statement]: Shared customer statement shows 7500.00 to OWNER and strictly 1500.00 to STN1 MANAGER & STAFF");

        // --- 3C. CatalogApi.addFollowup (+ followups / dueFollowups) ---
        check(post(client, url + "/catalog/customers/" + sharedCustId + "/followups", stn1MgrToken,
                Web.map("stationId", stn2, "kind", "CALL", "note", "Unauthorized STN2 followup")).statusCode() == 403,
                "Priority 3 [addFollowup]: STATION-1 MANAGER blocked (403) from posting follow-up with stationId=STN2");
        check(post(client, url + "/catalog/customers/" + sharedCustId + "/followups", stn1StaffToken,
                Web.map("stationId", stn2, "kind", "CALL", "note", "Unauthorized STN2 followup")).statusCode() == 403,
                "Priority 3 [addFollowup]: STATION-1 STAFF blocked (403) from posting follow-up with stationId=STN2");
        check(post(client, url + "/catalog/customers/" + otherCustId + "/followups", ownerToken,
                Web.map("stationId", stn1, "kind", "CALL", "note", "Cross-tenant customer")).statusCode() == 404
                        && post(client, url + "/catalog/customers/" + sharedCustId + "/followups", ownerToken,
                        Web.map("stationId", otherStnId, "kind", "CALL", "note", "Cross-tenant station")).statusCode() == 404,
                "Priority 3 [addFollowup]: Cross-tenant customer or station rejected with 404");

        check(post(client, url + "/catalog/customers/" + sharedCustId + "/followups", ownerToken,
                Web.map("stationId", stn2, "kind", "VISIT", "note", "STN2 owner followup", "nextOn", Clock.todayIst())).statusCode() == 201,
                "Priority 3 [addFollowup]: OWNER permitted (201) to post Station-2 follow-up");
        check(post(client, url + "/catalog/customers/" + sharedCustId + "/followups", stn1MgrToken,
                Web.map("kind", "CALL", "note", "STN1 default-station followup", "nextOn", Clock.todayIst())).statusCode() == 201,
                "Priority 3 [addFollowup]: STATION-1 MANAGER follow-up without stationId defaults cleanly to STN1 (201)");
        List<Map<String, Object>> mgrFollowups = dataList(get(client, url + "/catalog/customers/" + sharedCustId + "/followups", stn1MgrToken));
        List<Map<String, Object>> stfDueFollowups = dataList(get(client, url + "/catalog/followups?dueBy=" + Clock.todayIst(), stn1StaffToken));
        check(mgrFollowups.stream().allMatch(f -> stn1.equals(f.get("stationId")))
                        && stfDueFollowups.stream().allMatch(f -> stn1.equals(f.get("stationId")))
                        && get(client, url + "/catalog/customers/" + sharedCustId + "/followups?stationId=" + stn2, stn1MgrToken).statusCode() == 403,
                "Priority 3 [followups]: STN1 MANAGER & STAFF never see STN2 follow-ups and get 403 on stationId=STN2");

        // --- 3D. ReportApi.dashboard recentActivity ---
        check(get(client, url + "/reports/owner-dashboard?stationId=" + stn2, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/reports/owner-dashboard?stationId=" + stn2, stn1StaffToken).statusCode() == 403
                        && get(client, url + "/reports/owner-dashboard?stationId=" + stn2, ownerToken).statusCode() == 200,
                "Priority 3 [dashboard]: STN1 MANAGER & STAFF blocked (403) on stationId=STN2 while OWNER succeeds (200)");
        for (String tok : List.of(ownerToken, stn1MgrToken, stn1StaffToken)) {
            Map<String, Object> dash1 = dataMap(get(client, url + "/reports/owner-dashboard?stationId=" + stn1 + "&date=" + Clock.todayIst(), tok));
            List<Map<String, Object>> recent = (List<Map<String, Object>>) dash1.get("recentActivity");
            check(recent.stream().noneMatch(a -> "P3_STN2_SECRET".equals(a.get("action")))
                            && recent.stream().anyMatch(a -> "P3_STN1_EVENT".equals(a.get("action"))),
                    "Priority 3 [dashboard]: Station-1 dashboard recentActivity excludes Station-2 audit logs");
        }

        // --- 3E. ReportApi.ageing and ReportApi.csvAgeing ---
        check(get(client, url + "/reports/ageing?stationId=" + stn2, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/reports/ageing?stationId=" + stn2, stn1StaffToken).statusCode() == 403
                        && get(client, url + "/reports/export/ageing.csv?stationId=" + stn2, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/reports/export/ageing.csv?stationId=" + stn2, stn1StaffToken).statusCode() == 403,
                "Priority 3 [ageing/csvAgeing]: STN1 MANAGER & STAFF blocked (403) when requesting stationId=STN2");

        Map<String, Object> ownAgeingAll = dataMap(get(client, url + "/reports/ageing?stationId=all", ownerToken));
        List<Map<String, Object>> ownAgeRows = (List<Map<String, Object>>) ownAgeingAll.get("rows");
        check(ownAgeRows.stream().anyMatch(r -> stn2CustId.equals(r.get("customerId")) && "4000.00".equals(r.get("outstanding")))
                        && ownAgeRows.stream().anyMatch(r -> sharedCustId.equals(r.get("customerId")) && "7500.00".equals(r.get("outstanding"))),
                "Priority 3 [ageing]: OWNER all-station ageing includes STN2 customer (4000.00) and full shared customer (7500.00)");

        for (String tok : List.of(stn1MgrToken, stn1StaffToken)) {
            Map<String, Object> scopedAge = dataMap(get(client, url + "/reports/ageing", tok));
            List<Map<String, Object>> scopedRows = (List<Map<String, Object>>) scopedAge.get("rows");
            HttpResponse<String> scopedCsv = get(client, url + "/reports/export/ageing.csv", tok);
            check(scopedRows.stream().noneMatch(r -> stn2CustId.equals(r.get("customerId")))
                            && scopedRows.stream().anyMatch(r -> sharedCustId.equals(r.get("customerId")) && "1500.00".equals(r.get("outstanding")))
                            && !scopedCsv.body().contains("P3-STN2-ONLY")
                            && scopedCsv.body().contains("P3-SHARED-12,1500.00"),
                    "Priority 3 [ageing/csvAgeing]: STN1 user sees zero STN2-only customers and strictly 1500.00 for shared customer in JSON & CSV");
        }

        // --- 3F. OpsApi.audit ---
        check(get(client, url + "/audit?stationId=" + stn2, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/audit", stn1MgrToken).statusCode() == 403
                        && get(client, url + "/audit?stationId=" + stn2, stn1StaffToken).statusCode() == 403,
                "Priority 3 [audit]: STN1 MANAGER blocked by RBAC (403) and STN1 STAFF blocked (403) on GET /audit?stationId=STN2");
        List<Map<String, Object>> ownAuditRows = dataList(get(client, url + "/audit?pageSize=50", ownerToken));
        check(ownAuditRows.stream().anyMatch(a -> "P3_STN2_SECRET".equals(a.get("action"))),
                "Priority 3 [audit]: OWNER all-station GET /audit includes Station-2 audit events");
        HttpResponse<String> audRes = get(client, url + "/audit?pageSize=50", stn1StaffToken);
        List<Map<String, Object>> audRows = dataList(audRes);
        Map<String, Object> meta = Json.object(Json.object(Json.parse(audRes.body())).get("meta"));
        List<Map<String, Object>> actionFacets = (List<Map<String, Object>>) meta.get("actions");
        check(audRes.statusCode() == 200
                        && !audRows.isEmpty()
                        && audRows.stream().allMatch(a -> stn1.equals(a.get("stationId")))
                        && audRows.stream().noneMatch(a -> "P3_STN2_SECRET".equals(a.get("action")))
                        && actionFacets.stream().noneMatch(f -> "P3_STN2_SECRET".equals(f.get("action"))),
                "Priority 3 [audit]: STN1 STAFF (with audit.read) GET /audit without stationId returns strictly STN1 events and STN1 action facets");

        // --- 3G. CatalogApi.addVehicle (+ vehicles / deleteVehicle) ---
        for (String tok : List.of(ownerToken, stn1MgrToken, stn1StaffToken)) {
            check(post(client, url + "/catalog/customers/" + otherCustId + "/vehicles", tok,
                    Web.map("vehicleNumber", "MH01XX9999", "vehicleType", "TRUCK")).statusCode() == 404,
                    "Priority 3 [addVehicle]: Cross-tenant customer vehicle insert rejected with 404");
        }
        check(post(client, url + "/catalog/customers/cus_nonexistent_p3/vehicles", ownerToken,
                Web.map("vehicleNumber", "MH01XX0000", "vehicleType", "TRUCK")).statusCode() == 404
                        && get(client, url + "/catalog/customers/" + otherCustId + "/vehicles", ownerToken).statusCode() == 404
                        && delete(client, url + "/catalog/customers/" + otherCustId + "/vehicles/veh_none", ownerToken).statusCode() == 404
                        && Db.count("SELECT COUNT(*) AS c FROM customer_vehicles WHERE customer_id = ?", otherCustId) == 0,
                "Priority 3 [vehicles]: Non-existent & cross-tenant customer vehicle list/create/delete rejected with 404 and zero DB rows");
        check(post(client, url + "/catalog/customers/" + sharedCustId + "/vehicles", stn1MgrToken,
                Web.map("vehicleNumber", "MH12P3VALID", "vehicleType", "TANKER")).statusCode() == 201,
                "Priority 3 [addVehicle]: Same-tenant customer vehicle creation succeeds (201)");
    }

    @SuppressWarnings("unchecked")
    private static void verifyAuditRemediationF05ToF10(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));
        String today = Clock.todayIst();

        List<String> staffPerms = List.of(
                "customers.read", "customers.write", "reports.read", "sales.write",
                "suppliers.read", "deliveries.read", "lubricants.read", "density.read");
        String stn1MgrToken = ensureStation1ManagerToken(tenantId, stn1);
        String stn1StaffToken = ensureStation1StaffToken(tenantId, stn1, staffPerms);
        String stn2StaffToken = ensureStation2StaffToken(tenantId, stn2, staffPerms);

        // --- F-05: ASTM D1250-80 Table 53B Sub-zone 2 upper boundary 787.5 kg/m³ ---
        String[][] f05Points = {
                {"765.8", "30.0", "778.0"},
                {"778.0", "15.0", "778.0"},
                {"778.0", "25.0", "785.5"},
                {"766.7", "30.0", "778.8"},
                {"778.8", "15.0", "778.8"},
                {"778.8", "25.0", "786.3"},
                {"768.0", "30.0", "780.0"},
                {"780.0", "15.0", "780.0"},
                {"780.0", "25.0", "787.4"},
                {"776.3", "30.0", "787.4"},
                {"787.4", "15.0", "787.4"},
                {"787.4", "25.0", "794.7"},
                {"776.4", "30.0", "787.5"},
                {"787.5", "15.0", "787.5"},
                {"787.5", "25.0", "794.8"},
                {"760.0", "40.0", "780.0"}
        };
        for (String[] pt : f05Points) {
            Domain.DensityEvaluation ev = Domain.evaluateDensityAt15c("PETROL", pt[0], pt[1]);
            check(pt[2].equals(ev.densityAt15c()),
                    "F-05: ASTM D1250-80 Table 53B boundary " + pt[0] + " @ " + pt[1] + "°C -> " + pt[2] + " (got " + ev.densityAt15c() + ")");
        }

        Map<String, Object> petrolTank = Db.one(
                "SELECT t.id FROM tanks t JOIN fuel_products p ON p.id = t.product_id WHERE t.station_id = ? AND p.category = 'PETROL' LIMIT 1",
                stn1);
        Map<String, Object> dieselTank = Db.one(
                "SELECT t.id FROM tanks t JOIN fuel_products p ON p.id = t.product_id WHERE t.station_id = ? AND p.category = 'DIESEL' LIMIT 1",
                stn1);
        String petrolTankId = Db.str(petrolTank, "id");
        String dieselTankId = Db.str(dieselTank, "id");

        HttpResponse<String> post760At40 = post(client, url + "/density", ownerToken,
                Web.map("stationId", stn1, "tankId", petrolTankId, "businessDate", today,
                        "observedDensity", "760.0", "observedTempC", "40.0", "source", "NOZZLE"));
        String dns760Id = String.valueOf(dataMap(post760At40).get("id"));
        check(post760At40.statusCode() == 201
                        && "780.0".equals(dataMap(post760At40).get("densityAt15c"))
                        && "780.0".equals(Db.str(Db.one("SELECT density_at_15c FROM density_checks WHERE id = ?", dns760Id), "density_at_15c")),
                "F-05: POST /density 760.0 @ 40.0°C records exact ASTM Table 53B 780.0 kg/m³ in API & DB");

        // --- F-06: Shared density specification label agreement across POST & GET /density ---
        long preExistingCount = Db.count("SELECT COUNT(*) AS count FROM density_checks");
        HttpResponse<String> postDiesel = post(client, url + "/density", ownerToken,
                Web.map("stationId", stn1, "tankId", dieselTankId, "businessDate", today,
                        "observedDensity", "835.0", "observedTempC", "15.0", "source", "TANK"));
        String dieselDnsId = String.valueOf(dataMap(postDiesel).get("id"));
        String postDieselSpec = String.valueOf(dataMap(postDiesel).get("specificationUsed"));

        String postPetrolSpec = String.valueOf(dataMap(post760At40).get("specificationUsed"));

        HttpResponse<String> cngProdRes = post(client, url + "/catalog/products", ownerToken,
                Web.map("code", "CNG-F06", "name", "CNG F06", "category", "CNG", "unit", "KG", "color", "#10b981", "isActive", true));
        String cngProdId = String.valueOf(dataMap(cngProdRes).get("id"));
        HttpResponse<String> cngTankRes = post(client, url + "/catalog/tanks", ownerToken,
                Web.map("stationId", stn1, "productId", cngProdId, "code", "T-CNG-F06", "name", "CNG Tank F06", "capacityLitres", "5000", "deadStockLitres", "50"));
        String cngTankId = String.valueOf(dataMap(cngTankRes).get("id"));
        HttpResponse<String> postCng = post(client, url + "/density", ownerToken,
                Web.map("stationId", stn1, "tankId", cngTankId, "businessDate", today,
                        "observedDensity", "750.0", "observedTempC", "15.0", "source", "TANK"));
        String cngDnsId = String.valueOf(dataMap(postCng).get("id"));
        String postCngSpec = String.valueOf(dataMap(postCng).get("specificationUsed"));

        HttpResponse<String> lubeFuelProdRes = post(client, url + "/catalog/products", ownerToken,
                Web.map("code", "LUB-F06", "name", "Lube Oil F06", "category", "LUBRICANT", "unit", "L", "color", "#f59e0b", "isActive", true));
        String lubeFuelProdId = String.valueOf(dataMap(lubeFuelProdRes).get("id"));
        HttpResponse<String> lubeFuelTankRes = post(client, url + "/catalog/tanks", ownerToken,
                Web.map("stationId", stn1, "productId", lubeFuelProdId, "code", "T-LUB-F06", "name", "Lube Tank F06", "capacityLitres", "3000", "deadStockLitres", "30"));
        String lubeFuelTankId = String.valueOf(dataMap(lubeFuelTankRes).get("id"));
        HttpResponse<String> postLubeDns = post(client, url + "/density", ownerToken,
                Web.map("stationId", stn1, "tankId", lubeFuelTankId, "businessDate", today,
                        "observedDensity", "880.0", "observedTempC", "15.0", "source", "TANK"));
        String lubeDnsId = String.valueOf(dataMap(postLubeDns).get("id"));
        String postLubeSpec = String.valueOf(dataMap(postLubeDns).get("specificationUsed"));

        List<Map<String, Object>> densityGetRows = dataList(get(client, url + "/density?stationId=" + stn1 + "&from=" + today + "&to=" + today + "&pageSize=100", ownerToken));
        String getDieselSpec = densityGetRows.stream().filter(r -> dieselDnsId.equals(r.get("id"))).map(r -> String.valueOf(r.get("specificationUsed"))).findFirst().orElse("");
        String getPetrolSpec = densityGetRows.stream().filter(r -> dns760Id.equals(r.get("id"))).map(r -> String.valueOf(r.get("specificationUsed"))).findFirst().orElse("");
        String getCngSpec = densityGetRows.stream().filter(r -> cngDnsId.equals(r.get("id"))).map(r -> String.valueOf(r.get("specificationUsed"))).findFirst().orElse("");
        String getLubeSpec = densityGetRows.stream().filter(r -> lubeDnsId.equals(r.get("id"))).map(r -> String.valueOf(r.get("specificationUsed"))).findFirst().orElse("");

        check(postDieselSpec.equals(getDieselSpec)
                        && getDieselSpec.contains("Diesel screening band (810–845 kg/m³)")
                        && !getDieselSpec.contains("860"),
                "F-06: GET /density and POST /density agree on uncertified Diesel screening band (810–845 kg/m³)");
        check(postPetrolSpec.equals(getPetrolSpec)
                        && getPetrolSpec.contains("Petrol screening band (720–775 kg/m³)"),
                "F-06: GET /density and POST /density agree on uncertified Petrol screening band (720–775 kg/m³)");
        check(postCngSpec.equals(getCngSpec)
                        && getCngSpec.contains("Unsupported fuel category")
                        && !getCngSpec.contains("Motor Gasoline")
                        && !getCngSpec.contains("720–775")
                        && postLubeSpec.equals(getLubeSpec)
                        && getLubeSpec.contains("Unsupported fuel category")
                        && !getLubeSpec.contains("Motor Gasoline")
                        && Db.count("SELECT COUNT(*) AS count FROM density_checks") == preExistingCount + 3,
                "F-06: GET & POST /density report Unsupported fuel category for CNG & LUBRICANT without rewriting history");

        // --- F-09: Prevent arbitrary unbacked customer balance writes ---
        HttpResponse<String> unbackedRes = post(client, url + "/catalog/customers", stn1MgrToken,
                Web.map("code", "F09-UNBACKED", "name", "F09 Unbacked Co", "creditLimit", "500000", "balance", "99999.00"));
        String unbackedId = String.valueOf(dataMap(unbackedRes).get("id"));
        String dbUnbackedBal = Db.str(Db.one("SELECT balance FROM customers WHERE id = ?", unbackedId), "balance");
        long unbackedEntries = Db.count("SELECT COUNT(*) AS count FROM credit_entries WHERE customer_id = ?", unbackedId);
        check(unbackedRes.statusCode() == 201
                        && "0.00".equals(dataMap(unbackedRes).get("balance"))
                        && "0.00".equals(dbUnbackedBal)
                        && unbackedEntries == 0,
                "F-09: Submitting balance=99999.00 on POST /catalog/customers is ignored and defaults to 0.00 in API & DB");

        // --- F-07: Station-scoped customer directory balances (GET /catalog/customers & GET /customers) ---
        HttpResponse<String> f07Stn2OnlyRes = post(client, url + "/catalog/customers", ownerToken,
                Web.map("code", "F07-STN2-EX", "name", "F07 Station2 Exclusive", "creditLimit", "200000"));
        String f07Stn2OnlyId = String.valueOf(dataMap(f07Stn2OnlyRes).get("id"));
        post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn2, "customerId", f07Stn2OnlyId, "amount", "4000.00", "occurredAt", today));

        HttpResponse<String> f07SharedRes = post(client, url + "/catalog/customers", ownerToken,
                Web.map("code", "F07-SHARED", "name", "F07 Shared Fleet", "creditLimit", "200000"));
        String f07SharedId = String.valueOf(dataMap(f07SharedRes).get("id"));
        post(client, url + "/finance/credit-sales", stn1MgrToken,
                Web.map("stationId", stn1, "customerId", f07SharedId, "amount", "1500.00", "occurredAt", today));
        post(client, url + "/finance/credit-sales", stn2StaffToken,
                Web.map("stationId", stn2, "customerId", f07SharedId, "amount", "6000.00", "occurredAt", today));

        for (String ep : List.of("/catalog/customers?search=F07-", "/customers?search=F07-")) {
            List<Map<String, Object>> ownRows = dataList(get(client, url + ep, ownerToken));
            List<Map<String, Object>> s1MgrRows = dataList(get(client, url + ep, stn1MgrToken));
            List<Map<String, Object>> s1StfRows = dataList(get(client, url + ep, stn1StaffToken));
            List<Map<String, Object>> s2StfRows = dataList(get(client, url + ep, stn2StaffToken));

            check("4000.00".equals(findCustomerBal(ownRows, f07Stn2OnlyId))
                            && "7500.00".equals(findCustomerBal(ownRows, f07SharedId)),
                    "F-07 [" + ep + "]: OWNER retains all-station balances (4000.00 & 7500.00)");
            check("0.00".equals(findCustomerBal(s1MgrRows, f07Stn2OnlyId))
                            && "1500.00".equals(findCustomerBal(s1MgrRows, f07SharedId))
                            && "0.00".equals(findCustomerBal(s1StfRows, f07Stn2OnlyId))
                            && "1500.00".equals(findCustomerBal(s1StfRows, f07SharedId)),
                    "F-07 [" + ep + "]: STN1 MANAGER & STAFF see 0.00 for STN2-exclusive and strictly 1500.00 for shared customer while preserving directory lookup");
            check("4000.00".equals(findCustomerBal(s2StfRows, f07Stn2OnlyId))
                            && "6000.00".equals(findCustomerBal(s2StfRows, f07SharedId)),
                    "F-07 [" + ep + "]: STN2 STAFF sees 4000.00 for STN2-exclusive and strictly 6000.00 for shared customer");
        }
        check(get(client, url + "/catalog/customers?stationId=" + stn2, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/customers?stationId=" + stn2, stn1StaffToken).statusCode() == 403
                        && get(client, url + "/catalog/customers?stationId=" + stn1, stn2StaffToken).statusCode() == 403,
                "F-07: Explicit unauthorized stationId rejected with 403 on /catalog/customers and /customers");

        HttpResponse<String> patchCustS1 = patch(client, url + "/catalog/customers/" + f07SharedId, stn1MgrToken,
                Map.of("phone", "9876500001"));
        check(patchCustS1.statusCode() == 200 && "1500.00".equals(dataMap(patchCustS1).get("balance")),
                "F-07: PATCH /catalog/customers/:id returns station-scoped balance (1500.00) to STN1 MANAGER");

        // --- F-08: Follow-up due balances & credit-sale newBalance / limit-rejection leaks ---
        post(client, url + "/catalog/customers/" + f07SharedId + "/followups", stn1MgrToken,
                Web.map("stationId", stn1, "kind", "CALL", "note", "F08 STN1 chase", "nextOn", today));
        post(client, url + "/catalog/customers/" + f07SharedId + "/followups", stn2StaffToken,
                Web.map("stationId", stn2, "kind", "CALL", "note", "F08 STN2 chase", "nextOn", today));

        List<Map<String, Object>> ownDue = dataList(get(client, url + "/catalog/followups?dueBy=" + today, ownerToken));
        List<Map<String, Object>> s1MgrDue = dataList(get(client, url + "/catalog/followups?dueBy=" + today, stn1MgrToken));
        List<Map<String, Object>> s1StfDue = dataList(get(client, url + "/catalog/followups?dueBy=" + today, stn1StaffToken));
        List<Map<String, Object>> s2StfDue = dataList(get(client, url + "/catalog/followups?dueBy=" + today, stn2StaffToken));
        check(ownDue.stream().filter(f -> f07SharedId.equals(f.get("customerId"))).allMatch(f -> "7500.00".equals(f.get("balance")))
                        && s1MgrDue.stream().filter(f -> f07SharedId.equals(f.get("customerId"))).allMatch(f -> "1500.00".equals(f.get("balance")))
                        && s1StfDue.stream().filter(f -> f07SharedId.equals(f.get("customerId"))).allMatch(f -> "1500.00".equals(f.get("balance")))
                        && s2StfDue.stream().filter(f -> f07SharedId.equals(f.get("customerId"))).allMatch(f -> "6000.00".equals(f.get("balance"))),
                "F-08: GET /catalog/followups returns 7500.00 to OWNER, 1500.00 to STN1 users, and 6000.00 to STN2 users");

        HttpResponse<String> s1CreditSale = post(client, url + "/finance/credit-sales", stn1MgrToken,
                Web.map("stationId", stn1, "customerId", f07SharedId, "amount", "250.00", "occurredAt", today));
        HttpResponse<String> ownCreditSale = post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn2, "customerId", f07SharedId, "amount", "250.00", "occurredAt", today));
        check(s1CreditSale.statusCode() == 201
                        && "1750.00".equals(dataMap(s1CreditSale).get("newBalance"))
                        && ownCreditSale.statusCode() == 201
                        && "8000.00".equals(dataMap(ownCreditSale).get("newBalance"))
                        && "8000.00".equals(Db.str(Db.one("SELECT balance FROM customers WHERE id = ?", f07SharedId), "balance")),
                "F-08: POST /finance/credit-sales returns station-scoped newBalance (1750.00) to STN1 MANAGER and tenant total (8000.00) to OWNER");

        HttpResponse<String> limitCustRes = post(client, url + "/catalog/customers", ownerToken,
                Web.map("code", "F08-LIM", "name", "F08 Limit Check", "creditLimit", "10000.00"));
        String limitCustId = String.valueOf(dataMap(limitCustRes).get("id"));
        post(client, url + "/finance/credit-sales", stn2StaffToken,
                Web.map("stationId", stn2, "customerId", limitCustId, "amount", "9500.00", "occurredAt", today));
        HttpResponse<String> s1LimitReject = post(client, url + "/finance/credit-sales", stn1MgrToken,
                Web.map("stationId", stn1, "customerId", limitCustId, "amount", "1000.00", "occurredAt", today));
        HttpResponse<String> ownLimitReject = post(client, url + "/finance/credit-sales", ownerToken,
                Web.map("stationId", stn1, "customerId", limitCustId, "amount", "1000.00", "occurredAt", today));
        check(s1LimitReject.statusCode() == 422
                        && !s1LimitReject.body().contains("9500")
                        && ownLimitReject.statusCode() == 422
                        && ownLimitReject.body().contains("9500.00"),
                "F-08: Credit-limit rejection hides cross-station outstanding balance (9500.00) from STN1 MANAGER while preserving OWNER detail");

        // --- F-10: Supplier ledger, delivery supplierPaidTotal, and lubricant stockQty station scoping ---
        HttpResponse<String> f10SupRes = post(client, url + "/catalog/suppliers", ownerToken,
                Web.map("code", "SUP-F10", "name", "F10 Shared OMC", "openingBalance", "5000.00", "isActive", true));
        String f10SupId = String.valueOf(dataMap(f10SupRes).get("id"));
        String stn2TankId = Db.str(Db.one("SELECT id FROM tanks WHERE station_id = ? LIMIT 1", stn2), "id");

        post(client, url + "/inventory/deliveries", ownerToken,
                Web.map("stationId", stn1, "supplierId", f10SupId, "tankId", petrolTankId,
                        "invoiceNumber", "INV-F10-STN1", "invoiceDate", today,
                        "quantityLitres", "1000", "ratePerLitre", "85", "totalAmount", "85000.00", "receiveNow", true));
        post(client, url + "/finance/payments", ownerToken,
                Web.map("stationId", stn1, "partyType", "SUPPLIER", "supplierId", f10SupId, "direction", "PAYOUT",
                        "mode", "BANK", "amount", "25000.00", "reference", "UTR-F10-STN1", "occurredAt", today));

        post(client, url + "/inventory/deliveries", ownerToken,
                Web.map("stationId", stn2, "supplierId", f10SupId, "tankId", stn2TankId,
                        "invoiceNumber", "INV-F10-STN2", "invoiceDate", today,
                        "quantityLitres", "2000", "ratePerLitre", "85", "totalAmount", "170000.00", "receiveNow", true));
        post(client, url + "/finance/payments", ownerToken,
                Web.map("stationId", stn2, "partyType", "SUPPLIER", "supplierId", f10SupId, "direction", "PAYOUT",
                        "mode", "BANK", "amount", "77777.00", "reference", "UTR-F10-STN2", "occurredAt", today));

        HttpResponse<String> ownSupLed = get(client, url + "/finance/supplier-ledger?supplierId=" + f10SupId, ownerToken);
        HttpResponse<String> s1MgrSupLed = get(client, url + "/finance/supplier-ledger?supplierId=" + f10SupId, stn1MgrToken);
        HttpResponse<String> s1StfSupLed = get(client, url + "/finance/supplier-ledger?supplierId=" + f10SupId, stn1StaffToken);
        HttpResponse<String> s2StfSupLed = get(client, url + "/finance/supplier-ledger?supplierId=" + f10SupId, stn2StaffToken);
        check(ownSupLed.statusCode() == 200
                        && ownSupLed.body().contains("INV-F10-STN1") && ownSupLed.body().contains("INV-F10-STN2")
                        && ownSupLed.body().contains("UTR-F10-STN1") && ownSupLed.body().contains("UTR-F10-STN2")
                        && "157223.00".equals(dataMap(ownSupLed).get("closingBalance")),
                "F-10 [supplier-ledger]: OWNER retains all-station supplier invoices, UTRs, and opening/closing balance");
        check(s1MgrSupLed.statusCode() == 200 && s1StfSupLed.statusCode() == 200
                        && s1MgrSupLed.body().contains("INV-F10-STN1") && s1MgrSupLed.body().contains("UTR-F10-STN1")
                        && !s1MgrSupLed.body().contains("INV-F10-STN2") && !s1MgrSupLed.body().contains("UTR-F10-STN2")
                        && !s1StfSupLed.body().contains("INV-F10-STN2") && !s1StfSupLed.body().contains("UTR-F10-STN2")
                        && "60000.00".equals(dataMap(s1MgrSupLed).get("closingBalance"))
                        && "0.00".equals(dataMap(s1MgrSupLed).get("openingBalance"))
                        && get(client, url + "/finance/supplier-ledger?supplierId=" + f10SupId + "&stationId=" + stn2, stn1MgrToken).statusCode() == 403,
                "F-10 [supplier-ledger]: STN1 MANAGER & STAFF isolated from STN2 invoices/UTRs/openingBalance and blocked (403) on stationId=STN2");
        check(s2StfSupLed.statusCode() == 200
                        && s2StfSupLed.body().contains("INV-F10-STN2") && s2StfSupLed.body().contains("UTR-F10-STN2")
                        && !s2StfSupLed.body().contains("INV-F10-STN1") && !s2StfSupLed.body().contains("UTR-F10-STN1")
                        && "92223.00".equals(dataMap(s2StfSupLed).get("closingBalance"))
                        && get(client, url + "/finance/supplier-ledger?supplierId=" + f10SupId + "&stationId=" + stn1, stn2StaffToken).statusCode() == 403,
                "F-10 [supplier-ledger]: STN2 STAFF isolated from STN1 invoices/UTRs and blocked (403) on stationId=STN1");

        List<Map<String, Object>> s1Delivs = dataList(get(client, url + "/inventory/deliveries?stationId=" + stn1 + "&search=INV-F10-STN1", stn1MgrToken));
        List<Map<String, Object>> s2Delivs = dataList(get(client, url + "/inventory/deliveries?stationId=" + stn2 + "&search=INV-F10-STN2", stn2StaffToken));
        check("25000.00".equals(s1Delivs.get(0).get("supplierPaidTotal"))
                        && "77777.00".equals(s2Delivs.get(0).get("supplierPaidTotal")),
                "F-10 [deliveries]: supplierPaidTotal restricted to delivery's station scope (25000.00 on STN1, 77777.00 on STN2)");

        HttpResponse<String> lubeSkuRes = post(client, url + "/lubricants", ownerToken,
                Web.map("code", "LUB-F10-SKU", "name", "F10 Servo 4T", "packSize", "1 L", "gstRatePct", "18", "purchaseRate", "250", "saleRate", "320"));
        String f10LubeId = String.valueOf(dataMap(lubeSkuRes).get("id"));
        post(client, url + "/lubricants/movements", ownerToken,
                Web.map("stationId", stn1, "productId", f10LubeId, "type", "PURCHASE", "quantity", "20", "rate", "250"));
        post(client, url + "/lubricants/movements", ownerToken,
                Web.map("stationId", stn2, "productId", f10LubeId, "type", "PURCHASE", "quantity", "33", "rate", "250"));

        String ownLubeAll = findLubeStock(dataList(get(client, url + "/lubricants", ownerToken)), f10LubeId);
        String s1MgrLubeNoStn = findLubeStock(dataList(get(client, url + "/lubricants", stn1MgrToken)), f10LubeId);
        String s1StfLubeNoStn = findLubeStock(dataList(get(client, url + "/lubricants", stn1StaffToken)), f10LubeId);
        String s2StfLubeNoStn = findLubeStock(dataList(get(client, url + "/lubricants", stn2StaffToken)), f10LubeId);
        HttpResponse<String> s1PatchLube = patch(client, url + "/lubricants/" + f10LubeId, stn1MgrToken, Map.of("brand", "IndianOil"));
        check("53.000".equals(ownLubeAll)
                        && "20.000".equals(s1MgrLubeNoStn)
                        && "20.000".equals(s1StfLubeNoStn)
                        && "33.000".equals(s2StfLubeNoStn)
                        && s1PatchLube.statusCode() == 200
                        && "20.000".equals(dataMap(s1PatchLube).get("stockQty"))
                        && get(client, url + "/lubricants?stationId=" + stn2, stn1MgrToken).statusCode() == 403
                        && get(client, url + "/lubricants?stationId=" + stn2, stn1StaffToken).statusCode() == 403
                        && get(client, url + "/lubricants?stationId=" + stn1, stn2StaffToken).statusCode() == 403
                        && patch(client, url + "/lubricants/" + f10LubeId + "?stationId=" + stn2, stn1MgrToken, Map.of("brand", "X")).statusCode() == 403,
                "F-10 [lubricants]: OWNER sees 53.000, STN1 users see 20.000, STN2 STAFF sees 33.000, PATCH scopes stockQty, and cross-station stationId returns 403");
    }

    @SuppressWarnings("unchecked")
    private static void verifyF16SupplierOpeningBalanceIsolation(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));
        String today = Clock.todayIst();

        String stn1MgrToken = ensureStation1ManagerToken(tenantId, stn1);
        List<String> staffSupplierPerms = List.of("suppliers.read", "suppliers.write", "deliveries.read", "deliveries.write", "payments.read", "payments.write");
        String stn1StaffToken = ensureStation1StaffToken(tenantId, stn1, staffSupplierPerms);
        String stn2StaffToken = ensureStation2StaffToken(tenantId, stn2, staffSupplierPerms);

        // Create zero-station staff user with suppliers.read
        String zeroStnUid = Clock.newId("usr");
        String zeroNow = Clock.nowIso();
        Db.exec("INSERT INTO users (id, tenant_id, email, name, password_hash, role_key, status, granted_permissions, created_at, updated_at) VALUES (?, ?, 'f16.nostation@sunrisefuels.in', 'No Station Staff', ?, 'STAFF', 'ACTIVE', ?, ?, ?)",
                zeroStnUid, tenantId, Passwords.hash("PetroFlow@2025"), Json.stringify(List.of("suppliers.read", "suppliers.write")), zeroNow, zeroNow);
        String zeroStnToken = Passwords.randomToken();
        Db.exec("INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
                Clock.newId("ses"), zeroStnUid, Passwords.sha256(zeroStnToken), Clock.addDays(today, 1) + "T23:59:59.000Z", zeroNow, zeroNow);

        // Create supplier with distinct non-zero tenant opening balance via OWNER
        HttpResponse<String> ownCreateSup = post(client, url + "/catalog/suppliers", ownerToken,
                Web.map("code", "SUP-F16", "name", "F16 Bharat Petroleum", "openingBalance", "125000.50", "creditDays", 15, "isActive", true));
        check(ownCreateSup.statusCode() == 201 && "125000.50".equals(dataMap(ownCreateSup).get("openingBalance")),
                "F-16: OWNER creates supplier with formatted tenant openingBalance 125000.50");
        String f16SupId = String.valueOf(dataMap(ownCreateSup).get("id"));

        Map<String, Object> expectedTotalRow = Db.one(
                "SELECT COALESCE(SUM(CAST(opening_balance AS DECIMAL(20,2))), 0) AS total FROM suppliers WHERE tenant_id = ?",
                tenantId);
        String expectedTotal = Money.money(expectedTotalRow.get("total"));

        // 1. OWNER directory view & ledger consistency (tenant-wide vs single-station filter)
        HttpResponse<String> ownDirRes = get(client, url + "/catalog/suppliers?pageSize=100", ownerToken);
        Map<String, Object> ownDirJson = Json.object(Json.parse(ownDirRes.body()));
        Map<String, Object> ownDirMeta = Json.object(ownDirJson.get("meta"));
        List<Map<String, Object>> ownDirRows = (List<Map<String, Object>>) ownDirJson.get("data");
        String ownRowOpening = ownDirRows.stream().filter(r -> f16SupId.equals(r.get("id")))
                .map(r -> String.valueOf(r.get("openingBalance"))).findFirst().orElse(null);
        Map<String, Object> ownLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + f16SupId, ownerToken));
        check(ownDirRes.statusCode() == 200
                        && "125000.50".equals(ownRowOpening)
                        && expectedTotal.equals(ownDirMeta.get("openingBalanceTotal"))
                        && "125000.50".equals(ownLedger.get("openingBalance"))
                        && "125000.50".equals(Json.object(ownLedger.get("supplier")).get("openingBalance")),
                "F-16: OWNER sees tenant-wide supplier openingBalance (125000.50) and exact openingBalanceTotal (" + expectedTotal + ") matching supplier ledger");

        HttpResponse<String> ownStn1DirRes = get(client, url + "/catalog/suppliers?stationId=" + stn1 + "&pageSize=100", ownerToken);
        Map<String, Object> ownStn1Meta = Json.object(Json.object(Json.parse(ownStn1DirRes.body())).get("meta"));
        List<Map<String, Object>> ownStn1Rows = dataList(ownStn1DirRes);
        Map<String, Object> ownStn1Ledger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + f16SupId + "&stationId=" + stn1, ownerToken));
        check("0.00".equals(ownStn1Meta.get("openingBalanceTotal"))
                        && ownStn1Rows.stream().allMatch(r -> "0.00".equals(r.get("openingBalance")))
                        && "0.00".equals(ownStn1Ledger.get("openingBalance"))
                        && "0.00".equals(Json.object(ownStn1Ledger.get("supplier")).get("openingBalance")),
                "F-16: Single-station supplier directory & ledger both report 0.00 openingBalance even for OWNER");

        // 2. Station MANAGER and STAFF must NEVER see tenant-wide opening balances in directory, ?stationId=all, or ledger
        for (String[] actor : List.of(
                new String[]{"STN1_MGR", stn1MgrToken, stn1, stn2},
                new String[]{"STN1_STAFF", stn1StaffToken, stn1, stn2},
                new String[]{"STN2_STAFF", stn2StaffToken, stn2, stn1})) {
            String label = actor[0];
            String tok = actor[1];
            String allowedStn = actor[2];
            String otherStn = actor[3];
            for (String q : List.of("?pageSize=100", "?stationId=all&pageSize=100", "?stationId=" + allowedStn + "&pageSize=100")) {
                HttpResponse<String> dirRes = get(client, url + "/catalog/suppliers" + q, tok);
                Map<String, Object> bodyObj = Json.object(Json.parse(dirRes.body()));
                List<Map<String, Object>> rows = (List<Map<String, Object>>) bodyObj.get("data");
                Map<String, Object> meta = Json.object(bodyObj.get("meta"));
                boolean foundF16 = rows.stream().anyMatch(r -> f16SupId.equals(r.get("id")) && "F16 Bharat Petroleum".equals(r.get("name")));
                boolean allZero = rows.stream().allMatch(r -> "0.00".equals(r.get("openingBalance")));
                check(dirRes.statusCode() == 200
                                && foundF16
                                && allZero
                                && "0.00".equals(meta.get("openingBalanceTotal"))
                                && !dirRes.body().contains("125000.50")
                                && !dirRes.body().contains("185000"),
                        "F-16 [" + label + " " + q + "]: Directory preserves supplier lookup while masking all openingBalances & openingBalanceTotal to 0.00");
            }
            Map<String, Object> actorLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + f16SupId, tok));
            check("0.00".equals(actorLedger.get("openingBalance"))
                            && "0.00".equals(Json.object(actorLedger.get("supplier")).get("openingBalance")),
                    "F-16 [" + label + "]: Supplier ledger modal receives consistent 0.00 openingBalance");
            check(get(client, url + "/catalog/suppliers?stationId=" + otherStn, tok).statusCode() == 403,
                    "F-16 [" + label + "]: Unauthorized stationId on GET /catalog/suppliers rejected with 403");
        }

        // 3. Zero-station user & unknown stationId checks
        HttpResponse<String> zeroDir = get(client, url + "/catalog/suppliers", zeroStnToken);
        check(zeroDir.statusCode() == 200
                        && dataList(zeroDir).isEmpty()
                        && "0.00".equals(Json.object(Json.object(Json.parse(zeroDir.body())).get("meta")).get("openingBalanceTotal"))
                        && get(client, url + "/catalog/suppliers?stationId=stn_nonexistent", ownerToken).statusCode() == 404,
                "F-16: Zero-station user receives empty supplier list with 0.00 total; unknown stationId returns 404");

        // 4. Station MANAGER (no suppliers.write) & Station STAFF granted suppliers.write openingBalance isolation
        check(post(client, url + "/catalog/suppliers", stn1MgrToken, Web.map("code", "SUP-MGR-DENY", "name", "Mgr Denied")).statusCode() == 403
                        && patch(client, url + "/catalog/suppliers/" + f16SupId, stn1MgrToken, Web.map("phone", "9800000000")).statusCode() == 403,
                "F-16: Default station MANAGER without suppliers.write is blocked (403) from creating or patching suppliers");

        HttpResponse<String> stfForbiddenOpening = post(client, url + "/catalog/suppliers", stn1StaffToken,
                Web.map("code", "SUP-STF-BAD", "name", "Staff NonZero Attempt", "openingBalance", "50000.00"));
        HttpResponse<String> stfZeroOpening = post(client, url + "/catalog/suppliers", stn1StaffToken,
                Web.map("code", "SUP-STF-OK", "name", "Staff Zero Supplier", "openingBalance", "0"));
        HttpResponse<String> stfPatchF16 = patch(client, url + "/catalog/suppliers/" + f16SupId, stn1StaffToken,
                Web.map("phone", "9822012345"));
        check(stfForbiddenOpening.statusCode() == 403
                        && stfZeroOpening.statusCode() == 201
                        && "0.00".equals(dataMap(stfZeroOpening).get("openingBalance"))
                        && stfPatchF16.statusCode() == 200
                        && "0.00".equals(dataMap(stfPatchF16).get("openingBalance"))
                        && !stfPatchF16.body().contains("125000.50")
                        && "125000.50".equals(Money.money(Db.one("SELECT opening_balance FROM suppliers WHERE id = ?", f16SupId).get("opening_balance"))),
                "F-16: Station user granted suppliers.write cannot set non-zero openingBalance (403) and PATCH /catalog/suppliers/:id masks openingBalance without mutating DB");

        // 5. Station-specific deliveries & payouts remain intact and consistent
        String petrolTankStn1 = Db.str(Db.one("SELECT id FROM tanks WHERE station_id = ? LIMIT 1", stn1), "id");
        HttpResponse<String> s1Deliv = post(client, url + "/inventory/deliveries", stn1MgrToken,
                Web.map("stationId", stn1, "supplierId", f16SupId, "tankId", petrolTankStn1,
                        "invoiceNumber", "INV-F16-S1", "invoiceDate", today,
                        "quantityLitres", "500", "ratePerLitre", "84", "totalAmount", "42000.00", "receiveNow", true));
        HttpResponse<String> s1Pay = post(client, url + "/finance/payments", stn1MgrToken,
                Web.map("stationId", stn1, "partyType", "SUPPLIER", "supplierId", f16SupId, "direction", "PAYOUT",
                        "mode", "BANK", "amount", "12000.00", "reference", "UTR-F16-S1", "occurredAt", today));
        Map<String, Object> s1AfterLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + f16SupId, stn1MgrToken));
        Map<String, Object> s2AfterLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + f16SupId, stn2StaffToken));
        Map<String, Object> ownAfterLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + f16SupId, ownerToken));
        check(s1Deliv.statusCode() == 201 && s1Pay.statusCode() == 201
                        && "0.00".equals(s1AfterLedger.get("openingBalance"))
                        && "30000.00".equals(s1AfterLedger.get("closingBalance"))
                        && "0.00".equals(s2AfterLedger.get("openingBalance"))
                        && "0.00".equals(s2AfterLedger.get("closingBalance"))
                        && "125000.50".equals(ownAfterLedger.get("openingBalance"))
                        && "155000.50".equals(ownAfterLedger.get("closingBalance")),
                "F-16: Station deliveries & payouts remain visible per station (STN1 closing 30000.00, STN2 0.00, OWNER 155000.50)");
    }

    @SuppressWarnings("unchecked")
    private static void verifyA01SupplierOpeningBalanceAggregatePrecision(HttpClient client, String url, String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String stn2 = String.valueOf(stations.get(1).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));
        String today = Clock.todayIst();

        String stn1MgrToken = ensureStation1ManagerToken(tenantId, stn1);
        List<String> staffSupplierPerms = List.of("suppliers.read", "suppliers.write", "deliveries.read", "deliveries.write", "payments.read", "payments.write");
        String stn1StaffToken = ensureStation1StaffToken(tenantId, stn1, staffSupplierPerms);
        String stn2StaffToken = ensureStation2StaffToken(tenantId, stn2, staffSupplierPerms);

        // 1. Empty search cohort returns exact "0.00" with preserved response envelope
        HttpResponse<String> emptySearchRes = get(client, url + "/catalog/suppliers?search=A01-NO-MATCH-EVER", ownerToken);
        Map<String, Object> emptySearchBody = Json.object(Json.parse(emptySearchRes.body()));
        Map<String, Object> emptySearchMeta = Json.object(emptySearchBody.get("meta"));
        check(emptySearchRes.statusCode() == 200
                        && dataList(emptySearchRes).isEmpty()
                        && "0.00".equals(emptySearchMeta.get("openingBalanceTotal"))
                        && emptySearchMeta.containsKey("page")
                        && emptySearchMeta.containsKey("pageSize")
                        && emptySearchMeta.containsKey("total")
                        && emptySearchMeta.containsKey("totalPages"),
                "A-01: Empty supplier query preserves meta envelope and returns exact 0.00 openingBalanceTotal");

        // 2. Seed zero, negative, decimal, and large multi-crore (> IEEE-754 double 53-bit paise limit) suppliers via API
        String[][] specs = new String[][]{
                {"A01-ALL-ZERO-1", "A01 Zero One", "0"},
                {"A01-ALL-ZERO-2", "A01 Zero Two", "0.00"},
                {"A01-ALL-NEG-1", "A01 Negative Advance", "-45250.75"},
                {"A01-ALL-DEC-1", "A01 Decimal PointTen", "0.10"},
                {"A01-ALL-DEC-2", "A01 Decimal PointTwenty", "0.20"},
                {"A01-ALL-DEC-3", "A01 Decimal OddPaiseA", "19999.99"},
                {"A01-ALL-DEC-4", "A01 Decimal OddPaiseB", "125000.37"},
                {"A01-ALL-CRORE-POS-1", "A01 MultiCrore Alpha", "98765432109876.43"},
                {"A01-ALL-CRORE-POS-2", "A01 MultiCrore Beta", "12345678901234.58"},
                {"A01-ALL-CRORE-NEG-1", "A01 MultiCrore Offset", "-11111111101111.00"}
        };
        Map<String, String> idByCode = new java.util.LinkedHashMap<>();
        for (String[] sp : specs) {
            HttpResponse<String> created = post(client, url + "/catalog/suppliers", ownerToken,
                    Web.map("code", sp[0], "name", sp[1], "openingBalance", sp[2], "creditDays", 15, "isActive", true));
            String expectedFormatted = Money.money(new BigDecimal(sp[2]));
            check(created.statusCode() == 201 && expectedFormatted.equals(dataMap(created).get("openingBalance")),
                    "A-01: Created supplier " + sp[0] + " with exact formatted openingBalance " + expectedFormatted);
            idByCode.put(sp[0], String.valueOf(dataMap(created).get("id")));
        }

        // 3. Verify isolated sub-cohorts: zero, negative, decimal, and multi-crore (exceeding 2^53 paise float precision)
        Map<String, Object> zeroMeta = Json.object(Json.object(Json.parse(get(client, url + "/catalog/suppliers?search=A01-ALL-ZERO-", ownerToken).body())).get("meta"));
        check("0.00".equals(zeroMeta.get("openingBalanceTotal")),
                "A-01: Zero opening balance cohort sums to exact 0.00");

        Map<String, Object> negMeta = Json.object(Json.object(Json.parse(get(client, url + "/catalog/suppliers?search=A01-ALL-NEG-", ownerToken).body())).get("meta"));
        Map<String, Object> negLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + idByCode.get("A01-ALL-NEG-1"), ownerToken));
        check("-45250.75".equals(negMeta.get("openingBalanceTotal"))
                        && "-45250.75".equals(negLedger.get("openingBalance"))
                        && "-45250.75".equals(negLedger.get("closingBalance")),
                "A-01: Negative opening balance (-45250.75) matches supplier ledger opening & closing balance");

        Map<String, Object> decMeta = Json.object(Json.object(Json.parse(get(client, url + "/catalog/suppliers?search=A01-ALL-DEC-", ownerToken).body())).get("meta"));
        check("145000.66".equals(decMeta.get("openingBalanceTotal")),
                "A-01: Decimal opening balance cohort (0.10 + 0.20 + 19999.99 + 125000.37) sums to exact 145000.66");

        // 98765432109876.43 + 12345678901234.58 = 111111111011111.01 (IEEE-754 double SUM drifts to .02!)
        Map<String, Object> crorePosMeta = Json.object(Json.object(Json.parse(get(client, url + "/catalog/suppliers?search=A01-ALL-CRORE-POS-", ownerToken).body())).get("meta"));
        check("111111111011111.01".equals(crorePosMeta.get("openingBalanceTotal")),
                "A-01: Large multi-crore DECIMAL(20,2) sum preserves exact paisa precision (111111111011111.01, zero IEEE-754 double drift)");

        // 4. Confirm combined cohort & full-tenant meta.openingBalanceTotal match BigDecimal supplier-ledger calculations
        String tankStn1 = Db.str(Db.one("SELECT id FROM tanks WHERE station_id = ? LIMIT 1", stn1), "id");
        String alphaId = idByCode.get("A01-ALL-CRORE-POS-1");
        HttpResponse<String> alphaDeliv = post(client, url + "/inventory/deliveries", ownerToken,
                Web.map("stationId", stn1, "supplierId", alphaId, "tankId", tankStn1,
                        "invoiceNumber", "INV-A01-CRORE", "invoiceDate", today,
                        "quantityLitres", "500", "ratePerLitre", "100.0005", "totalAmount", "50000.25", "receiveNow", true));
        HttpResponse<String> alphaPay = post(client, url + "/finance/payments", ownerToken,
                Web.map("stationId", stn1, "partyType", "SUPPLIER", "supplierId", alphaId, "direction", "PAYOUT",
                        "mode", "BANK", "amount", "15000.10", "reference", "UTR-A01-CRORE", "occurredAt", today));
        Map<String, Object> alphaOwnerLedger = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + alphaId, ownerToken));
        check(alphaDeliv.statusCode() == 201 && alphaPay.statusCode() == 201
                        && "98765432109876.43".equals(alphaOwnerLedger.get("openingBalance"))
                        && "98765432144876.58".equals(alphaOwnerLedger.get("closingBalance")),
                "A-01: Multi-crore supplier ledger BigDecimal running calculation exact (98765432109876.43 + 50000.25 - 15000.10 = 98765432144876.58)");

        HttpResponse<String> allCohortRes = get(client, url + "/catalog/suppliers?search=A01-ALL-&pageSize=100", ownerToken);
        Map<String, Object> allCohortJson = Json.object(Json.parse(allCohortRes.body()));
        List<Map<String, Object>> allCohortRows = (List<Map<String, Object>>) allCohortJson.get("data");
        Map<String, Object> allCohortMeta = Json.object(allCohortJson.get("meta"));
        BigDecimal cohortLedgerOpeningSum = BigDecimal.ZERO;
        for (Map<String, Object> r : allCohortRows) {
            Map<String, Object> led = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + r.get("id"), ownerToken));
            cohortLedgerOpeningSum = cohortLedgerOpeningSum.add(new BigDecimal(String.valueOf(led.get("openingBalance"))));
        }
        // Expected A01-ALL sum: 0 + 0 - 45250.75 + 145000.66 + 111111111011111.01 - 11111111101111.00 = 100000099749.92? Let's verify exact BigDecimal:
        check(allCohortRows.size() == specs.length
                        && Money.money(cohortLedgerOpeningSum).equals(allCohortMeta.get("openingBalanceTotal"))
                        && "100000000009749.92".equals(allCohortMeta.get("openingBalanceTotal")),
                "A-01: Combined zero/negative/decimal/multi-crore meta.openingBalanceTotal (100000000009749.92) matches BigDecimal supplier-ledger sum");

        HttpResponse<String> fullTenantRes = get(client, url + "/catalog/suppliers?pageSize=100", ownerToken);
        Map<String, Object> fullTenantJson = Json.object(Json.parse(fullTenantRes.body()));
        List<Map<String, Object>> fullTenantRows = (List<Map<String, Object>>) fullTenantJson.get("data");
        Map<String, Object> fullTenantMeta = Json.object(fullTenantJson.get("meta"));
        BigDecimal fullLedgerOpeningSum = BigDecimal.ZERO;
        for (Map<String, Object> r : fullTenantRows) {
            Map<String, Object> led = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + r.get("id"), ownerToken));
            fullLedgerOpeningSum = fullLedgerOpeningSum.add(new BigDecimal(String.valueOf(led.get("openingBalance"))));
        }
        check(Money.money(fullLedgerOpeningSum).equals(fullTenantMeta.get("openingBalanceTotal")),
                "A-01: Full-tenant meta.openingBalanceTotal matches exact BigDecimal sum of all supplier ledgers (" + Money.money(fullLedgerOpeningSum) + ")");

        // 5. Preserve OWNER single-station & station MANAGER/STAFF visibility isolation
        HttpResponse<String> ownStn1Res = get(client, url + "/catalog/suppliers?stationId=" + stn1 + "&search=A01-ALL-&pageSize=100", ownerToken);
        check("0.00".equals(Json.object(Json.object(Json.parse(ownStn1Res.body())).get("meta")).get("openingBalanceTotal"))
                        && dataList(ownStn1Res).stream().allMatch(r -> "0.00".equals(r.get("openingBalance"))),
                "A-01: OWNER single-station supplier view preserves 0.00 openingBalance & openingBalanceTotal");

        for (String[] actor : List.of(
                new String[]{"STN1_MGR", stn1MgrToken, "35000.15"},
                new String[]{"STN1_STAFF", stn1StaffToken, "35000.15"},
                new String[]{"STN2_STAFF", stn2StaffToken, "0.00"})) {
            HttpResponse<String> dir = get(client, url + "/catalog/suppliers?stationId=all&search=A01-ALL-&pageSize=100", actor[1]);
            Map<String, Object> meta = Json.object(Json.object(Json.parse(dir.body())).get("meta"));
            Map<String, Object> led = dataMap(get(client, url + "/finance/supplier-ledger?supplierId=" + alphaId, actor[1]));
            check(dir.statusCode() == 200
                            && "0.00".equals(meta.get("openingBalanceTotal"))
                            && dataList(dir).stream().allMatch(r -> "0.00".equals(r.get("openingBalance")))
                            && !dir.body().contains("98765432109876.43")
                            && !dir.body().contains("-45250.75")
                            && "0.00".equals(led.get("openingBalance"))
                            && actor[2].equals(led.get("closingBalance")),
                    "A-01 [" + actor[0] + "]: Station-user visibility isolation preserved across multi-crore & negative suppliers");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyF17IdempotencyLeaseRecovery(
            HttpClient client,
            String url,
            Web web,
            Config base,
            String testDb,
            String ownerToken) throws Exception {
        Map<String, Object> meData = dataMap(get(client, url + "/auth/me", ownerToken));
        List<Map<String, Object>> stations = (List<Map<String, Object>>) meData.get("stations");
        String stn1 = String.valueOf(stations.get(0).get("id"));
        String tenantId = String.valueOf(Json.object(meData.get("tenant")).get("id"));

        // 1. Verify IN_PROGRESS claim uses short ~90s lease [60..120s] while COMPLETED sets 24h replay retention
        var observedLeaseSeconds = new java.util.concurrent.atomic.AtomicLong(-1);
        web.post("/test/f17-lease-inspect", ctx -> {
            ctx.requireAuth();
            String idemKey = ctx.header("idempotency-key");
            Map<String, Object> claim = Db.one(
                    "SELECT status, created_at, expires_at FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?",
                    ctx.auth.tenantId, idemKey);
            long createdEpoch = java.time.Instant.parse(Db.str(claim, "created_at")).getEpochSecond();
            long expiresEpoch = java.time.Instant.parse(Db.str(claim, "expires_at")).getEpochSecond();
            observedLeaseSeconds.set(expiresEpoch - createdEpoch);
            ctx.created(Web.map("statusDuringHandler", Db.str(claim, "status"), "leaseSeconds", expiresEpoch - createdEpoch));
        });

        String inspectKey = "f17-lease-window-01";
        HttpResponse<String> inspectRes = postWithIdem(client, url + "/test/f17-lease-inspect", ownerToken, inspectKey, Map.of("probe", 1));
        Map<String, Object> completedClaim = Db.one(
                "SELECT status, created_at, expires_at FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?",
                tenantId, inspectKey);
        long completedTtlSec = java.time.Instant.parse(Db.str(completedClaim, "expires_at")).getEpochSecond()
                - java.time.Instant.parse(Db.str(completedClaim, "created_at")).getEpochSecond();
        HttpResponse<String> inspectReplay = postWithIdem(client, url + "/test/f17-lease-inspect", ownerToken, inspectKey, Map.of("probe", 1));
        check(inspectRes.statusCode() == 201
                        && observedLeaseSeconds.get() >= 60 && observedLeaseSeconds.get() <= 120
                        && "COMPLETED".equals(Db.str(completedClaim, "status"))
                        && completedTtlSec >= 86_000 && completedTtlSec <= 86_500
                        && inspectReplay.statusCode() == 201
                        && "true".equalsIgnoreCase(inspectReplay.headers().firstValue("Idempotent-Replayed").orElse("")),
                "F-17: IN_PROGRESS claim sets short lease (" + observedLeaseSeconds.get() + "s in [60..120s]) while COMPLETED retains 24h replay TTL");

        // 2. Normal runtime exceptions (500) & validation failures (422) roll back both business writes & IN_PROGRESS claim
        web.post("/test/f17-error-rollback", ctx -> {
            ctx.requireAuth();
            String marker = ctx.str("marker");
            String mode = ctx.str("mode");
            Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, ?, '', ?, 'dirty', ?)",
                    Clock.newId("stg"), ctx.auth.tenantId, marker, Clock.nowIso());
            if ("validation".equals(mode)) {
                throw ApiException.validation("mode", "Simulated validation error");
            }
            if ("crash_exception".equals(mode)) {
                throw new IllegalStateException("Simulated unexpected runtime failure");
            }
            ctx.created(Web.map("marker", marker, "saved", true));
        });

        String errKey = "f17-rollback-retry-01";
        String errMarker = "f17.marker.rollback";
        HttpResponse<String> valFail = postWithIdem(client, url + "/test/f17-error-rollback", ownerToken, errKey,
                Map.of("marker", errMarker, "mode", "validation"));
        check(valFail.statusCode() == 422
                        && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = ?", errMarker) == 0
                        && Db.count("SELECT COUNT(*) AS c FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?", tenantId, errKey) == 0,
                "F-17: Validation failure (422) rolls back business write and removes IN_PROGRESS claim immediately");

        HttpResponse<String> rtFail = postWithIdem(client, url + "/test/f17-error-rollback", ownerToken, errKey,
                Map.of("marker", errMarker, "mode", "crash_exception"));
        check(rtFail.statusCode() == 500
                        && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = ?", errMarker) == 0
                        && Db.count("SELECT COUNT(*) AS c FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?", tenantId, errKey) == 0,
                "F-17: Unhandled runtime exception (500) rolls back business write and removes IN_PROGRESS claim immediately");

        HttpResponse<String> retrySuccess = postWithIdem(client, url + "/test/f17-error-rollback", ownerToken, errKey,
                Map.of("marker", errMarker, "mode", "ok"));
        check(retrySuccess.statusCode() == 201
                        && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = ?", errMarker) == 1,
                "F-17: Immediate retry with same Idempotency-Key succeeds cleanly after rolled-back errors");

        // 3. NEVER allow a second business transaction while the original transaction is still active (even past lease expiry!)
        var activeEntered = new java.util.concurrent.CountDownLatch(1);
        var allowFinish = new java.util.concurrent.CountDownLatch(1);
        var activeHandlerRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        web.post("/test/f17-active-tx-guard", ctx -> {
            ctx.requireAuth();
            int runNo = activeHandlerRuns.incrementAndGet();
            String idemKey = ctx.header("idempotency-key");
            // Artificially age expires_at into the past WHILE this transaction is still active!
            Db.exec("UPDATE idempotency_keys SET expires_at = '2020-01-01T00:00:00.000Z' WHERE tenant_id = ? AND `key` = ?",
                    ctx.auth.tenantId, idemKey);
            Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, ?, '', ?, ?, ?)",
                    Clock.newId("stg"), ctx.auth.tenantId, "f17.active.guard." + runNo, "ok", Clock.nowIso());
            activeEntered.countDown();
            try {
                allowFinish.await(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            ctx.created(Web.map("runNo", runNo));
        });

        String activeKey = "f17-never-double-active-01";
        var exec = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var firstCall = exec.submit(() -> postWithIdem(client, url + "/test/f17-active-tx-guard", ownerToken, activeKey, Map.of("op", "hold")));
            check(activeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "F-17: Primary transaction entered active handler");

            // Different payload while primary transaction is active -> immediate 409 CONFLICT
            HttpResponse<String> mismatchWhileActive = postWithIdem(client, url + "/test/f17-active-tx-guard", ownerToken, activeKey, Map.of("op", "tampered"));
            check(mismatchWhileActive.statusCode() == 409 && mismatchWhileActive.body().contains("different request payload"),
                    "F-17: Payload mismatch against active IN_PROGRESS transaction immediately rejected with 409");

            // Same payload retry while primary transaction is still active (even though expires_at is in the past!)
            var secondCall = exec.submit(() -> postWithIdem(client, url + "/test/f17-active-tx-guard", ownerToken, activeKey, Map.of("op", "hold")));
            Thread.sleep(250);
            check(activeHandlerRuns.get() == 1, "F-17: Second business transaction NEVER starts while original transaction is active");
            allowFinish.countDown();

            HttpResponse<String> r1 = firstCall.get();
            HttpResponse<String> r2 = secondCall.get();
            check(r1.statusCode() == 201
                            && r2.statusCode() == 201
                            && "true".equalsIgnoreCase(r2.headers().firstValue("Idempotent-Replayed").orElse(""))
                            && activeHandlerRuns.get() == 1
                            && Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` LIKE 'f17.active.guard.%'") == 1,
                    "F-17: Active transaction finishes once, commits single row, and concurrent retry replays winner without re-executing");
        } finally {
            allowFinish.countDown();
            exec.shutdownNow();
        }

        // 4. Process crash (kill -9 / destroyForcibly) + short-lease stale claim recovery + multi-JVM concurrent retry storm
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        Process crashJvm = new ProcessBuilder(javaBin, "-cp", cp, "in.petrolflow.Main")
                .redirectErrorStream(true)
                .start();
        crashJvm.destroyForcibly();
        crashJvm.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

        Process workerA = startChildApiJvmWithEnv(javaBin, cp, 4295, base.dbHost, base.dbPort, testDb, Map.of("PETROFLOW_IDEMPOTENCY_LEASE_SECONDS", "2"));
        Process workerB = startChildApiJvmWithEnv(javaBin, cp, 4296, base.dbHost, base.dbPort, testDb, Map.of("PETROFLOW_IDEMPOTENCY_LEASE_SECONDS", "2"));
        try {
            String urlA = "http://127.0.0.1:4295/api/v1";
            String urlB = "http://127.0.0.1:4296/api/v1";
            waitForHealthyJvm(client, urlA + "/health");
            waitForHealthyJvm(client, urlB + "/health");

            String crashedKey = "f17-crashed-lease-key-01";
            String crashedDesc = "F17 Crashed Lease Recovery Expense";
            Map<String, Object> expBody = Web.map("stationId", stn1, "description", crashedDesc, "amount", "321.00");
            String expHash = Passwords.sha256(Json.stringify(new java.util.TreeMap<>(expBody)));

            // Insert an unexpired IN_PROGRESS claim (simulating a JVM that crashed 1 second ago with 60s remaining)
            String nowUnexpired = Clock.nowIso();
            Db.exec("""
                    INSERT INTO idempotency_keys
                      (id, tenant_id, scope, `key`, request_hash, status, response_status, response_body, created_at, expires_at)
                    VALUES (?, ?, 'POST /finance/expenses', ?, ?, 'IN_PROGRESS', NULL, NULL, ?, ?)
                    """, Clock.newId("idm"), tenantId, crashedKey, expHash, nowUnexpired, Clock.addSecondsIso(nowUnexpired, 60));

            // Mismatched payload on unexpired IN_PROGRESS -> immediate 409 CONFLICT
            HttpResponse<String> unexpiredMismatch = postWithIdem(client, urlA + "/finance/expenses", ownerToken, crashedKey,
                    Web.map("stationId", stn1, "description", crashedDesc, "amount", "999.00"));
            check(unexpiredMismatch.statusCode() == 409 && unexpiredMismatch.body().contains("different request payload"),
                    "F-17: Unexpired IN_PROGRESS claim rejects mismatched payload with 409 CONFLICT");

            // Now set short 2-second lease on the orphaned crashed claim and wait 2.2s for natural short-lease expiry
            String shortNow = Clock.nowIso();
            Db.exec("UPDATE idempotency_keys SET expires_at = ? WHERE tenant_id = ? AND `key` = ?",
                    Clock.addSecondsIso(shortNow, 2), tenantId, crashedKey);
            Thread.sleep(2300);

            // Fire 6 simultaneous retries across BOTH child JVMs at the exact moment after the short lease expired!
            var stormPool = java.util.concurrent.Executors.newFixedThreadPool(6);
            try {
                var stormBarrier = new java.util.concurrent.CyclicBarrier(6);
                var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
                for (int i = 0; i < 6; i++) {
                    final String target = (i % 2 == 0) ? urlA : urlB;
                    futures.add(stormPool.submit(() -> {
                        stormBarrier.await();
                        return postWithIdem(client, target + "/finance/expenses", ownerToken, crashedKey, expBody);
                    }));
                }
                int ok201 = 0;
                int inProg409 = 0;
                String winnerId = null;
                for (var f : futures) {
                    HttpResponse<String> r = f.get();
                    if (r.statusCode() == 201) {
                        ok201++;
                        String rid = String.valueOf(dataMap(r).get("id"));
                        if (winnerId == null) winnerId = rid;
                        check(winnerId.equals(rid), "F-17: Every 201 response in concurrent stale-claim recovery returns identical expense ID");
                    } else if (r.statusCode() == 409) {
                        inProg409++;
                    }
                }
                long committedExpenses = Db.count("SELECT COUNT(*) AS c FROM expenses WHERE tenant_id = ? AND description = ?", tenantId, crashedDesc);
                Map<String, Object> recoveredRow = Db.one("SELECT status FROM idempotency_keys WHERE tenant_id = ? AND `key` = ?", tenantId, crashedKey);
                check(ok201 >= 1 && (ok201 + inProg409) == 6
                                && committedExpenses == 1
                                && recoveredRow != null && "COMPLETED".equals(Db.str(recoveredRow, "status")),
                        "F-17: Stale crashed IN_PROGRESS lease recovered after ~2s across 6 concurrent cross-JVM retries with strictly 1 expense created");
            } finally {
                stormPool.shutdownNow();
            }
        } finally {
            workerA.destroyForcibly();
            workerB.destroyForcibly();
            workerA.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            workerB.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void verifyF18MysqlConnectionPooling(Config base, String testDb) throws Exception {
        // 1. Re-open Db with bounded pool size = 4 and verify concurrent connections, single-connection tx pinning, and zero leaks
        Config poolCfg = Config.load(Map.ofEntries(
                Map.entry("PETROFLOW_DB_HOST", base.dbHost),
                Map.entry("PETROFLOW_DB_PORT", String.valueOf(base.dbPort)),
                Map.entry("PETROFLOW_DB_NAME", testDb),
                Map.entry("PETROFLOW_DB_USER", base.dbUser),
                Map.entry("PETROFLOW_DB_PASSWORD", base.dbPassword),
                Map.entry("PETROFLOW_DB_POOL_MAX", "4"),
                Map.entry("PETROFLOW_DB_CONNECT_TIMEOUT_MS", "5000"),
                Map.entry("PETROFLOW_DB_SOCKET_TIMEOUT_MS", "15000"),
                Map.entry("PETROFLOW_DB_POOL_TIMEOUT_MS", "5000")));
        Db.open(poolCfg);
        check(Db.poolMaxSize() == 4 && Db.poolCreatedCount() == 1 && Db.poolActiveCount() == 0 && Db.poolIdleCount() == 1,
                "F-18: Pool initializes bounded (max=4) with 1 warm connection and 0 active permits");

        // Verify 4 simultaneous readers acquire 4 distinct pooled sockets concurrently and return all 4 cleanly
        var holdBarrier = new java.util.concurrent.CyclicBarrier(4);
        var connIds = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var maxObservedActive = new java.util.concurrent.atomic.AtomicInteger(0);
        var poolExec = java.util.concurrent.Executors.newFixedThreadPool(12);
        try {
            var fourSimultaneous = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                fourSimultaneous.add(poolExec.submit(() -> Db.tx(() -> {
                    String cid = Db.str(Db.one("SELECT CONNECTION_ID() AS cid"), "cid");
                    connIds.add(cid);
                    maxObservedActive.accumulateAndGet(Db.poolActiveCount(), Math::max);
                    try {
                        holdBarrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    // Verify nested Db.tx and repeated queries stay pinned to the exact same connection
                    String nestedCid = Db.tx(() -> Db.str(Db.one("SELECT CONNECTION_ID() AS cid"), "cid"));
                    if (!cid.equals(nestedCid)) {
                        throw new AssertionError("Connection changed mid-transaction: " + cid + " vs " + nestedCid);
                    }
                    return null;
                })));
            }
            for (var f : fourSimultaneous) f.get();

            check(connIds.size() == 4 && maxObservedActive.get() == 4 && Db.poolCreatedCount() == 4
                            && Db.poolActiveCount() == 0 && Db.poolIdleCount() == 4,
                    "F-18: 4 concurrent transactions execute in parallel across 4 distinct connections, keep nested tx pinned to 1 connection, and return 4/4 idle");

            // Stress 24 tasks across 12 threads (> maxPoolSize=4), mixing commits, RuntimeException rollbacks, and Error rollbacks
            var stressTasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 24; i++) {
                final int idx = i;
                stressTasks.add(poolExec.submit(() -> {
                    String key = "f18.stress." + idx;
                    try {
                        Db.tx(() -> {
                            maxObservedActive.accumulateAndGet(Db.poolActiveCount(), Math::max);
                            Db.exec("INSERT INTO app_settings (id, tenant_id, station_id, `key`, value, updated_at) VALUES (?, '', '', ?, '1', ?)",
                                    Clock.newId("stg"), key, Clock.nowIso());
                            if (idx % 3 == 1) throw new IllegalStateException("rollback-rt");
                            if (idx % 3 == 2) throw new AssertionError("rollback-err");
                            return null;
                        });
                    } catch (IllegalStateException | AssertionError expected) {
                        // expected for 2/3 of tasks
                    }
                    return null;
                }));
            }
            for (var f : stressTasks) f.get();

            long committedStress = Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` LIKE 'f18.stress.%'");
            check(committedStress == 8
                            && maxObservedActive.get() <= 4
                            && Db.poolCreatedCount() <= 4
                            && Db.poolActiveCount() == 0
                            && Db.poolIdleCount() == Db.poolCreatedCount(),
                    "F-18: 24-task stress across 12 threads commits 8/24, rolls back 16/24, never exceeds maxPoolSize=4, and leaks 0 permits/sockets");

            // Verify FOREIGN_KEY_CHECKS=0 inside withConnection is automatically reset to 1 when connection returns to pool
            Db.withConnection(() -> {
                Db.exec("SET FOREIGN_KEY_CHECKS = 0");
                return null;
            });
            for (int i = 0; i < 4; i++) {
                long fk = Db.count("SELECT @@SESSION.foreign_key_checks AS fk");
                check(fk == 1, "F-18: Pooled connection #" + (i + 1) + " has FOREIGN_KEY_CHECKS=1 restored after pool release");
            }
        } finally {
            poolExec.shutdownNow();
        }

        // 2. Embedded MySQL wire-protocol fault-injection server: test DB_POOL_EXHAUSTED, socket timeout, and 1213 deadlock retry
        try (MockMysqlFaultServer mock = new MockMysqlFaultServer()) {
            Config faultCfg = Config.load(Map.ofEntries(
                    Map.entry("PETROFLOW_DB_HOST", "127.0.0.1"),
                    Map.entry("PETROFLOW_DB_PORT", String.valueOf(mock.port())),
                    Map.entry("PETROFLOW_DB_NAME", "faultdb"),
                    Map.entry("PETROFLOW_DB_USER", "root"),
                    Map.entry("PETROFLOW_DB_PASSWORD", ""),
                    Map.entry("PETROFLOW_DB_POOL_MAX", "2"),
                    Map.entry("PETROFLOW_DB_CONNECT_TIMEOUT_MS", "1500"),
                    Map.entry("PETROFLOW_DB_SOCKET_TIMEOUT_MS", "2000"),
                    Map.entry("PETROFLOW_DB_POOL_TIMEOUT_MS", "1000")));
            Db.open(faultCfg);

            // (a) Pool acquire timeout when all 2 connections are held
            var hold2 = new java.util.concurrent.CountDownLatch(2);
            var release2 = new java.util.concurrent.CountDownLatch(1);
            var fExec = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                var h1 = fExec.submit(() -> Db.tx(() -> { hold2.countDown(); awaitQuietly(release2); return 1; }));
                var h2 = fExec.submit(() -> Db.tx(() -> { hold2.countDown(); awaitQuietly(release2); return 2; }));
                check(hold2.await(3, java.util.concurrent.TimeUnit.SECONDS), "F-18: 2 mock connections held");
                String exhaustedCode = null;
                try {
                    Db.one("SELECT 1");
                } catch (ApiException ex) {
                    exhaustedCode = ex.code;
                }
                release2.countDown();
                h1.get();
                h2.get();
                check("DB_POOL_EXHAUSTED".equals(exhaustedCode) && Db.poolActiveCount() == 0,
                        "F-18: Pool acquire timeout throws 503 DB_POOL_EXHAUSTED cleanly and releases all permits afterward");

                // (b) Transient MySQL 1213 deadlock inside Db.tx automatically retries and succeeds
                mock.failNextQueryWithDeadlock1213.set(true);
                int attempts = Db.tx(() -> {
                    Db.exec("UPDATE demo SET x = 1");
                    return mock.deadlockAttempts.get();
                });
                check(attempts >= 1 && !mock.failNextQueryWithDeadlock1213.get() && Db.poolActiveCount() == 0,
                        "F-18: Outermost Db.tx transparently retries transient MySQL 1213 deadlock and succeeds");

                // (c) Socket read timeout discards hung socket and leaks 0 pool permits
                mock.stallNextQueryMs.set(3500);
                String timeoutCode = null;
                try {
                    Db.tx(() -> Db.one("SELECT SLEEP(5)"));
                } catch (ApiException ex) {
                    timeoutCode = ex.code;
                }
                mock.stallNextQueryMs.set(0);
                Map<String, Object> recovered = Db.one("SELECT 1");
                check("DB_UNAVAILABLE".equals(timeoutCode) && recovered != null && Db.poolActiveCount() == 0,
                        "F-18: Socket timeout aborts hung transaction (503 DB_UNAVAILABLE), evicts broken socket, and recovers cleanly with 0 leaked permits");
            } finally {
                release2.countDown();
                fExec.shutdownNow();
            }
        } finally {
            // Restore main testDb pool for clean teardown
            Config restoreCfg = Config.load(Map.of(
                    "PETROFLOW_DB_HOST", base.dbHost,
                    "PETROFLOW_DB_PORT", String.valueOf(base.dbPort),
                    "PETROFLOW_DB_NAME", testDb,
                    "PETROFLOW_DB_USER", base.dbUser,
                    "PETROFLOW_DB_PASSWORD", base.dbPassword));
            Db.open(restoreCfg);
        }
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static Process startChildApiJvmWithEnv(
            String javaBin, String cp, int port, String dbHost, int dbPort, String dbName, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp, "in.petrolflow.Main");
        Map<String, String> env = pb.environment();
        env.put("NODE_ENV", "development");
        env.put("HOST", "127.0.0.1");
        env.put("PORT", String.valueOf(port));
        env.put("MYSQL_HOST", dbHost);
        env.put("MYSQL_PORT", String.valueOf(dbPort));
        env.put("PETROFLOW_DB_NAME", dbName);
        env.put("SEED_DEMO_DATA", "false");
        env.putAll(extraEnv);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread drain = new Thread(() -> {
            try (var in = p.getInputStream()) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (Exception ignored) {}
        });
        drain.setDaemon(true);
        drain.start();
        return p;
    }

    private static final class MockMysqlFaultServer implements AutoCloseable {
        private final java.net.ServerSocket server;
        private final java.util.concurrent.ExecutorService acceptPool = java.util.concurrent.Executors.newCachedThreadPool();
        final java.util.concurrent.atomic.AtomicBoolean failNextQueryWithDeadlock1213 = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger deadlockAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger stallNextQueryMs = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.ConcurrentLinkedQueue<String> handshakeSchemas = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.ConcurrentLinkedQueue<String> sqlCommands = new java.util.concurrent.ConcurrentLinkedQueue<>();

        MockMysqlFaultServer() throws Exception {
            this.server = new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            acceptPool.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        java.net.Socket s = server.accept();
                        acceptPool.submit(() -> handleClient(s));
                    } catch (Exception ignored) {
                        break;
                    }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        private void handleClient(java.net.Socket s) {
            try (s; var in = s.getInputStream(); var out = s.getOutputStream()) {
                s.setTcpNoDelay(true);
                // Send minimal MySQL v10 greeting with mysql_native_password
                var greet = new java.io.ByteArrayOutputStream();
                greet.write(10);
                greet.write("8.0.36-mock\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                greet.write(new byte[]{1, 0, 0, 0}); // conn id
                greet.write("12345678".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); // scramble1
                greet.write(0);
                greet.write(new byte[]{(byte) 0xff, (byte) 0xf7}); // cap lower
                greet.write(0x21); // utf8
                greet.write(new byte[]{2, 0}); // status
                greet.write(new byte[]{(byte) 0xff, (byte) 0x81}); // cap upper
                greet.write(21); // auth plugin data len
                greet.write(new byte[10]); // reserved
                greet.write("123456789012\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); // scramble2
                greet.write("mysql_native_password\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writePkt(out, 0, greet.toByteArray());
                byte[] handshakeResponse = readPkt(in);
                handshakeSchemas.add(readHandshakeSchema(handshakeResponse));
                writePkt(out, 2, new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00}); // OK

                while (!s.isClosed()) {
                    byte[] cmd = readPkt(in);
                    if (cmd == null || cmd.length == 0 || cmd[0] == 0x01) break;
                    String sql = new String(cmd, 1, cmd.length - 1, java.nio.charset.StandardCharsets.UTF_8).trim();
                    sqlCommands.add(sql);
                    if (sql.toUpperCase().contains("SLEEP")) {
                        int stall = stallNextQueryMs.getAndSet(0);
                        if (stall > 0) Thread.sleep(stall);
                    }
                    if (sql.toUpperCase().startsWith("UPDATE DEMO") && failNextQueryWithDeadlock1213.compareAndSet(true, false)) {
                        deadlockAttempts.incrementAndGet();
                        var err = new java.io.ByteArrayOutputStream();
                        err.write(0xff);
                        err.write(1213 & 0xff);
                        err.write((1213 >> 8) & 0xff);
                        err.write("#40001Deadlock found when trying to get lock; try restarting transaction".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        writePkt(out, 1, err.toByteArray());
                        continue;
                    }
                    if (sql.toUpperCase().startsWith("SELECT")) {
                        sendSingleRowOne(out);
                    } else {
                        writePkt(out, 1, new byte[]{0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00});
                    }
                }
            } catch (Exception ignored) {}
        }

        private static void sendSingleRowOne(java.io.OutputStream out) throws Exception {
            writePkt(out, 1, new byte[]{1}); // 1 column
            var col = new java.io.ByteArrayOutputStream();
            writeLenStr(col, "def"); writeLenStr(col, ""); writeLenStr(col, ""); writeLenStr(col, "");
            writeLenStr(col, "v"); writeLenStr(col, "v");
            col.write(0x0c); col.write(new byte[]{0x21, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00});
            writePkt(out, 2, col.toByteArray());
            writePkt(out, 3, new byte[]{(byte) 0xfe, 0, 0, 2, 0}); // EOF
            writePkt(out, 4, new byte[]{1, '1'}); // row "1"
            writePkt(out, 5, new byte[]{(byte) 0xfe, 0, 0, 2, 0}); // EOF
        }

        private static void writeLenStr(java.io.ByteArrayOutputStream b, String s) throws Exception {
            byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            b.write(raw.length);
            b.write(raw);
        }

        private static String readHandshakeSchema(byte[] packet) {
            if (packet == null || packet.length < 33) return "";
            int cursor = 32; // capabilities, max packet, charset, reserved bytes
            while (cursor < packet.length && packet[cursor] != 0) cursor++;
            if (cursor >= packet.length) return "";
            cursor++;
            if (cursor >= packet.length) return "";
            int authLength = packet[cursor++] & 0xff;
            if (authLength == 0xfc && cursor + 2 <= packet.length) {
                authLength = (packet[cursor] & 0xff) | ((packet[cursor + 1] & 0xff) << 8);
                cursor += 2;
            } else if (authLength == 0xfd && cursor + 3 <= packet.length) {
                authLength = (packet[cursor] & 0xff) | ((packet[cursor + 1] & 0xff) << 8) | ((packet[cursor + 2] & 0xff) << 16);
                cursor += 3;
            }
            cursor = Math.min(packet.length, cursor + authLength);
            int end = cursor;
            while (end < packet.length && packet[end] != 0) end++;
            return new String(packet, cursor, end - cursor, java.nio.charset.StandardCharsets.UTF_8);
        }

        private static byte[] readPkt(java.io.InputStream in) throws Exception {
            byte[] hdr = in.readNBytes(4);
            if (hdr.length < 4) return null;
            int len = (hdr[0] & 0xff) | ((hdr[1] & 0xff) << 8) | ((hdr[2] & 0xff) << 16);
            return in.readNBytes(len);
        }

        private static void writePkt(java.io.OutputStream out, int seq, byte[] body) throws Exception {
            out.write(new byte[]{(byte) (body.length & 0xff), (byte) ((body.length >> 8) & 0xff), (byte) ((body.length >> 16) & 0xff), (byte) seq});
            out.write(body);
            out.flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            acceptPool.shutdownNow();
        }
    }

    private static String findCustomerBal(List<Map<String, Object>> rows, String customerId) {
        return rows.stream().filter(r -> customerId.equals(r.get("id"))).map(r -> String.valueOf(r.get("balance"))).findFirst().orElse(null);
    }

    private static String findLubeStock(List<Map<String, Object>> rows, String productId) {
        return rows.stream().filter(r -> productId.equals(r.get("id"))).map(r -> String.valueOf(r.get("stockQty"))).findFirst().orElse(null);
    }

    private static String ensureStation2StaffToken(String tenantId, String stn2, List<String> perms) {
        String email = "s2.staff.isolated@sunrisefuels.in";
        Map<String, Object> existing = Db.one("SELECT id FROM users WHERE email = ?", email);
        String uid = existing != null ? Db.str(existing, "id") : Clock.newId("usr");
        String now = Clock.nowIso();
        if (existing == null) {
            Db.exec("INSERT INTO users (id, tenant_id, email, name, password_hash, role_key, status, granted_permissions, created_at, updated_at) VALUES (?, ?, ?, 'S2 Isolated Staff', ?, 'STAFF', 'ACTIVE', ?, ?, ?)",
                    uid, tenantId, email, Passwords.hash("PetroFlow@2025"), Json.stringify(perms), now, now);
            Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)",
                    Clock.newId("usa"), uid, stn2, now);
        } else {
            Db.exec("UPDATE users SET granted_permissions = ?, updated_at = ? WHERE id = ?", Json.stringify(perms), now, uid);
            Db.exec("DELETE FROM user_station_access WHERE user_id = ?", uid);
            Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)",
                    Clock.newId("usa"), uid, stn2, now);
            Db.exec("DELETE FROM sessions WHERE user_id = ?", uid);
        }
        String raw = Passwords.randomToken();
        Db.exec("INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
                Clock.newId("ses"), uid, Passwords.sha256(raw), Clock.addDays(Clock.todayIst(), 1) + "T23:59:59.000Z", now, now);
        return raw;
    }

    private static String ensureStation1ManagerToken(String tenantId, String stn1) {
        Map<String, Object> existing = Db.one("SELECT id FROM users WHERE tenant_id = ? AND role_key = 'MANAGER' LIMIT 1", tenantId);
        String uid = existing != null ? Db.str(existing, "id") : Clock.newId("usr");
        String now = Clock.nowIso();
        if (existing == null) {
            Db.exec("INSERT INTO users (id, tenant_id, email, name, password_hash, role_key, status, granted_permissions, created_at, updated_at) VALUES (?, ?, 's1.manager.p3@sunrisefuels.in', 'S1 Manager', ?, 'MANAGER', 'ACTIVE', '[]', ?, ?)",
                    uid, tenantId, Passwords.hash("PetroFlow@2025"), now, now);
        } else {
            Db.exec("UPDATE users SET status = 'ACTIVE', updated_at = ? WHERE id = ?", now, uid);
            Db.exec("DELETE FROM user_station_access WHERE user_id = ?", uid);
            Db.exec("DELETE FROM sessions WHERE user_id = ?", uid);
        }
        Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)",
                Clock.newId("usa"), uid, stn1, now);
        String raw = Passwords.randomToken();
        Db.exec("INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
                Clock.newId("ses"), uid, Passwords.sha256(raw), Clock.addDays(Clock.todayIst(), 1) + "T23:59:59.000Z", now, now);
        return raw;
    }

    private static String ensureStation1StaffToken(String tenantId, String stn1, List<String> perms) throws Exception {
        String email = "s1.staff.isolated@sunrisefuels.in";
        Map<String, Object> existing = Db.one("SELECT id FROM users WHERE email = ?", email);
        String uid = existing != null ? Db.str(existing, "id") : Clock.newId("usr");
        String now = Clock.nowIso();
        if (existing == null) {
            Db.exec("INSERT INTO users (id, tenant_id, email, name, password_hash, role_key, status, granted_permissions, created_at, updated_at) VALUES (?, ?, ?, 'S1 Isolated Staff', ?, 'STAFF', 'ACTIVE', ?, ?, ?)",
                    uid, tenantId, email, Passwords.hash("PetroFlow@2025"), Json.stringify(perms), now, now);
            Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)",
                    Clock.newId("usa"), uid, stn1, now);
        } else {
            Db.exec("UPDATE users SET granted_permissions = ?, updated_at = ? WHERE id = ?", Json.stringify(perms), now, uid);
            Db.exec("DELETE FROM user_station_access WHERE user_id = ?", uid);
            Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)",
                    Clock.newId("usa"), uid, stn1, now);
            Db.exec("DELETE FROM sessions WHERE user_id = ?", uid);
        }
        String raw = Passwords.randomToken();
        Db.exec("INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
                Clock.newId("ses"), uid, Passwords.sha256(raw), Clock.addDays(Clock.todayIst(), 1) + "T23:59:59.000Z", now, now);
        return raw;
    }

    private static Map<String, Object> dataMap(HttpResponse<String> res) {
        return Json.object(Json.object(Json.parse(res.body())).get("data"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataList(HttpResponse<String> res) {
        return (List<Map<String, Object>>) Json.object(Json.parse(res.body())).get("data");
    }

    private static HttpResponse<String> get(HttpClient client, String uri, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------------------------------
    // Phase 0 security remediation — A4 (production demo security) and A5 (login throttling)
    // -----------------------------------------------------------------------------------------

    private static Config testConfig(String testDb, Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PETROFLOW_DB_NAME", testDb);
        env.putAll(extra);
        return Config.load(env);
    }

    /** Runs an action that must throw IllegalStateException; returns the message (or null on failure). */
    private static String expectConfigFailure(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            check(true, label);
            return expected.getMessage() == null ? "" : expected.getMessage();
        }
        check(false, label + " (no failure was raised)");
        return null;
    }

    /** A4: production defaults and explicit demo enablement rejection. No database required. */
    private static void testProductionDemoSecurity() {
        Config production = testConfig("petroflow_test", Map.of("NODE_ENV", "production"));
        check(production.production, "A4: NODE_ENV=production is recognised as production");
        check("production".equalsIgnoreCase(Config.PRODUCTION_ENV), "A4: the production environment marker is stable");
        check(!production.seedDemo, "A4: demo seeding defaults to false in production (never silently creates demo users)");
        check(!production.demoTools, "A4: demo tools default to false in production");
        check(production.cookieSecure, "A4: production keeps secure session cookies by default");

        Config uppercase = testConfig("petroflow_test", Map.of("NODE_ENV", "PRODUCTION"));
        check(uppercase.production && !uppercase.seedDemo && !uppercase.demoTools,
                "A4: production detection is case-insensitive");

        Config dev = testConfig("petroflow_test", Map.of("NODE_ENV", "development"));
        check(!dev.production, "A4: development is not treated as production");
        check(dev.seedDemo && dev.demoTools, "A4: development keeps the existing demo experience (seed + tools on)");

        Config noEnv = testConfig("petroflow_test", Map.of());
        check(!noEnv.production, "A4: an unset NODE_ENV does not become production by accident");

        String seedMessage = expectConfigFailure(
                () -> testConfig("petroflow_test", Map.of("NODE_ENV", "production", "SEED_DEMO_DATA", "true")),
                "A4: SEED_DEMO_DATA=true in production fails startup configuration");
        check(seedMessage != null && seedMessage.contains("SEED_DEMO_DATA"),
                "A4: the seeding failure names the offending variable");

        String toolsMessage = expectConfigFailure(
                () -> testConfig("petroflow_test", Map.of("NODE_ENV", "production", "ENABLE_DEMO_TOOLS", "true")),
                "A4: ENABLE_DEMO_TOOLS=true in production fails startup configuration");
        check(toolsMessage != null && toolsMessage.contains("ENABLE_DEMO_TOOLS"),
                "A4: the demo-tools failure names the offending variable");

        Config explicitOff = testConfig("petroflow_test",
                Map.of("NODE_ENV", "production", "SEED_DEMO_DATA", "false", "ENABLE_DEMO_TOOLS", "false"));
        check(!explicitOff.seedDemo && !explicitOff.demoTools,
                "A4: explicitly disabled demo settings are accepted in production");
    }

    /** A4: demo-account detection finds seeded logins without touching unrelated users. */
    private static void testDemoAccountDetection() {
        List<Map<String, Object>> seeded = List.of(
                Map.of("id", "u1", "email", "superadmin@petroflow.dev", "is_demo", 1),
                Map.of("id", "u2", "email", "owner@sunrisefuels.in", "is_demo", 1),
                Map.of("id", "u3", "email", "manager@sunrisefuels.in", "is_demo", 1),
                Map.of("id", "u4", "email", "attendant@sunrisefuels.in", "is_demo", 1));
        List<DemoGuard.Finding> findings = DemoGuard.scan(seeded);
        check(findings.size() == 4, "A4: all four seeded demo accounts are detected");
        check(findings.stream().anyMatch(f -> "owner@sunrisefuels.in".equals(f.email())),
                "A4: detection reports the seeded demo administrator by email");

        List<Map<String, Object>> unflaggedDemoEmail = List.of(
                Map.of("id", "u5", "email", " owner@sunrisefuels.in ", "is_demo", 0));
        check(DemoGuard.scan(unflaggedDemoEmail).size() == 1,
                "A4: a seeded demo email is still detected after is_demo is cleared (case/space tolerant)");

        List<Map<String, Object>> unrelated = List.of(
                Map.of("id", "u6", "email", "dealer@realpump.in", "is_demo", 0),
                Map.of("id", "u7", "email", "accountant@realpump.in", "is_demo", 0));
        check(DemoGuard.scan(unrelated).isEmpty(),
                "A4: unrelated legitimate users are never flagged as demo accounts");
        check(DemoGuard.scan(List.of()).isEmpty(), "A4: an empty user list produces no findings");

        String message = DemoGuard.describe(findings);
        check(message.contains("owner@sunrisefuels.in") && message.contains("did not delete"),
                "A4: the failure message names the accounts and confirms nothing was modified");
        check(!message.toLowerCase(java.util.Locale.ROOT).contains("pbkdf2") && !message.contains("$"),
                "A4: the failure message contains no password hashes or tokens");
        check(message.contains("SEED_DEMO_DATA=false") && message.contains("ENABLE_DEMO_TOOLS=false"),
                "A4: the failure message restates the required production settings");

        // The detection list must be derived from the seed file that actually ships with this build.
        Config locator = Config.load(Map.of());
        java.util.Set<String> fromSeed = DemoGuard.seededEmails(locator.mysqlDir().resolve("seed.sql"));
        check(fromSeed.size() == DemoGuard.SEEDED_DEMO_EMAILS.size(),
                "A4: seed.sql declares exactly the expected demo accounts (" + fromSeed.size() + ")");
        check(new java.util.TreeSet<>(DemoGuard.SEEDED_DEMO_EMAILS).equals(fromSeed),
                "A4: the built-in demo account list matches backend/db/mysql/seed.sql");
        check(DemoGuard.scan(seeded, fromSeed).size() == 4,
                "A4: scan() with seed-derived accounts finds every seeded demo login");
        check(DemoGuard.seededEmails(locator.mysqlDir().resolve("does-not-exist.sql")).isEmpty(),
                "A4: a missing seed file yields no demo emails instead of failing startup");
    }

    /** In-memory Store used to exercise the throttle policy without MySQL. */
    private static final class FakeThrottleStore implements LoginThrottle.Store {
        private final List<String[]> rows = new ArrayList<>(); // id, ipKey, accountKey, attemptedAt
        private int seq = 0;

        @Override public synchronized String record(String ipKey, String accountKey, String attemptedAtIso) {
            String id = "fake-" + (++seq);
            rows.add(new String[]{id, ipKey, accountKey, attemptedAtIso});
            return id;
        }
        @Override public synchronized void release(String id) {
            rows.removeIf(row -> row[0].equals(id));
        }
        @Override public synchronized long countByIp(String ipKey, String sinceIso) {
            return rows.stream().filter(row -> row[1].equals(ipKey) && row[3].compareTo(sinceIso) >= 0).count();
        }
        @Override public synchronized long countByAccount(String accountKey, String sinceIso) {
            return rows.stream().filter(row -> row[2].equals(accountKey) && row[3].compareTo(sinceIso) >= 0).count();
        }
        @Override public synchronized long clearAccount(String accountKey) {
            int before = rows.size();
            rows.removeIf(row -> row[2].equals(accountKey));
            return before - rows.size();
        }
        @Override public synchronized String oldestByIp(String ipKey, String sinceIso) {
            return rows.stream().filter(row -> row[1].equals(ipKey) && row[3].compareTo(sinceIso) >= 0)
                    .map(row -> row[3]).min(String::compareTo).orElse(null);
        }
        @Override public synchronized String oldestByAccount(String accountKey, String sinceIso) {
            return rows.stream().filter(row -> row[2].equals(accountKey) && row[3].compareTo(sinceIso) >= 0)
                    .map(row -> row[3]).min(String::compareTo).orElse(null);
        }
        @Override public synchronized int pruneBefore(String beforeIso) {
            int before = rows.size();
            rows.removeIf(row -> row[3].compareTo(beforeIso) < 0);
            return before - rows.size();
        }
        synchronized int size() { return rows.size(); }
    }

    /** A5: throttle policy — per-IP, per-account, windows, success scoping, concurrency. */
    private static void testLoginThrottlePolicy() throws Exception {
        String now = Clock.nowIso();
        Config oneAccountManyIps = testConfig("petroflow_test", Map.of(
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", "3",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "1000",
                "PETROFLOW_LOGIN_WINDOW_SECONDS", "900"));
        check(oneAccountManyIps.loginMaxAttemptsPerAccount == 3 && oneAccountManyIps.loginMaxAttemptsPerIp == 1000,
                "A5: thresholds and window are configurable");

        FakeThrottleStore store = new FakeThrottleStore();
        String victim = "owner@sunrisefuels.in";
        int blockedAt = -1;
        for (int i = 1; i <= 10; i++) {
            LoginThrottle.Decision d = LoginThrottle.evaluate(store, oneAccountManyIps, "198.51.100." + i, victim, now);
            if (!d.allowed()) { blockedAt = i; check(LoginThrottle.SCOPE_ACCOUNT.equals(d.limitedScope()),
                    "A5: an account spread across many IPs is throttled on the account bucket"); break; }
        }
        check(blockedAt == 4, "A5: one account from many IPs is throttled after the configured account limit (blocked at " + blockedAt + ")");

        Config oneIpManyAccounts = testConfig("petroflow_test", Map.of(
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", "1000",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "3",
                "PETROFLOW_LOGIN_WINDOW_SECONDS", "900"));
        FakeThrottleStore ipStore = new FakeThrottleStore();
        int ipBlockedAt = -1;
        for (int i = 1; i <= 10; i++) {
            LoginThrottle.Decision d = LoginThrottle.evaluate(ipStore, oneIpManyAccounts, "203.0.113.9", "user" + i + "@example.com", now);
            if (!d.allowed()) { ipBlockedAt = i; check(LoginThrottle.SCOPE_IP.equals(d.limitedScope()),
                    "A5: spraying many accounts from one IP is throttled on the IP bucket"); break; }
        }
        check(ipBlockedAt == 4, "A5: many accounts from one IP are throttled after the configured IP limit (blocked at " + ipBlockedAt + ")");
        check(ipStore.size() == 3, "A5: rejected attempts are released and do not consume quota");

        // Normal success while under the limit.
        FakeThrottleStore healthy = new FakeThrottleStore();
        LoginThrottle.evaluate(healthy, oneAccountManyIps, "192.0.2.1", victim, now);
        LoginThrottle.evaluate(healthy, oneAccountManyIps, "192.0.2.1", victim, now);
        check(LoginThrottle.evaluate(healthy, oneAccountManyIps, "192.0.2.1", victim, now).allowed(),
                "A5: a normal sign-in is not throttled while under both limits");

        // Account identifiers are normalised (trim + lowercase) so casing cannot reset a counter.
        FakeThrottleStore normalised = new FakeThrottleStore();
        for (int i = 0; i < 3; i++) LoginThrottle.evaluate(normalised, oneAccountManyIps, "192.0.2.2", "OWNER@SunriseFuels.in ", now);
        check(!LoginThrottle.evaluate(normalised, oneAccountManyIps, "192.0.2.3", "owner@sunrisefuels.in", now).allowed(),
                "A5: casing/whitespace variations of the same account share one bucket");
        check("owner@sunrisefuels.in".equals(LoginThrottle.normalizeAccount("  OWNER@SunriseFuels.IN  ")),
                "A5: account identifiers are normalised before hashing");

        // Window expiry — no permanent lockout.
        String later = Clock.addSecondsIso(now, 901);
        check(LoginThrottle.evaluate(store, oneAccountManyIps, "198.51.100.50", victim, later).allowed(),
                "A5: sign-in resumes automatically once the window has elapsed (no permanent lockout)");
        String partial = Clock.addSecondsIso(now, 5);
        check(!LoginThrottle.evaluate(store, oneAccountManyIps, "198.51.100.51", victim, partial).allowed(),
                "A5: the throttle still applies inside the window");

        // A rejected request reports a retry delay instead of a permanent denial.
        LoginThrottle.Decision limited = LoginThrottle.evaluate(store, oneAccountManyIps, "198.51.100.52", victim, now);
        check(!limited.allowed() && limited.retryAfterSeconds() >= 1
                        && limited.retryAfterSeconds() <= oneAccountManyIps.loginWindowSeconds + 1L,
                "A5: a throttled response carries a bounded retry-after hint");

        // Success clears only this account's history.
        FakeThrottleStore scoped = new FakeThrottleStore();
        LoginThrottle.evaluate(scoped, oneIpManyAccounts, "203.0.113.10", "a@example.com", now);
        LoginThrottle.evaluate(scoped, oneIpManyAccounts, "203.0.113.10", "b@example.com", now);
        LoginThrottle.clearOnSuccess(scoped, " A@Example.com ", null);
        check(scoped.countByAccount(LoginThrottle.key(LoginThrottle.SCOPE_ACCOUNT, "a@example.com"),
                Clock.addSecondsIso(now, -60)) == 0, "A5: a successful sign-in clears that account's own attempts");
        check(scoped.countByIp(LoginThrottle.key(LoginThrottle.SCOPE_IP, "203.0.113.10"),
                Clock.addSecondsIso(now, -60)) == 1, "A5: a successful sign-in never clears another account's history");

        // Keys are hashes, never raw identifiers.
        String ipKey = LoginThrottle.key(LoginThrottle.SCOPE_IP, "203.0.113.10");
        check(ipKey.length() == 64 && !ipKey.contains("203.0.113.10") && !ipKey.contains("@"),
                "A5: stored throttle keys are SHA-256 hashes, never raw IPs or emails");
        check(!LoginThrottle.key(LoginThrottle.SCOPE_IP, "203.0.113.10")
                        .equals(LoginThrottle.key(LoginThrottle.SCOPE_ACCOUNT, "203.0.113.10")),
                "A5: IP and account buckets are namespaced separately");

        // Generic messaging — no account-existence or account-state disclosure.
        String generic = LoginThrottle.GENERIC_AUTH_MESSAGE.toLowerCase(java.util.Locale.ROOT);
        check(!generic.contains("not found") && !generic.contains("disabled") && !generic.contains("exist")
                && !generic.contains("demo") && !generic.contains("owner"), "A5: the sign-in error is generic");
        check(LoginThrottle.THROTTLED_MESSAGE.toLowerCase(java.util.Locale.ROOT).contains("too many"),
                "A5: the throttled error is distinct from a credential error");

        // Concurrent attempts cannot bypass the limit.
        Config concurrency = testConfig("petroflow_test", Map.of(
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "5",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", "1000",
                "PETROFLOW_LOGIN_WINDOW_SECONDS", "900"));
        FakeThrottleStore racing = new FakeThrottleStore();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitted = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (LoginThrottle.evaluate(racing, concurrency, "203.0.113.77", "racer" + n + "@example.com", now).allowed()) {
                        permitted.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        check(permitted.get() <= 5, "A5: concurrent attempts cannot exceed the limit (" + permitted.get() + " of 16 permitted)");
        check(permitted.get() >= 1, "A5: the concurrency test actually exercised the throttle");
    }

    /** A5: proxy handling — X-Forwarded-For is never trusted blindly. */
    private static void testClientIpResolution() {
        Config direct = testConfig("petroflow_test", Map.of());
        check(!direct.trustProxy, "A5: forwarded headers are ignored unless a trusted proxy is configured");
        check("203.0.113.5".equals(Web.resolveClientIp(direct, "1.2.3.4", "203.0.113.5")),
                "A5: a forged X-Forwarded-For header cannot spoof the client IP");
        check("203.0.113.5".equals(Web.resolveClientIp(direct, "not-an-ip", "203.0.113.5")),
                "A5: malformed forwarded headers are ignored");

        Config proxied = testConfig("petroflow_test", Map.of(
                "PETROFLOW_TRUST_PROXY", "true",
                "PETROFLOW_TRUSTED_PROXIES", "10.0.0.5"));
        check(proxied.trustProxy, "A5: trusted-proxy mode is opt-in");
        check("1.2.3.4".equals(Web.resolveClientIp(proxied, "1.2.3.4", "10.0.0.5")),
                "A5: the forwarded client IP is honoured behind a configured trusted proxy");
        check("10.9.9.9".equals(Web.resolveClientIp(proxied, "1.2.3.4", "10.9.9.9")),
                "A5: a direct caller cannot forge the header even when trusted-proxy mode is on");
        check("1.2.3.4".equals(Web.resolveClientIp(proxied, "6.6.6.6, 1.2.3.4, 10.0.0.5", "10.0.0.5")),
                "A5: the right-most untrusted hop is used, so client-supplied left entries are ignored");
        check("6.6.6.6".equals(Web.resolveClientIp(proxied, "6.6.6.6, garbage", "10.0.0.5")),
                "A5: invalid hops are skipped rather than trusted");
        check("2001:db8::1".equals(Web.resolveClientIp(proxied, "[2001:DB8::1]:443, 10.0.0.5", "10.0.0.5")),
                "A5: IPv6 client addresses are normalised");
        check("203.0.113.9".equals(Web.normalizeIp("::ffff:203.0.113.9")),
                "A5: IPv4-mapped IPv6 addresses are normalised");
        check(Web.normalizeIp("example.com") == null && Web.normalizeIp("1.2.3") == null,
                "A5: hostnames and partial addresses are rejected as client IPs");

        String message = expectConfigFailure(
                () -> testConfig("petroflow_test", Map.of("PETROFLOW_TRUST_PROXY", "true")),
                "A5: trust-proxy without a trusted proxy list fails startup configuration");
        check(message != null && message.contains("PETROFLOW_TRUSTED_PROXIES"),
                "A5: the proxy failure tells the operator which variable is missing");
    }

    // -----------------------------------------------------------------------------------------
    // Phase 0 completion: D1/D2 bootstrap, D4/D5 throttle precision, D6 reset protection
    // -----------------------------------------------------------------------------------------

    private static String expectBootstrapRefusal(Runnable action, String label) {
        try {
            action.run();
        } catch (Bootstrap.BootstrapException expected) {
            check(true, label);
            return expected.getMessage() == null ? "" : expected.getMessage();
        }
        check(false, label + " (no refusal was raised)");
        return null;
    }

    /** D1/D2: request validation, slug derivation, password policy. No database, no side effects. */
    private static void testBootstrapValidation() {
        Bootstrap.Request valid = Bootstrap.validate(
                new Bootstrap.Request("OWNER", " Owner@Dealer.IN ", "Asha Rao", "Sunrise Fuels Pvt Ltd",
                        "", "Main Outlet", "", "Pune", "Maharashtra"));
        check("owner@dealer.in".equals(valid.email()), "D1: bootstrap lowercases and trims the owner email");
        check("sunrise-fuels-pvt-ltd".equals(valid.tenantSlug()), "D1: bootstrap derives a URL-safe company slug");
        check("SUNRIS-01".equals(valid.stationCode()), "D1: bootstrap derives a station code when omitted");
        check("OWNER".equals(valid.role()), "D1: an explicit OWNER role is accepted");

        Bootstrap.Request noSlug = Bootstrap.validate(
                new Bootstrap.Request(null, "a@b.co", "Asha", "Rao Fuels", null, null, null, null, null));
        check("OWNER".equals(noSlug.role()), "D1: the role defaults to OWNER");
        check("rao-fuels".equals(noSlug.tenantSlug()), "D1: a blank slug is derived from the company name");
        check(noSlug.stationName() == null && noSlug.stationCode() == null,
                "D1: no station is created when the operator skips it");

        Bootstrap.Request superAdmin = Bootstrap.validate(new Bootstrap.Request("super_admin", "ops@petrolflow.dev", "Ops", "x", "y", "s", "c", null, null));
        check("SUPER_ADMIN".equals(superAdmin.role()) && superAdmin.tenantName() == null,
                "D1: a platform administrator request carries no tenant");

        String emailMessage = expectBootstrapRefusal(() -> {
            Bootstrap.validate(new Bootstrap.Request("OWNER", "not-an-email", "A", "B", null, null, null, null, null));
        },
                "D1: an invalid email is refused");
        check(emailMessage != null && emailMessage.toLowerCase(java.util.Locale.ROOT).contains("email"),
                "D1: the email refusal explains the problem");
        expectBootstrapRefusal(() -> {
            Bootstrap.validate(new Bootstrap.Request("OWNER", "a@b.co", "  ", "B", null, null, null, null, null));
        },
                "D1: a blank owner name is refused");
        expectBootstrapRefusal(() -> {
            Bootstrap.validate(new Bootstrap.Request("OWNER", "a@b.co", "A", "  ", null, null, null, null, null));
        },
                "D1: a blank company name is refused");
        expectBootstrapRefusal(() -> {
            Bootstrap.validate(new Bootstrap.Request("OWNER", "a@b.co", "A", "B", "bad slug!", null, null, null, null));
        },
                "D1: an invalid company slug is refused");
        expectBootstrapRefusal(() -> {
            Bootstrap.validate(new Bootstrap.Request("MANAGER", "a@b.co", "A", "B", null, null, null, null, null));
        },
                "D1: a role other than OWNER/SUPER_ADMIN is refused");
        expectBootstrapRefusal(() -> Bootstrap.validate(null), "D1: a missing request is refused");
        expectBootstrapRefusal(() -> {
            Bootstrap.validate(new Bootstrap.Request("OWNER", "a@b.co", "A", "B", null, "", "X-01", null, null));
        },
                "D1: a station code without a station name is refused");

        check("my-pump-1".equals(Bootstrap.slugify("My Pump #1")), "D1: slugify strips punctuation");
        check(Bootstrap.slugify("   ").isEmpty(), "D1: slugify of a blank name is empty (and rejected later)");
        check(Bootstrap.slugify("A".repeat(80)).length() <= 40, "D1: slugify caps the slug at 40 characters");

        Config production = testConfig("petroflow_test", Map.of("NODE_ENV", "production"));
        String weak = expectBootstrapRefusal(() -> Bootstrap.validatePassword(production, "short1"),
                "D1: a password shorter than the policy is refused");
        check(weak != null && weak.toLowerCase(java.util.Locale.ROOT).contains("10"),
                "D1: the password refusal states the minimum length");
        expectBootstrapRefusal(() -> Bootstrap.validatePassword(production, "alllettersonly"),
                "D1: a password without a digit is refused");
        expectBootstrapRefusal(() -> Bootstrap.validatePassword(production, null),
                "D1: an empty password is refused");
        String published = expectBootstrapRefusal(() -> Bootstrap.validatePassword(production, production.demoPassword),
                "D1: the published demo password is refused");
        check(published != null && published.toLowerCase(java.util.Locale.ROOT).contains("demo"),
                "D1: the demo-password refusal explains why");
        Bootstrap.validatePassword(production, "Dealer-Owner-2026");
        check(true, "D1: a strong, non-default password is accepted");

        java.util.Map<String, String> parsed = Bootstrap.parseArgs(new String[]{
                "--bootstrap-owner", "--email=owner@dealer.in", "--name=Asha Rao", "--role=OWNER"});
        check("owner@dealer.in".equals(parsed.get("email")) && "Asha Rao".equals(parsed.get("name")),
                "D1: bootstrap CLI flags are parsed, including values with spaces");
        check(!parsed.containsKey("bootstrap-owner"), "D1: valueless flags are not mistaken for options");
    }

    /** D6: production resets are refused before any database work. */
    private static void testResetProtection() {
        Config production = testConfig("petroflow_test", Map.of("NODE_ENV", "production"));
        Config development = testConfig("petroflow_test", Map.of("NODE_ENV", "development"));

        String message = null;
        try {
            Main.assertResetAllowed(production);
            check(false, "D6: --reset-db is refused in production");
        } catch (IllegalStateException refused) {
            message = refused.getMessage();
            check(true, "D6: --reset-db is refused in production");
        }
        check(message != null && message.contains("--reset-db") && message.contains("production"),
                "D6: the reset refusal names the command and the environment");

        // The refusal must happen before any SQL: with no database reachable at all, this still
        // throws the production guard (proving resetDatabase never reached Db.withConnection).
        try {
            Main.resetDatabase(production);
            check(false, "D6: resetDatabase refuses production even if the CLI check is bypassed");
        } catch (IllegalStateException refused) {
            check(refused.getMessage() != null && refused.getMessage().contains("--reset-db"),
                    "D6: resetDatabase refuses production even if the CLI check is bypassed");
        } catch (RuntimeException other) {
            check(false, "D6: resetDatabase reached the database layer in production: " + other.getClass().getSimpleName());
        }
        check(true, "D6: no SQL was executed by the production reset path");

        Main.assertResetAllowed(development);
        check(true, "D6: --reset-db stays available in development and tests");
        Main.assertResetAllowed(null);
        check(true, "D6: a missing configuration does not block the development reset");
    }

    /** D4/D5: retry precision and canonical account identity for the throttle. */
    private static void testThrottlePrecisionAndIdentity() {
        String now = Clock.nowIso();
        Config config = testConfig("petroflow_test", Map.of(
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", "1",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "1000",
                "PETROFLOW_LOGIN_WINDOW_SECONDS", "60"));
        FakeThrottleStore store = new FakeThrottleStore();
        LoginThrottle.evaluate(store, config, "203.0.113.1", "a@example.com", now);
        LoginThrottle.Decision blocked = LoginThrottle.evaluate(store, config, "203.0.113.2", "a@example.com", now);
        check(!blocked.allowed(), "D4: the account bucket blocks the second attempt");
        check(blocked.retryAfterSeconds() >= 60 && blocked.retryAfterSeconds() <= 61,
                "D4: Retry-After covers the remaining window without under-stating it (" + blocked.retryAfterSeconds() + "s)");
        LoginThrottle.Decision late = LoginThrottle.evaluate(store, config, "203.0.113.3", "a@example.com",
                Clock.addSecondsIso(now, 59));
        check(!late.allowed() && late.retryAfterSeconds() >= 2 && late.retryAfterSeconds() <= 3,
                "D4: Retry-After shrinks as the window elapses (" + late.retryAfterSeconds() + "s)");
        check(LoginThrottle.evaluate(store, config, "203.0.113.4", "a@example.com",
                Clock.addSecondsIso(now, 61)).allowed(),
                "D4: the window still expires exactly when the hint says it will");

        // D5: the canonical stored email is the bucket key, so collation-equivalent spellings share it.
        check("owner@sunrisefuels.in".equals(LoginThrottle.accountIdentity("owner@sunrisefuels.in", "OWNER@SunriseFuels.in")),
                "D5: an existing account is keyed on its canonical stored email");
        check("owner@sunrisefuels.in".equals(LoginThrottle.accountIdentity(null, "  OWNER@SunriseFuels.in  ")),
                "D5: an unknown account falls back to the normalised submitted address");
        String canonical = LoginThrottle.accountIdentity("owner@sunrisefuels.in", "owne\u0301r@sunrisefuels.in");
        check("owner@sunrisefuels.in".equals(canonical),
                "D5: an accent-variant spelling resolves to the same account bucket");
        check(!LoginThrottle.accountIdentity("a@example.com", "b@example.com")
                        .equals(LoginThrottle.accountIdentity("b@example.com", "a@example.com")),
                "D5: two distinct accounts stay in distinct buckets");

        // The locked variant must not require a database for non-database stores.
        FakeThrottleStore fake = new FakeThrottleStore();
        LoginThrottle.Decision passthrough = LoginThrottle.evaluateLocked(fake, config, "203.0.113.9", "z@example.com", now);
        check(passthrough.allowed() && fake.size() == 1,
                "D3: evaluateLocked applies the same policy for non-database stores");
    }

    /**
     * A5 + A4 regression coverage that needs the real MySQL schema: HTTP login throttling, persisted
     * throttle state, and the production demo-account guard reading real rows. Test database only.
     */
    private static void verifyLoginThrottleAndDemoGuard(HttpClient client, String baseUrl, Config base, String testDb)
            throws Exception {
        String loginUrl = baseUrl + "/auth/login";
        String email = "owner@sunrisefuels.in";

        Main.resetDatabase(base);
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        Main.seedIfEmpty(base);

        // A normal sign-in still works while under the limit.
        HttpResponse<String> ok = post(client, loginUrl, null, Map.of("email", email, "password", base.demoPassword));
        check(ok.statusCode() == 200, "A5: a normal sign-in succeeds while under the throttle limits (got " + ok.statusCode() + ")");

        // Many different accounts from one IP are throttled with a generic 429.
        int firstBlocked = -1;
        String throttledBody = null;
        String retryAfter = null;
        boolean allPlain401 = true;
        for (int i = 1; i <= 60; i++) {
            HttpResponse<String> r = post(client, loginUrl, null,
                    Map.of("email", "nobody" + i + "@example.com", "password", "wrong-password"));
            if (r.statusCode() == 429) {
                firstBlocked = i;
                throttledBody = r.body();
                retryAfter = r.headers().firstValue("retry-after").orElse(null);
                break;
            }
            if (r.statusCode() != 401) allPlain401 = false;
        }
        check(allPlain401, "A5: wrong passwords stay a plain 401 until the IP limit is reached");
        check(firstBlocked > 0, "A5: many accounts from one IP are throttled over HTTP");
        check(firstBlocked >= Math.max(1, base.loginMaxAttemptsPerIp - 5),
                "A5: the IP limit is not triggered early (blocked on attempt " + firstBlocked
                        + ", limit " + base.loginMaxAttemptsPerIp + ")");
        check(throttledBody != null && throttledBody.contains(LoginThrottle.THROTTLED_MESSAGE),
                "A5: the throttled response uses the generic throttle message");
        check(retryAfter != null && !retryAfter.isBlank(), "A5: the throttled response carries a Retry-After header");

        // Throttle state lives in MySQL, so a second API process enforces the same limits.
        long rows = Db.count("SELECT COUNT(*) AS c FROM login_throttle_attempts");
        check(rows > 0, "A5: throttle attempts are persisted in login_throttle_attempts (multi-process safe)");
        check(Db.count("SELECT COUNT(*) AS c FROM login_throttle_attempts WHERE account_key LIKE '%@%'") == 0
                        && Db.count("SELECT COUNT(*) AS c FROM login_throttle_attempts WHERE ip_key LIKE '%@%'") == 0,
                "A5: the throttle table stores hashed keys only (no raw emails)");

        // Window expiry: with a short window the stored history stops counting.
        Config expired = testConfig(testDb, Map.of(
                "PETROFLOW_LOGIN_WINDOW_SECONDS", "1",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "3"));
        check(LoginThrottle.evaluate(LoginThrottle.dbStore(), expired, "203.0.113.77", email,
                        Clock.addSecondsIso(Clock.nowIso(), 5)).allowed(),
                "A5: attempts resume automatically after the window expires (no permanent lockout)");

        // Success clears only the successful account's history.
        Db.exec("DELETE FROM login_throttle_attempts");
        for (int i = 0; i < 3; i++) {
            LoginThrottle.evaluate(LoginThrottle.dbStore(), base, "192.0.2.10", "other@example.com", Clock.nowIso());
            LoginThrottle.evaluate(LoginThrottle.dbStore(), base, "192.0.2.10", email, Clock.nowIso());
        }
        LoginThrottle.clearOnSuccess(LoginThrottle.dbStore(), "  OWNER@SunriseFuels.in ", null);
        check(Db.count("SELECT COUNT(*) AS c FROM login_throttle_attempts WHERE account_key = ?",
                        LoginThrottle.key(LoginThrottle.SCOPE_ACCOUNT, email)) == 0,
                "A5: a successful sign-in clears that account's attempts only");
        check(Db.count("SELECT COUNT(*) AS c FROM login_throttle_attempts WHERE account_key = ?",
                        LoginThrottle.key(LoginThrottle.SCOPE_ACCOUNT, "other@example.com")) > 0,
                "A5: a successful sign-in never clears another account's history");

        // Concurrency against the real database.
        Db.exec("DELETE FROM login_throttle_attempts");
        Config tight = testConfig(testDb, Map.of(
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "5",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", "1000"));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitted = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (LoginThrottle.evaluate(LoginThrottle.dbStore(), tight, "203.0.113.250",
                            "racer" + n + "@example.com", Clock.nowIso()).allowed()) {
                        permitted.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        check(permitted.get() <= 5, "A5: concurrent attempts cannot exceed the limit in MySQL (" + permitted.get() + " of 16)");

        // A4: the production guard detects seeded demo accounts and mutates nothing.
        long usersBefore = Db.count("SELECT COUNT(*) AS c FROM users");
        long demoBefore = Db.count("SELECT COUNT(*) AS c FROM users WHERE is_demo = 1");
        List<DemoGuard.Finding> findings = DemoGuard.detectInDatabase();
        check(findings.size() >= DemoGuard.SEEDED_DEMO_EMAILS.size(),
                "A4: demo-account detection finds every seeded demo user (" + findings.size() + ")");
        check(findings.stream().anyMatch(f -> email.equals(f.email())),
                "A4: detection reports the seeded demo administrator email");

        Config production = testConfig(testDb, Map.of("NODE_ENV", "production"));
        String guardMessage = expectConfigFailure(() -> DemoGuard.enforceProductionStartup(production),
                "A4: production startup fails safely when known demo accounts exist");
        check(guardMessage != null && guardMessage.contains(email),
                "A4: the startup failure names the detected demo account");
        check(guardMessage != null && !guardMessage.toLowerCase(java.util.Locale.ROOT).contains("pbkdf2"),
                "A4: the startup failure logs no password hashes");
        check(Db.count("SELECT COUNT(*) AS c FROM users") == usersBefore,
                "A4: the production guard never deletes user rows");
        check(Db.count("SELECT COUNT(*) AS c FROM users WHERE is_demo = 1") == demoBefore,
                "A4: the production guard never modifies or deactivates demo rows");
        check(!production.seedDemo && !production.demoTools,
                "A4: the production configuration that triggered the guard had seeding and demo tools off");

        DemoGuard.enforceProductionStartup(base);
        check(true, "A4: the same check is a no-op outside production (development keeps its demo data)");

        // A4: a production-configured API serves no demo shortcuts and refuses demo logins, while a
        // real (non-demo) account still signs in normally.
        int productionPort = 4294;
        Web productionWeb = new Web(testConfig(testDb, Map.of(
                "NODE_ENV", "production",
                "PORT", String.valueOf(productionPort))));
        productionWeb.listen();
        String productionUrl = "http://127.0.0.1:" + productionPort + "/api/v1";
        String tempUserId = Clock.newId("usr");
        try {
            HttpResponse<String> demoList = get(client, productionUrl + "/auth/demo-accounts", null);
            check(demoList.statusCode() == 404, "A4: /auth/demo-accounts does not work in production");
            check(!demoList.body().contains(base.demoPassword),
                    "A4: the disabled demo endpoint never leaks the demo password");

            HttpResponse<String> demoLogin = post(client, productionUrl + "/auth/login", null,
                    Map.of("email", email, "password", base.demoPassword));
            check(demoLogin.statusCode() == 401, "A4: a demo account cannot sign in to a production API");
            check(!demoLogin.body().toLowerCase(java.util.Locale.ROOT).contains("demo"),
                    "A4: the demo-login refusal does not disclose that the account is a demo account");

            String now = Clock.nowIso();
            Db.exec("""
                    INSERT INTO users (id, tenant_id, email, name, password_hash, role_key, status,
                                       must_change_password, is_demo, granted_permissions, created_at, updated_at)
                    VALUES (?, NULL, 'dealer.real@example.in', 'Real Dealer', ?, 'SUPER_ADMIN', 'ACTIVE', 0, 0, '[]', ?, ?)
                    """, tempUserId, Passwords.hash("Temp-Real-Deal-2026"), now, now);
            HttpResponse<String> realLogin = post(client, productionUrl + "/auth/login", null,
                    Map.of("email", "dealer.real@example.in", "password", "Temp-Real-Deal-2026"));
            check(realLogin.statusCode() == 200, "A4: a real non-demo account still signs in on a production API");
            check(!realLogin.body().contains("sessionToken"),
                    "A4: production login responses never return the session token in the body");

            HttpResponse<String> wrongPassword = post(client, productionUrl + "/auth/login", null,
                    Map.of("email", "dealer.real@example.in", "password", "nope"));
            check(wrongPassword.statusCode() == 401
                            && wrongPassword.body().contains(LoginThrottle.GENERIC_AUTH_MESSAGE),
                    "A5: a wrong password on a production API returns the generic message");

            HttpResponse<String> unknownAccount = post(client, productionUrl + "/auth/login", null,
                    Map.of("email", "never-existed@example.in", "password", "nope"));
            check(unknownAccount.statusCode() == 401
                            && unknownAccount.body().contains(LoginThrottle.GENERIC_AUTH_MESSAGE),
                    "A5: an unknown account returns the same generic message as a wrong password");

            Db.exec("UPDATE users SET status = 'DISABLED' WHERE id = ?", tempUserId);
            HttpResponse<String> disabled = post(client, productionUrl + "/auth/login", null,
                    Map.of("email", "dealer.real@example.in", "password", "Temp-Real-Deal-2026"));
            check(disabled.statusCode() == 401 && disabled.body().contains(LoginThrottle.GENERIC_AUTH_MESSAGE),
                    "A5: a disabled account is indistinguishable from a wrong password");
        } finally {
            Db.exec("DELETE FROM sessions WHERE user_id = ?", tempUserId);
            Db.exec("DELETE FROM users WHERE id = ?", tempUserId);
            productionWeb.stop();
        }

        // Reset throttle state so later suites start clean.
        Db.exec("DELETE FROM login_throttle_attempts");
    }

    /**
     * D1/D2: first-owner bootstrap against the real MySQL schema. Test database only.
     */
    private static void verifyBootstrapFlow(HttpClient client, String url, Config base) throws Exception {
        String ownerPassword = "Dealer-Owner-2026";

        // --- system role reference data exists even without the demo seed ---------------------
        Main.resetDatabase(base);
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        check(Db.count("SELECT COUNT(*) AS c FROM users") == 0, "D1: a fresh schema has no users");
        check(Db.count("SELECT COUNT(*) AS c FROM roles WHERE `key` IN ('OWNER','MANAGER','STAFF','SUPER_ADMIN')") == 4,
                "D1: schema.sql provides the four system roles on a fresh production database");
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        check(Db.count("SELECT COUNT(*) AS c FROM roles") == 4,
                "D1: re-applying the schema does not duplicate the system roles (idempotent)");

        // --- invalid input is refused before anything is written -----------------------------
        long tenantsBefore = Db.count("SELECT COUNT(*) AS c FROM tenants");
        try {
            Bootstrap.createFirstOwner(base, Bootstrap.Request.owner("owner@dealer.in", "Asha Rao", "Asha Fuels", "asha-fuels"),
                    "short");
            check(false, "D1: bootstrap refuses a weak password");
        } catch (Bootstrap.BootstrapException expected) {
            check(true, "D1: bootstrap refuses a weak password");
        }
        check(Db.count("SELECT COUNT(*) AS c FROM tenants") == tenantsBefore,
                "D1: a refused bootstrap writes no tenant row");
        check(Db.count("SELECT COUNT(*) AS c FROM users") == 0, "D1: a refused bootstrap writes no user row");
        check(Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = ?", Bootstrap.MARKER_KEY) == 0,
                "D1: a refused bootstrap does not set the completion marker");

        // --- successful first-owner creation --------------------------------------------------
        Bootstrap.Result created = Bootstrap.createFirstOwner(base,
                new Bootstrap.Request("OWNER", "owner@dealer.in", "Asha Rao", "Asha Fuels", "asha-fuels",
                        "Asha Fuels — Main Outlet", "ASHA-01", "Pune", "Maharashtra"),
                ownerPassword);
        check(created.userId() != null && created.tenantId() != null && created.stationId() != null,
                "D1: bootstrap creates the tenant, the owner and the first station");
        check(Db.count("SELECT COUNT(*) AS c FROM users WHERE id = ? AND is_demo = 0 AND role_key = 'OWNER' AND status = 'ACTIVE'",
                created.userId()) == 1, "D1: the first owner is a real (is_demo=0) active OWNER");
        check(Db.count("SELECT COUNT(*) AS c FROM tenants WHERE id = ? AND is_demo = 0 AND status = 'ACTIVE'",
                created.tenantId()) == 1, "D1: the tenant is real (is_demo=0) and active");
        check(Db.count("SELECT COUNT(*) AS c FROM stations WHERE id = ? AND tenant_id = ? AND is_demo = 0",
                created.stationId(), created.tenantId()) == 1, "D1: the station belongs to the tenant and is not demo data");
        check(Db.count("SELECT COUNT(*) AS c FROM user_station_access WHERE user_id = ? AND station_id = ?",
                created.userId(), created.stationId()) == 1, "D1: the owner is granted access to its first station");
        String hash = Db.str(Db.one("SELECT password_hash FROM users WHERE id = ?", created.userId()), "password_hash");
        check(hash != null && hash.startsWith("pbkdf2$"), "D1: the bootstrap password is stored with the existing PBKDF2 hasher");
        check(hash != null && !hash.contains(ownerPassword), "D1: the bootstrap password is never stored in clear text");
        check(!Bootstrap.describe(created).contains(ownerPassword), "D1: the bootstrap summary never prints the password");
        check(Db.count("SELECT COUNT(*) AS c FROM app_settings WHERE `key` = ?", Bootstrap.MARKER_KEY) == 1,
                "D1: a successful bootstrap records its one-time marker");

        // --- the new owner can actually sign in ----------------------------------------------
        HttpResponse<String> login = post(client, url + "/auth/login", null,
                Map.of("email", "owner@dealer.in", "password", ownerPassword));
        check(login.statusCode() == 200, "D1: the bootstrapped owner can sign in (got " + login.statusCode() + ")");
        String ownerToken = String.valueOf(Json.object(Json.object(Json.parse(login.body())).get("data")).get("sessionToken"));
        HttpResponse<String> me = get(client, url + "/auth/me", ownerToken);
        check(me.statusCode() == 200, "D1: the bootstrapped owner gets a working session");

        // --- it is disabled afterwards -------------------------------------------------------
        try {
            Bootstrap.createFirstOwner(base, Bootstrap.Request.owner("second@dealer.in", "Second Owner", "Other Fuels", "other-fuels"),
                    "Second-Owner-2026");
            check(false, "D1: a second bootstrap is refused once an owner exists");
        } catch (Bootstrap.BootstrapException expected) {
            check(true, "D1: a second bootstrap is refused once an owner exists");
        }
        check(Db.count("SELECT COUNT(*) AS c FROM users WHERE role_key IN ('OWNER','SUPER_ADMIN')") == 1,
                "D1: the refused second bootstrap creates no second owner");
        check(Db.count("SELECT COUNT(*) AS c FROM tenants") == 1, "D1: the refused second bootstrap creates no tenant");
        check(Db.count("SELECT COUNT(*) AS c FROM users WHERE email = 'second@dealer.in'") == 0,
                "D1: the refused second bootstrap creates no user");
        DemoGuard.enforceProductionStartup(testConfig(base.dbName, Map.of("NODE_ENV", "production")));
        check(true, "D1: a bootstrapped production database passes the demo-account guard");

        // --- a conflicting account is refused, and never modified ----------------------------
        Main.resetDatabase(base);
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        String now = Clock.nowIso();
        String existingId = Clock.newId("usr");
        Db.exec("""
                INSERT INTO users (id, tenant_id, email, name, password_hash, role_key, status,
                                   must_change_password, is_demo, granted_permissions, created_at, updated_at)
                VALUES (?, NULL, 'taken@dealer.in', 'Existing Staff', ?, 'STAFF', 'ACTIVE', 0, 0, '[]', ?, ?)
                """, existingId, Passwords.hash("Existing-Staff-2026"), now, now);
        String conflictMessage = expectConfigFailure(() -> {
            try {
                Bootstrap.createFirstOwner(base, Bootstrap.Request.owner("taken@dealer.in", "New Owner", "New Fuels", "new-fuels"),
                        "New-Owner-2026");
            } catch (Bootstrap.BootstrapException refused) {
                throw new IllegalStateException(refused.getMessage(), refused);
            }
        }, "D1: bootstrap refuses an email that already exists");
        check(conflictMessage != null && conflictMessage.toLowerCase(java.util.Locale.ROOT).contains("already exists"),
                "D1: the conflicting-account refusal explains the conflict");
        check(Db.count("SELECT COUNT(*) AS c FROM users WHERE id = ? AND role_key = 'STAFF'", existingId) == 1,
                "D1: the existing conflicting account is left untouched");
        check(Db.count("SELECT COUNT(*) AS c FROM users") == 1, "D1: the refused bootstrap adds no user");

        // --- an existing conflicting tenant slug is refused ----------------------------------
        try {
            Bootstrap.createFirstOwner(base, Bootstrap.Request.owner("other@dealer.in", "Owner Two", "New Fuels", "new-fuels"),
                    "New-Owner-2026");
            Bootstrap.createFirstOwner(base, Bootstrap.Request.owner("third@dealer.in", "Owner Three", "New Fuels", "new-fuels"),
                    "New-Owner-2026");
            check(false, "D1: bootstrap refuses a company slug that is already taken");
        } catch (Bootstrap.BootstrapException expected) {
            check(true, "D1: bootstrap refuses a company slug that is already taken");
        }
        check(Db.count("SELECT COUNT(*) AS c FROM tenants WHERE slug = 'new-fuels'") == 1,
                "D1: only one tenant is created for a duplicated slug");

        // --- concurrent bootstraps: exactly one wins -----------------------------------------
        Main.resetDatabase(base);
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    Bootstrap.createFirstOwner(base,
                            Bootstrap.Request.owner("racer" + n + "@dealer.in", "Racer " + n, "Racer Fuels " + n, "racer-fuels-" + n),
                            "Racer-Owner-2026");
                    succeeded.incrementAndGet();
                } catch (Bootstrap.BootstrapException refusedBootstrap) {
                    refused.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        check(succeeded.get() == 1 && refused.get() == 1,
                "D1: concurrent bootstraps produce exactly one owner (" + succeeded.get() + " succeeded, " + refused.get() + " refused)");
        check(Db.count("SELECT COUNT(*) AS c FROM users WHERE role_key = 'OWNER'") == 1,
                "D1: concurrency cannot create a second owner row");
        check(Db.count("SELECT COUNT(*) AS c FROM tenants") == 1, "D1: concurrency cannot create a second tenant");

        // --- restore the demo seed for the remaining suite steps ------------------------------
        Main.resetDatabase(base);
        Main.apply(base.mysqlDir().resolve("schema.sql"));
        Main.seedIfEmpty(base);
        check(Db.count("SELECT COUNT(*) AS c FROM users") > 0, "D1: the demo seed still loads after the bootstrap tests");
    }

    /** D3: the throttle's reserve+count sequence runs under a MySQL advisory lock. */
    private static void verifyThrottleAdvisoryLock(Config base) {
        Db.exec("DELETE FROM login_throttle_attempts");
        Config tight = testConfig(base.dbName, Map.of(
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_IP", "5",
                "PETROFLOW_LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", "1000"));
        AtomicInteger permitted = new AtomicInteger();
        try {
            LoginThrottle.Decision first = LoginThrottle.evaluateLocked(LoginThrottle.dbStore(), tight,
                    "203.0.113.99", "locked@example.com", Clock.nowIso());
            check(first.allowed(), "D3: evaluateLocked admits a request through the real MySQL store");
            String lockName = Db.str(Db.one("SELECT IF(GET_LOCK(?, 1) = 1, 'free', 'held') AS state", LoginThrottle.LOCK_NAME), "state");
            Db.releaseLock(LoginThrottle.LOCK_NAME);
            check("free".equals(lockName), "D3: the throttle advisory lock is released after each request");

            ExecutorService pool = Executors.newFixedThreadPool(12);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        if (LoginThrottle.evaluateLocked(LoginThrottle.dbStore(), tight, "203.0.113.100",
                                "racer" + n + "@example.com", Clock.nowIso()).allowed()) {
                            permitted.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
            pool.shutdownNow();
        } catch (Exception error) {
            check(false, "D3: advisory-locked throttle concurrency test failed: " + error.getMessage());
        }
        check(permitted.get() <= 5, "D3: concurrent locked reservations cannot exceed the IP limit (" + permitted.get() + " of 12)");
        check(permitted.get() >= 1, "D3: the advisory-locked test actually exercised the throttle");
        Db.exec("DELETE FROM login_throttle_attempts");
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postWithIdem(HttpClient client, String uri, String token, String idemKey, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> patch(HttpClient client, String uri, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(HttpClient client, String uri, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).DELETE();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("FAILED: " + label);
        passed++;
        System.out.println("  [OK] " + label);
    }
}
