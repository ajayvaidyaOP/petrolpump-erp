package in.petrolflow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FinanceApi {
    private FinanceApi() {}

    public static void register(Web web) {
        web.get("/finance/customer-ledger", FinanceApi::customerLedger);
        web.get("/finance/customer-statement", FinanceApi::statement);
        web.post("/finance/credit-sales", FinanceApi::creditSale);
        web.get("/finance/supplier-ledger", FinanceApi::supplierLedger);
        web.get("/finance/payments", FinanceApi::payments);
        web.post("/finance/payments", FinanceApi::createPayment);
        web.post("/finance/payments/:id/settle", FinanceApi::settle);
        web.patch("/finance/payments/:id/settle", FinanceApi::settle);
        web.post("/finance/payments/:id/reverse", FinanceApi::reversePayment);
        web.get("/finance/expenses", FinanceApi::expenses);
        web.post("/finance/expenses", FinanceApi::createExpense);
        web.delete("/finance/expenses/:id", FinanceApi::deleteExpense);
        web.get("/finance/reconciliation", FinanceApi::reconciliation);
        web.post("/finance/reconciliation", FinanceApi::saveReconciliation);
        web.post("/finance/reconciliation/:id/approve", FinanceApi::approveReconciliation);
        web.get("/inventory/stock", FinanceApi::stock);
        web.get("/inventory/movements", FinanceApi::movements);
        web.get("/inventory/deliveries", FinanceApi::deliveries);
        web.post("/inventory/deliveries", FinanceApi::createDelivery);
        web.post("/inventory/deliveries/:id/receive", FinanceApi::receive);
        web.post("/inventory/deliveries/:id/dispute", FinanceApi::dispute);
        web.delete("/inventory/deliveries/:id", FinanceApi::deleteDelivery);
        web.post("/inventory/adjustments", FinanceApi::adjustment);
        web.post("/inventory/opening-stock", FinanceApi::opening);
        web.post("/inventory/transfers", FinanceApi::transfer);
        web.get("/inventory/tanks/:id/ledger", FinanceApi::tankLedger);
    }

    private static List<String> resolveLedgerStationIds(Auth auth, Web.Ctx ctx, String customerId) {
        String stationParam = ctx.q("stationId");
        if (stationParam != null && !stationParam.isBlank() && !"all".equalsIgnoreCase(stationParam)) {
            auth.station(stationParam.trim());
            return List.of(stationParam.trim());
        }
        if (auth.allStations) {
            return null;
        }
        if (auth.stationIds.isEmpty()) {
            throw ApiException.forbidden("You do not have access to any station");
        }
        List<String> allowed = new ArrayList<>(auth.stationIds);
        String inPlaceholders = String.join(",", Collections.nCopies(allowed.size(), "?"));
        List<Object> allowParams = new ArrayList<>();
        allowParams.add(customerId);
        allowParams.addAll(allowed);
        long allowedCount = Db.count(
                "SELECT COUNT(*) AS count FROM credit_entries WHERE customer_id = ? AND status = 'POSTED' AND station_id IN (" + inPlaceholders + ")",
                allowParams.toArray());
        if (allowedCount == 0) {
            long totalCount = Db.count("SELECT COUNT(*) AS count FROM credit_entries WHERE customer_id = ? AND status = 'POSTED'", customerId);
            if (totalCount > 0) {
                throw ApiException.forbidden("You do not have access to credit ledger entries for other stations");
            }
        }
        return allowed;
    }

    private static BigDecimal applyCreditEntryDelta(BigDecimal acc, Map<String, Object> entry) {
        String type = Db.str(entry, "type");
        BigDecimal rawAmt = Money.d(entry.get("amount"));
        BigDecimal absAmt = rawAmt.abs();
        if ("CREDIT_SALE".equals(type)) return acc.add(absAmt);
        if ("PAYMENT".equals(type)) return acc.subtract(absAmt);
        if ("PAYMENT_REVERSAL".equals(type)) return acc.add(absAmt);
        return acc.add(rawAmt);
    }

    private static void customerLedger(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.read");
        String customerId = required(ctx, "customerId");
        Map<String, Object> customer = Db.one("SELECT * FROM customers WHERE id = ? AND tenant_id = ?", customerId, auth.tenantId());
        if (customer == null) throw ApiException.notFound("Customer not found");
        List<String> stationIds = resolveLedgerStationIds(auth, ctx, customerId);
        List<Map<String, Object>> entries;
        if (stationIds == null) {
            entries = Db.all(
                    """
                    SELECT ce.*, st.name AS station_name, u.name AS created_by_name, p.name AS product_name
                      FROM credit_entries ce
                      LEFT JOIN stations st ON st.id = ce.station_id
                      LEFT JOIN users u ON u.id = ce.created_by
                      LEFT JOIN fuel_products p ON p.id = ce.product_id
                     WHERE ce.customer_id = ? AND ce.tenant_id = ? AND ce.status = 'POSTED'
                     ORDER BY ce.occurred_at ASC, ce.created_at ASC
                    """, customerId, auth.tenantId());
        } else {
            String inSql = String.join(",", Collections.nCopies(stationIds.size(), "?"));
            List<Object> params = new ArrayList<>();
            params.add(customerId);
            params.add(auth.tenantId());
            params.addAll(stationIds);
            entries = Db.all(
                    "SELECT ce.*, st.name AS station_name, u.name AS created_by_name, p.name AS product_name "
                            + "FROM credit_entries ce "
                            + "LEFT JOIN stations st ON st.id = ce.station_id "
                            + "LEFT JOIN users u ON u.id = ce.created_by "
                            + "LEFT JOIN fuel_products p ON p.id = ce.product_id "
                            + "WHERE ce.customer_id = ? AND ce.tenant_id = ? AND ce.status = 'POSTED' AND ce.station_id IN (" + inSql + ") "
                            + "ORDER BY ce.occurred_at ASC, ce.created_at ASC",
                    params.toArray());
        }
        BigDecimal running;
        if (stationIds == null) {
            BigDecimal netPosted = BigDecimal.ZERO;
            for (Map<String, Object> entry : entries) {
                netPosted = applyCreditEntryDelta(netPosted, entry);
            }
            running = Money.d(customer.get("balance")).subtract(netPosted);
        } else {
            running = BigDecimal.ZERO;
        }
        BigDecimal overdueTotal = BigDecimal.ZERO;
        String today = Clock.todayIst();
        List<Map<String, Object>> ledger = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String type = Db.str(entry, "type");
            BigDecimal rawAmt = Money.d(entry.get("amount"));
            BigDecimal absAmt = rawAmt.abs();
            running = applyCreditEntryDelta(running, entry);
            String due = Db.str(entry, "due_date");
            boolean overdue = "CREDIT_SALE".equals(type) && due != null && due.compareTo(today) < 0;
            if (overdue) overdueTotal = overdueTotal.add(absAmt);
            boolean isDebit = "CREDIT_SALE".equals(type) || (!"PAYMENT".equals(type) && rawAmt.signum() > 0);
            boolean isCredit = "PAYMENT".equals(type) || (!"CREDIT_SALE".equals(type) && rawAmt.signum() < 0);
            Map<String, Object> shaped = Json.shape(entry);
            shaped.put("amount", Money.money(absAmt));
            shaped.put("debit", isDebit ? Money.money(absAmt) : "0.00");
            shaped.put("credit", isCredit ? Money.money(absAmt) : "0.00");
            shaped.put("runningBalance", Money.money(running));
            shaped.put("overdue", overdue);
            ledger.add(shaped);
        }
        BigDecimal limit = Money.d(customer.get("credit_limit"));
        Map<String, Object> shapedCustomer = Json.shape(customer);
        if (stationIds != null) {
            shapedCustomer.put("balance", Money.money(running));
        }
        ctx.ok(Web.map(
                "customer", shapedCustomer,
                "ledger", ledger,
                "entries", ledger,
                "closingBalance", Money.money(running),
                "creditLimit", Money.money(limit),
                "availableCredit", Money.money(limit.subtract(running)),
                "overdueAmount", Money.money(overdueTotal),
                "note", "A credit sale increases the customer balance; a receipt reduces it. Payment mode does not change the credit balance until a receipt is recorded."));
    }

    private static void statement(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.read");
        String month = ctx.q("month");
        if (month == null || !month.matches("\\d{4}-\\d{2}")) throw ApiException.unprocessable("month (YYYY-MM) is required");
        Map<String, Object> customer = Db.one("SELECT * FROM customers WHERE id = ? AND tenant_id = ?", ctx.q("customerId"), auth.tenantId());
        if (customer == null) throw ApiException.notFound("Customer not found");
        String customerId = Db.str(customer, "id");
        List<String> stationIds = resolveLedgerStationIds(auth, ctx, customerId);
        String fromIso = Clock.istDayStart(month + "-01");
        String toIso = Clock.istDayStart(Clock.addDays(month + "-28", 4).substring(0, 8) + "01");
        BigDecimal opening;
        List<Map<String, Object>> rows;
        BigDecimal scopedCurrentBalance = null;
        if (stationIds == null) {
            BigDecimal netAllPosted = BigDecimal.ZERO;
            for (Map<String, Object> r : Db.all("SELECT type, amount FROM credit_entries WHERE customer_id = ? AND tenant_id = ? AND status = 'POSTED'", customerId, auth.tenantId())) {
                netAllPosted = applyCreditEntryDelta(netAllPosted, r);
            }
            opening = Money.d(customer.get("balance")).subtract(netAllPosted);
            for (Map<String, Object> r : Db.all("SELECT type, amount FROM credit_entries WHERE customer_id = ? AND tenant_id = ? AND status = 'POSTED' AND occurred_at < ?", customerId, auth.tenantId(), fromIso)) {
                opening = applyCreditEntryDelta(opening, r);
            }
            rows = Db.all(
                    """
                    SELECT ce.*, st.name AS station_name, p.name AS product_name
                      FROM credit_entries ce
                      LEFT JOIN stations st ON st.id = ce.station_id
                      LEFT JOIN fuel_products p ON p.id = ce.product_id
                     WHERE ce.customer_id = ? AND ce.tenant_id = ? AND ce.status = 'POSTED' AND ce.occurred_at >= ? AND ce.occurred_at < ?
                     ORDER BY ce.occurred_at ASC, ce.created_at ASC
                    """, customerId, auth.tenantId(), fromIso, toIso);
        } else {
            String inSql = String.join(",", Collections.nCopies(stationIds.size(), "?"));
            opening = BigDecimal.ZERO;
            List<Object> preParams = new ArrayList<>();
            preParams.add(customerId);
            preParams.add(auth.tenantId());
            preParams.add(fromIso);
            preParams.addAll(stationIds);
            for (Map<String, Object> r : Db.all(
                    "SELECT type, amount FROM credit_entries WHERE customer_id = ? AND tenant_id = ? AND status = 'POSTED' AND occurred_at < ? AND station_id IN (" + inSql + ")",
                    preParams.toArray())) {
                opening = applyCreditEntryDelta(opening, r);
            }
            List<Object> rowParams = new ArrayList<>();
            rowParams.add(customerId);
            rowParams.add(auth.tenantId());
            rowParams.add(fromIso);
            rowParams.add(toIso);
            rowParams.addAll(stationIds);
            rows = Db.all(
                    "SELECT ce.*, st.name AS station_name, p.name AS product_name "
                            + "FROM credit_entries ce "
                            + "LEFT JOIN stations st ON st.id = ce.station_id "
                            + "LEFT JOIN fuel_products p ON p.id = ce.product_id "
                            + "WHERE ce.customer_id = ? AND ce.tenant_id = ? AND ce.status = 'POSTED' AND ce.occurred_at >= ? AND ce.occurred_at < ? "
                            + "AND ce.station_id IN (" + inSql + ") "
                            + "ORDER BY ce.occurred_at ASC, ce.created_at ASC",
                    rowParams.toArray());
            scopedCurrentBalance = BigDecimal.ZERO;
            List<Object> allStationParams = new ArrayList<>();
            allStationParams.add(customerId);
            allStationParams.add(auth.tenantId());
            allStationParams.addAll(stationIds);
            for (Map<String, Object> r : Db.all(
                    "SELECT type, amount FROM credit_entries WHERE customer_id = ? AND tenant_id = ? AND status = 'POSTED' AND station_id IN (" + inSql + ")",
                    allStationParams.toArray())) {
                scopedCurrentBalance = applyCreditEntryDelta(scopedCurrentBalance, r);
            }
        }
        BigDecimal running = opening;
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalPayments = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String type = Db.str(row, "type");
            BigDecimal amt = Money.d(row.get("amount"));
            BigDecimal abs = amt.abs();
            if ("CREDIT_SALE".equals(type)) { running = running.add(abs); totalSales = totalSales.add(abs); }
            else if ("PAYMENT".equals(type)) { running = running.subtract(abs); totalPayments = totalPayments.add(abs); }
            else if ("PAYMENT_REVERSAL".equals(type)) { running = running.add(abs); totalPayments = totalPayments.subtract(abs); }
            else { running = running.add(amt); }
            boolean isDebit = "CREDIT_SALE".equals(type) || (!"PAYMENT".equals(type) && amt.signum() > 0);
            boolean isCredit = "PAYMENT".equals(type) || (!"CREDIT_SALE".equals(type) && amt.signum() < 0);
            Map<String, Object> line = Json.shape(row);
            line.put("debit", isDebit ? Money.money(abs) : "0.00");
            line.put("credit", isCredit ? Money.money(abs) : "0.00");
            line.put("runningBalance", Money.money(running));
            lines.add(line);
        }
        Map<String, Object> tenant = Db.one("SELECT name, legal_name, gstin, address FROM tenants WHERE id = ?", auth.tenantId());
        List<Map<String, Object>> vehicles = Json.shapeAll(Db.all("SELECT * FROM customer_vehicles WHERE customer_id = ? AND tenant_id = ? AND is_active = 1 ORDER BY vehicle_number", customerId, auth.tenantId()));
        Map<String, Object> shapedCustomer = Json.shape(customer);
        if (scopedCurrentBalance != null) {
            shapedCustomer.put("balance", Money.money(scopedCurrentBalance));
        }
        ctx.ok(Web.map(
                "tenant", tenant == null ? null : Json.shape(tenant),
                "customer", shapedCustomer,
                "vehicles", vehicles,
                "month", month,
                "openingBalance", Money.money(opening),
                "closingBalance", Money.money(running),
                "totalSales", Money.money(totalSales),
                "totalPayments", Money.money(totalPayments),
                "lines", lines,
                "generatedAt", Clock.nowIso()));
    }

    private static void creditSale(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("sales.write");
        Map<String, Object> station = auth.station(requiredBody(ctx, "stationId"));
        Map<String, Object> customer = Db.one("SELECT * FROM customers WHERE id = ? AND tenant_id = ?", requiredBody(ctx, "customerId"), auth.tenantId());
        if (customer == null) throw ApiException.notFound("Customer not found");
        String productId = blankToNull(ctx.str("productId"));
        if (productId != null && Db.one("SELECT id FROM fuel_products WHERE id = ? AND tenant_id = ? AND is_active = 1", productId, auth.tenantId()) == null) {
            throw ApiException.notFound("Active fuel product not found");
        }
        String occurredAt = resolveOccurredAtIso(ctx.str("occurredAt"));
        String rateStr = blankToNull(ctx.str("ratePerLitre"));
        if (rateStr == null && productId != null) {
            Map<String, Object> price = Domain.priceAt(auth.tenantId(), productId, Db.str(station, "id"), occurredAt);
            if (price != null) rateStr = Db.str(price, "rate_per_litre");
        }
        final String resolvedRate = rateStr;
        BigDecimal amount = FinanceRules.creditAmount(ctx.str("amount"), ctx.str("quantityLitres"), resolvedRate);
        String dueDate = blankToNull(ctx.str("dueDate"));
        if (dueDate != null && !Clock.isIsoDate(dueDate)) throw ApiException.validation("dueDate", "dueDate must be YYYY-MM-DD");
        String id = Clock.newId("cre");
        String businessDate = resolveBusinessDate(occurredAt);
        String now = Clock.nowIso();
        Map<String, Object> result = Db.tx(() -> {
            Domain.assertDayOpen(Db.str(station, "id"), businessDate);
            Map<String, Object> freshCustomer = Db.one("SELECT * FROM customers WHERE id = ? AND tenant_id = ? FOR UPDATE", customer.get("id"), auth.tenantId());
            if (freshCustomer == null || !"ACTIVE".equals(Db.str(freshCustomer, "status"))) throw ApiException.unprocessable("Customer is not active");
            BigDecimal curBalance = Money.d(freshCustomer.get("balance"));
            BigDecimal limit = Money.d(freshCustomer.get("credit_limit"));
            if (limit.signum() > 0 && curBalance.add(amount).compareTo(limit) > 0) {
                if (auth.allStations) {
                    throw ApiException.unprocessable("Credit limit exceeded: outstanding " + Money.money(curBalance) + ", limit " + Money.money(limit));
                }
                throw ApiException.unprocessable("Credit limit exceeded (sanctioned limit " + Money.money(limit) + ")");
            }
            long seq = Db.count("SELECT COUNT(*) AS count FROM credit_entries WHERE station_id = ? AND type = 'CREDIT_SALE'", station.get("id")) + 1;
            String slip = ctx.str("slipNumber") == null || ctx.str("slipNumber").isBlank()
                    ? station.get("code") + "-" + businessDate.replace("-", "") + "-" + String.format("%03d", seq)
                    : ctx.str("slipNumber");
            Db.exec(
                    """
                    INSERT INTO credit_entries
                      (id, tenant_id, station_id, customer_id, type, amount, slip_number, vehicle_number, driver_name,
                       product_id, quantity_litres, rate_per_litre, reference, occurred_at, due_date, note, status, created_by, created_at)
                    VALUES (?, ?, ?, ?, 'CREDIT_SALE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?)
                    """,
                    id, auth.tenantId(), station.get("id"), customer.get("id"), Money.money(amount), slip,
                    ctx.str("vehicleNumber"), ctx.str("driverName"), ctx.str("productId"),
                    ctx.str("quantityLitres") == null ? null : Money.litres(ctx.str("quantityLitres")),
                    resolvedRate == null ? null : Money.rate(resolvedRate),
                    ctx.str("reference"), occurredAt, dueDate, ctx.str("note"), auth.userId, now);
            String newBal = Money.money(curBalance.add(amount));
            Db.exec("UPDATE customers SET balance = ?, updated_at = ? WHERE id = ?", newBal, now, customer.get("id"));
            Auth.auditRequired(auth, "CREDIT_SALE_RECORDED", "CreditEntry", id, Db.str(station, "id"), customer.get("name") + ": " + Money.money(amount), null, Db.one("SELECT * FROM credit_entries WHERE id = ?", id));
            String visibleNewBalance = auth.allStations
                    ? newBal
                    : Domain.stationScopedCustomerBalance(auth.tenantId(), Db.str(customer, "id"), auth.stationIds);
            return Web.map("id", id, "customerId", customer.get("id"), "slipNumber", slip, "amount", Money.money(amount), "newBalance", visibleNewBalance);
        });
        ctx.created(result);
    }

    private static void supplierLedger(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("suppliers.read");
        Map<String, Object> supplier = Db.one("SELECT * FROM suppliers WHERE id = ? AND tenant_id = ?", required(ctx, "supplierId"), auth.tenantId());
        if (supplier == null) throw ApiException.notFound("Supplier not found");
        String stationParam = ctx.q("stationId");
        final List<String> stationIds;
        if (stationParam != null && !stationParam.isBlank() && !"all".equalsIgnoreCase(stationParam)) {
            auth.station(stationParam.trim());
            stationIds = List.of(stationParam.trim());
        } else if (auth.allStations) {
            stationIds = null;
        } else {
            if (auth.stationIds.isEmpty()) {
                throw ApiException.forbidden("You do not have access to any station");
            }
            stationIds = new ArrayList<>(auth.stationIds);
        }

        final List<Map<String, Object>> deliveryRows;
        final List<Map<String, Object>> lubePurchaseRows;
        final List<Map<String, Object>> paymentRows;
        if (stationIds == null) {
            deliveryRows = Db.all(
                    "SELECT d.*, p.name AS product_name FROM deliveries d JOIN fuel_products p ON p.id = d.product_id "
                            + "WHERE d.supplier_id = ? AND d.tenant_id = ? AND d.status = 'RECEIVED'",
                    supplier.get("id"), auth.tenantId());
            lubePurchaseRows = Db.all(
                    "SELECT m.*, p.name AS product_name FROM lube_movements m JOIN lube_products p ON p.id = m.product_id WHERE m.supplier_id = ? AND m.tenant_id = ? AND m.type = 'PURCHASE'",
                    supplier.get("id"), auth.tenantId());
            paymentRows = Db.all(
                    "SELECT * FROM payments WHERE supplier_id = ? AND tenant_id = ? AND direction = 'PAYOUT' AND status != 'REVERSED'",
                    supplier.get("id"), auth.tenantId());
        } else {
            String inSql = String.join(",", Collections.nCopies(stationIds.size(), "?"));
            List<Object> dParams = new ArrayList<>(2 + stationIds.size());
            dParams.add(supplier.get("id"));
            dParams.add(auth.tenantId());
            dParams.addAll(stationIds);
            deliveryRows = Db.all(
                    "SELECT d.*, p.name AS product_name FROM deliveries d JOIN fuel_products p ON p.id = d.product_id "
                            + "WHERE d.supplier_id = ? AND d.tenant_id = ? AND d.status = 'RECEIVED' AND d.station_id IN (" + inSql + ")",
                    dParams.toArray());

            List<Object> lParams = new ArrayList<>(2 + stationIds.size());
            lParams.add(supplier.get("id"));
            lParams.add(auth.tenantId());
            lParams.addAll(stationIds);
            lubePurchaseRows = Db.all(
                    "SELECT m.*, p.name AS product_name FROM lube_movements m JOIN lube_products p ON p.id = m.product_id "
                            + "WHERE m.supplier_id = ? AND m.tenant_id = ? AND m.type = 'PURCHASE' AND m.station_id IN (" + inSql + ")",
                    lParams.toArray());
            List<Object> pParams = new ArrayList<>(2 + stationIds.size());
            pParams.add(supplier.get("id"));
            pParams.add(auth.tenantId());
            pParams.addAll(stationIds);
            paymentRows = Db.all(
                    "SELECT * FROM payments WHERE supplier_id = ? AND tenant_id = ? AND direction = 'PAYOUT' AND status != 'REVERSED' AND station_id IN (" + inSql + ")",
                    pParams.toArray());
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> d : deliveryRows) {
            events.add(Web.map(
                    "id", d.get("id"),
                    "date", d.get("received_at") != null ? d.get("received_at") : (d.get("invoice_date") + "T00:00:00.000Z"),
                    "type", "PURCHASE",
                    "description", d.get("product_name") + " · " + d.get("quantity_litres") + " L · Inv " + d.get("invoice_number"),
                    "reference", d.get("invoice_number"),
                    "debit", Money.money(d.get("total_amount")),
                    "credit", "0.00"));
        }
        for (Map<String, Object> lube : lubePurchaseRows) {
            events.add(Web.map(
                    "id", lube.get("id"),
                    "date", lube.get("occurred_at"),
                    "type", "PURCHASE",
                    "description", lube.get("product_name") + " · " + lube.get("quantity") + " units",
                    "reference", lube.get("reference"),
                    "debit", Money.money(Money.d(lube.get("amount")).abs()),
                    "credit", "0.00"));
        }
        for (Map<String, Object> pay : paymentRows) {
            events.add(Web.map(
                    "id", pay.get("id"),
                    "date", pay.get("occurred_at"),
                    "type", "PAYMENT",
                    "description", pay.get("mode") + (pay.get("reference") != null ? " · " + pay.get("reference") : ""),
                    "reference", pay.get("reference"),
                    "debit", "0.00",
                    "credit", Money.money(pay.get("amount"))));
        }
        events.sort((a, b) -> String.valueOf(a.get("date")).compareTo(String.valueOf(b.get("date"))));
        BigDecimal opening = stationIds == null ? Money.d(supplier.get("opening_balance")) : BigDecimal.ZERO;
        BigDecimal running = opening;
        List<Map<String, Object>> ledger = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            running = running.add(Money.d(ev.get("debit"))).subtract(Money.d(ev.get("credit")));
            Map<String, Object> item = new LinkedHashMap<>(ev);
            item.put("runningBalance", Money.money(running));
            ledger.add(item);
        }
        Map<String, Object> shapedSupplier = Json.shape(supplier);
        shapedSupplier.put("openingBalance", Money.money(opening));
        ctx.ok(Web.map(
                "supplier", shapedSupplier,
                "ledger", ledger,
                "entries", ledger,
                "openingBalance", Money.money(opening),
                "closingBalance", Money.money(running),
                "note", "Fuel purchases enter the payable ledger when a delivery is marked received; lubricant purchases post when recorded. Supplier opening balance is shown only in the all-station ledger because it is not station-allocated."));
    }

    private static void payments(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("payments.read");
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        String where = "p.tenant_id = ?";
        List<Object> params = new ArrayList<>(List.of(auth.tenantId()));
        if (ctx.q("stationId") != null && !ctx.q("stationId").isBlank()) {
            auth.station(ctx.q("stationId"));
            where += " AND p.station_id = ?";
            params.add(ctx.q("stationId"));
        } else if (!auth.allStations) {
            if (auth.stationIds.isEmpty()) {
                ctx.ok(List.of(), Domain.page(0, page, size));
                return;
            }
            List<String> allowed = new ArrayList<>(auth.stationIds);
            where += " AND p.station_id IN (" + String.join(",", Collections.nCopies(allowed.size(), "?")) + ")";
            params.addAll(allowed);
        }
        if (ctx.q("direction") != null && !ctx.q("direction").isBlank()) {
            where += " AND p.direction = ?";
            params.add(ctx.q("direction").trim());
        }
        if (ctx.q("mode") != null && !ctx.q("mode").isBlank()) {
            where += " AND p.mode = ?";
            params.add(ctx.q("mode").trim());
        }
        if (ctx.q("status") != null && !ctx.q("status").isBlank()) {
            where += " AND p.status = ?";
            params.add(ctx.q("status").trim());
        }
        if (ctx.q("from") != null && !ctx.q("from").isBlank()) {
            String fromDay = Domain.requireDate(ctx.q("from"), "from");
            where += " AND p.occurred_at >= ?";
            params.add(Clock.istDayStart(fromDay));
        }
        if (ctx.q("to") != null && !ctx.q("to").isBlank()) {
            String toDay = Domain.requireDate(ctx.q("to"), "to");
            where += " AND p.occurred_at <= ?";
            params.add(Clock.istDayEnd(toDay));
        }
        long total = Db.count("SELECT COUNT(*) AS count FROM payments p WHERE " + where, params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        ctx.ok(Json.shapeAll(Db.all(
                """
                SELECT p.*, c.name AS customer_name, s.name AS supplier_name, st.name AS station_name, u.name AS created_by_name
                  FROM payments p
                  LEFT JOIN customers c ON c.id = p.customer_id
                  LEFT JOIN suppliers s ON s.id = p.supplier_id
                  LEFT JOIN stations st ON st.id = p.station_id
                  LEFT JOIN users u ON u.id = p.created_by
                 WHERE %s ORDER BY p.occurred_at DESC, p.created_at DESC LIMIT ? OFFSET ?
                """.formatted(where), params.toArray())), Domain.page(total, page, size));
    }

    private static void createPayment(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("payments.write");
        String stationId = requiredBody(ctx, "stationId");
        BigDecimal amountDec = FinanceRules.positiveMoney(requiredBody(ctx, "amount"), "amount");
        String amt = Money.money(amountDec);
        String occurredAt = resolveOccurredAtIso(ctx.str("occurredAt"));
        String businessDate = resolveBusinessDate(occurredAt);
        final String rawCustomerId = blankToNull(ctx.str("customerId") != null ? ctx.str("customerId") : ctx.str("partyId"));
        final String rawSupplierId = blankToNull(ctx.str("supplierId") != null ? ctx.str("supplierId") : ctx.str("partyId"));
        String rawPartyType = blankToNull(ctx.str("partyType"));
        if (rawPartyType == null) rawPartyType = rawSupplierId != null ? "SUPPLIER" : "CUSTOMER";
        String rawDirection = blankToNull(ctx.str("direction"));
        if (rawDirection == null) rawDirection = "SUPPLIER".equals(rawPartyType) ? "PAYOUT" : "RECEIPT";
        FinanceRules.PaymentParty party = FinanceRules.validatePayment(rawPartyType, rawDirection, rawCustomerId, rawSupplierId,
                blankToNull(ctx.str("mode")) == null ? "CASH" : ctx.str("mode"));
        String partyType = party.partyType();
        String direction = party.direction();
        final String customerIdForTx = party.customerId();
        final String supplierIdForTx = party.supplierId();
        String mode = ctx.str("mode") == null || ctx.str("mode").isBlank() ? "CASH" : ctx.str("mode").trim().toUpperCase(java.util.Locale.ROOT);
        String reference = blankToNull(ctx.str("reference"));
        String settlementRef = blankToNull(ctx.str("settlementRef"));
        String status = ("CASH".equals(mode) || settlementRef != null) ? "SETTLED" : "POSTED";
        String notes = blankToNull(ctx.str("notes") != null ? ctx.str("notes") : ctx.str("note"));

        Map<String, Object> created = Db.tx(() -> {
            auth.station(stationId);
            Domain.assertDayOpen(stationId, businessDate);
            if (reference != null && Db.one("SELECT id FROM payments WHERE tenant_id = ? AND reference = ?", auth.tenantId(), reference) != null) {
                throw ApiException.conflict("A payment with this reference already exists");
            }
            Map<String, Object> customer = null;
            if (customerIdForTx != null) {
                customer = Db.one("SELECT * FROM customers WHERE id = ? AND tenant_id = ? FOR UPDATE", customerIdForTx, auth.tenantId());
                if (customer == null) throw ApiException.notFound("Customer not found");
            }
            if (rawSupplierId != null) {
                if (Db.one("SELECT id FROM suppliers WHERE id = ? AND tenant_id = ?", rawSupplierId, auth.tenantId()) == null) {
                    throw ApiException.notFound("Supplier not found");
                }
            }
            String id = Clock.newId("pay");
            String createdNow = Clock.nowIso();
            Db.exec(
                    """
                    INSERT INTO payments
                      (id, tenant_id, station_id, direction, party_type, customer_id, supplier_id, amount,
                       mode, status, settlement_ref, settled_at, reference, notes, occurred_at, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, auth.tenantId(), stationId, direction, partyType, rawCustomerId, rawSupplierId,
                    amt, mode, status, settlementRef, "SETTLED".equals(status) ? occurredAt : null,
                    reference, notes, occurredAt, auth.userId, createdNow);
            if (customer != null && "RECEIPT".equals(direction)) {
                String newBalance = Money.money(Money.d(customer.get("balance")).subtract(amountDec));
                Db.exec("UPDATE customers SET balance = ?, updated_at = ? WHERE id = ?", newBalance, createdNow, rawCustomerId);
                Db.exec(
                        """
                        INSERT INTO credit_entries
                          (id, tenant_id, station_id, customer_id, type, amount, payment_id, reference, occurred_at, note, status, created_by, created_at)
                        VALUES (?, ?, ?, ?, 'PAYMENT', ?, ?, ?, ?, ?, 'POSTED', ?, ?)
                        """,
                        Clock.newId("cre"), auth.tenantId(), stationId, rawCustomerId,
                        Money.money(amountDec.negate()), id, reference, occurredAt, notes, auth.userId, createdNow);
            }
            Auth.auditRequired(auth, "PAYMENT_RECORDED", "Payment", id, stationId, direction + " " + mode + " ₹" + amt, null, Db.one("SELECT * FROM payments WHERE id = ?", id));
            return loadShapedPayment(id);
        });
        ctx.created(created);
    }

    private static void settle(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("payments.write");
        String paymentId = ctx.p("id");
        String settlementRef = blankToNull(ctx.str("settlementRef"));
        if (settlementRef == null) {
            throw ApiException.validation("settlementRef", "Settlement reference is required");
        }
        Map<String, Object> settled = Db.tx(() -> {
            Map<String, Object> payment = Db.one("SELECT * FROM payments WHERE id = ? AND tenant_id = ? FOR UPDATE", paymentId, auth.tenantId());
            if (payment == null) throw ApiException.notFound("Payment not found");
            String stationId = Db.str(payment, "station_id");
            auth.station(stationId);
            String status = Db.str(payment, "status");
            if ("REVERSED".equals(status)) {
                throw ApiException.conflict("Reversed payments cannot be settled");
            }
            if ("SETTLED".equals(status)) {
                throw ApiException.conflict("Payment is already settled");
            }
            Domain.assertDayOpen(stationId, resolveBusinessDate(Db.str(payment, "occurred_at")));
            String now = Clock.nowIso();
            Db.exec("UPDATE payments SET status = 'SETTLED', settlement_ref = ?, settled_at = ? WHERE id = ?",
                    settlementRef, now, paymentId);
            Auth.auditRequired(auth, "PAYMENT_SETTLED", "Payment", paymentId, stationId, "Settled ref " + settlementRef, payment, Db.one("SELECT * FROM payments WHERE id = ?", paymentId));
            return loadShapedPayment(paymentId);
        });
        ctx.ok(settled);
    }

    private static void reversePayment(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("payments.write");
        String paymentId = ctx.p("id");
        String reason = blankToNull(ctx.str("reason"));
        if (reason == null || reason.length() < 3) {
            throw ApiException.validation("reason", "Reversal reason must be at least 3 characters");
        }
        Map<String, Object> result = Db.tx(() -> {
            Map<String, Object> payment = Db.one("SELECT * FROM payments WHERE id = ? AND tenant_id = ? FOR UPDATE", paymentId, auth.tenantId());
            if (payment == null) throw ApiException.notFound("Payment not found");
            String stationId = Db.str(payment, "station_id");
            auth.station(stationId);
            String status = Db.str(payment, "status");
            if ("REVERSED".equals(status)) {
                throw ApiException.conflict("Payment has already been reversed");
            }
            String payDate = resolveBusinessDate(Db.str(payment, "occurred_at"));
            Domain.assertDayOpen(stationId, payDate);
            if (!payDate.equals(Clock.todayIst())) {
                Domain.assertDayOpen(stationId, Clock.todayIst());
            }
            String now = Clock.nowIso();
            String existingNotes = blankToNull(Db.str(payment, "notes"));
            String updatedNotes = (existingNotes == null ? "" : existingNotes + " · ") + "Reversed: " + reason;
            Db.exec("UPDATE payments SET status = 'REVERSED', notes = ? WHERE id = ?", updatedNotes, paymentId);

            String customerId = blankToNull(Db.str(payment, "customer_id"));
            if ("RECEIPT".equals(Db.str(payment, "direction")) && customerId != null) {
                Map<String, Object> customer = Db.one("SELECT * FROM customers WHERE id = ? AND tenant_id = ? FOR UPDATE", customerId, auth.tenantId());
                if (customer != null) {
                    BigDecimal amt = Money.d(payment.get("amount")).abs();
                    String restoredBalance = Money.money(Money.d(customer.get("balance")).add(amt));
                    Db.exec("UPDATE customers SET balance = ?, updated_at = ? WHERE id = ?", restoredBalance, now, customerId);
                    String ref = blankToNull(Db.str(payment, "reference"));
                    Db.exec(
                            """
                            INSERT INTO credit_entries
                              (id, tenant_id, station_id, customer_id, type, amount, payment_id, reference, occurred_at, note, status, created_by, created_at)
                            VALUES (?, ?, ?, ?, 'PAYMENT_REVERSAL', ?, ?, ?, ?, ?, 'POSTED', ?, ?)
                            """,
                            Clock.newId("cre"), auth.tenantId(), stationId, customerId,
                            Money.money(amt), paymentId, ref != null ? ref + " (REV)" : "REVERSAL",
                            now, "Payment reversal: " + reason, auth.userId, now);
                }
            }
            Auth.auditRequired(auth, "PAYMENT_REVERSED", "Payment", paymentId, stationId,
                    "Reversed ₹" + Money.money(payment.get("amount")) + ": " + reason, payment, Db.one("SELECT * FROM payments WHERE id = ?", paymentId));
            Map<String, Object> shaped = loadShapedPayment(paymentId);
            shaped.put("reversed", true);
            return shaped;
        });
        ctx.ok(result);
    }

    private static Map<String, Object> loadShapedPayment(String id) {
        Map<String, Object> row = Db.one(
                """
                SELECT p.*, c.name AS customer_name, s.name AS supplier_name, st.name AS station_name, u.name AS created_by_name
                  FROM payments p
                  LEFT JOIN customers c ON c.id = p.customer_id
                  LEFT JOIN suppliers s ON s.id = p.supplier_id
                  LEFT JOIN stations st ON st.id = p.station_id
                  LEFT JOIN users u ON u.id = p.created_by
                 WHERE p.id = ?
                """, id);
        return Json.shape(row);
    }

    private static BigDecimal parsePositiveMoney(String raw, String field) {
        final BigDecimal val;
        try {
            val = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.validation(field, field + " must be a valid amount");
        }
        if (val.signum() <= 0) {
            throw ApiException.validation(field, field + " must be greater than zero");
        }
        return val;
    }

    private static String resolveOccurredAtIso(String raw) {
        if (raw == null || raw.isBlank()) return Clock.nowIso();
        String s = raw.trim();
        if (Clock.isIsoDate(s)) return Clock.istDayStart(s);
        try {
            java.time.Instant.parse(s);
            return s;
        } catch (RuntimeException ex) {
            throw ApiException.validation("occurredAt", "occurredAt must be an ISO timestamp or YYYY-MM-DD date");
        }
    }

    private static String resolveBusinessDate(String isoOrDate) {
        if (isoOrDate == null || isoOrDate.isBlank()) return Clock.todayIst();
        String s = isoOrDate.trim();
        if (Clock.isIsoDate(s)) return s;
        if (s.length() >= 10 && Clock.isIsoDate(s.substring(0, 10))) {
            try {
                return Clock.businessDateOf(s);
            } catch (RuntimeException ignored) {
                return s.substring(0, 10);
            }
        }
        return Clock.todayIst();
    }

    private static void expenses(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("expenses.read");
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        String where = "tenant_id = ?";
        List<Object> params = new ArrayList<>(List.of(auth.tenantId()));
        if (ctx.q("stationId") != null && !ctx.q("stationId").isBlank()) {
            auth.station(ctx.q("stationId"));
            where += " AND station_id = ?";
            params.add(ctx.q("stationId"));
        } else if (!auth.allStations) {
            if (auth.stationIds.isEmpty()) {
                ctx.ok(Map.of("expenses", List.of(), "totalAmount", "0.00"), Domain.page(0, page, size));
                return;
            }
            List<String> allowed = new ArrayList<>(auth.stationIds);
            where += " AND station_id IN (" + String.join(",", Collections.nCopies(allowed.size(), "?")) + ")";
            params.addAll(allowed);
        }
        if (ctx.q("from") != null && !ctx.q("from").isBlank()) { where += " AND occurred_at >= ?"; params.add(Clock.istDayStart(Domain.requireDate(ctx.q("from"), "from"))); }
        if (ctx.q("to") != null && !ctx.q("to").isBlank()) { where += " AND occurred_at <= ?"; params.add(Clock.istDayEnd(Domain.requireDate(ctx.q("to"), "to"))); }
        long total = Db.count("SELECT COUNT(*) AS count FROM expenses WHERE " + where, params.toArray());
        Map<String, Object> sum = Db.one("SELECT COALESCE(SUM(CAST(amount AS DECIMAL(20,2))), 0) AS total FROM expenses WHERE " + where, params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        ctx.ok(Map.of("expenses", Json.shapeAll(Db.all("SELECT e.*, u.name AS created_by_name FROM expenses e LEFT JOIN users u ON u.id = e.created_by WHERE " + where.replace("tenant_id", "e.tenant_id").replace("station_id", "e.station_id").replace("occurred_at", "e.occurred_at") + " ORDER BY e.occurred_at DESC LIMIT ? OFFSET ?", params.toArray())), "totalAmount", Money.money(sum.get("total"))), Domain.page(total, page, size));
    }

    private static void createExpense(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("expenses.write");
        String stationId = requiredBody(ctx, "stationId");
        String description = requiredBody(ctx, "description").trim();
        BigDecimal amountDec = FinanceRules.positiveMoney(requiredBody(ctx, "amount"), "amount");
        String occurredAt = resolveOccurredAtIso(ctx.str("occurredAt"));
        String businessDate = resolveBusinessDate(occurredAt);
        String paidVia = (ctx.str("paidVia") == null || ctx.str("paidVia").isBlank()) ? "CASH" : ctx.str("paidVia").trim().toUpperCase(java.util.Locale.ROOT);
        if (!FinanceRules.PAYMENT_MODES.contains(paidVia)) throw ApiException.validation("paidVia", "paidVia must be CASH, UPI, CARD, BANK or CHEQUE");
        String categoryId = blankToNull(ctx.str("categoryId"));
        String categoryName = blankToNull(ctx.str("categoryName"));

        Map<String, Object> created = Db.tx(() -> {
            Map<String, Object> station = auth.station(stationId);
            Domain.assertDayOpen(stationId, businessDate);
            String resolvedCatName = "General";
            if (categoryId != null) {
                Map<String, Object> cat = Db.one("SELECT name FROM expense_categories WHERE id = ? AND tenant_id = ? AND is_active = 1", categoryId, auth.tenantId());
                if (cat == null) throw ApiException.notFound("Active expense category not found");
                resolvedCatName = Db.str(cat, "name");
            } else if (categoryName != null) {
                resolvedCatName = categoryName;
            }
            String id = Clock.newId("exp");
            String now = Clock.nowIso();
            Db.exec(
                    """
                    INSERT INTO expenses
                      (id, tenant_id, station_id, category_id, category_name, description, amount, occurred_at, paid_via, reference, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, auth.tenantId(), station.get("id"), categoryId, resolvedCatName,
                    description, Money.money(amountDec), occurredAt,
                    paidVia, blankToNull(ctx.str("reference")), auth.userId, now);
            Auth.auditRequired(auth, "EXPENSE_RECORDED", "Expense", id, stationId, description + ": ₹" + Money.money(amountDec), null, Db.one("SELECT * FROM expenses WHERE id = ?", id));
            return Json.shape(Db.one("SELECT * FROM expenses WHERE id = ?", id));
        });
        ctx.created(created);
    }

    private static void deleteExpense(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("expenses.write");
        String expenseId = ctx.p("id");
        Db.tx(() -> {
            Map<String, Object> exp = Db.one("SELECT * FROM expenses WHERE id = ? AND tenant_id = ?", expenseId, auth.tenantId());
            if (exp == null) throw ApiException.notFound("Expense not found");
            String stationId = Db.str(exp, "station_id");
            auth.station(stationId);
            Domain.assertDayOpen(stationId, resolveBusinessDate(Db.str(exp, "occurred_at")));
            Db.exec("DELETE FROM expenses WHERE id = ?", expenseId);
            Auth.auditRequired(auth, "EXPENSE_DELETED", "Expense", expenseId, stationId, "Deleted expense: " + exp.get("description"), exp, null);
            return null;
        });
        ctx.ok(Map.of("deleted", true));
    }

    /**
     * Cash & Digital Reconciliation Formula (Safeguard 7 — zero double-counting):
     *
     * 1. cashSales =
     *      (a) Settled closed/flagged shift net cash fuel sales:
     *          SUM(CAST(ss.expected_amount AS DECIMAL(20,2)) - CAST(s.opening_cash AS DECIMAL(20,2)))
     *          for shifts on businessDate with status IN ('CLOSED','FLAGGED') and ss.mode = 'CASH'
     *      + (b) Standalone fuel_transactions (payment_mode = 'CASH', voided_at IS NULL, in IST day window)
     *          excluding any transaction linked to a shift already settled in shift_settlements
     *      + (c) Lubricant counter sales (lube_movements type = 'SALE', payment_mode = 'CASH', in IST day window)
     *
     * 2. digitalSales =
     *      (a) Settled closed/flagged shift digital fuel collections (ss.mode IN ('UPI','CARD','BANK'))
     *      + (b) Standalone fuel_transactions (payment_mode IN ('UPI','CARD','BANK'), voided_at IS NULL, not in settled shift)
     *      + (c) Lubricant counter sales (lube_movements type = 'SALE', payment_mode IN ('UPI','CARD','BANK'))
     *
     * 3. creditSales (informational — excluded from expected.cash and expected.digital) =
     *      POSTED credit_entries (type = 'CREDIT_SALE')
     *      + unlinked CREDIT fuel_transactions (no matching credit_entries.transaction_id, not in settled shift)
     *      + unlinked CREDIT lube_movements (type = 'SALE', no matching credit_entries reference)
     *      + any shift_settlements CREDIT excess above itemized shift credit sales.
     *
     * 4. cashReceipts / digitalReceipts = non-REVERSED payments with direction = 'RECEIPT' (disjoint from direct sales).
     * 5. cashPayouts / digitalPayouts   = non-REVERSED payments with direction = 'PAYOUT' (disjoint from expenses).
     * 6. cashExpenses / digitalExpenses = expenses grouped by paid_via ('CASH' vs 'UPI'/'CARD'/'BANK'/'CHEQUE').
     * 7. shiftAggregateLitres / shiftAggregateValue = closed/flagged totalizer-only shifts WITHOUT shift_settlements.
     *
     * Final expectations:
     *   expected.cash    = cashSales + cashReceipts - cashExpenses - cashPayouts
     *   expected.digital = digitalSales + digitalReceipts - digitalExpenses - digitalPayouts
     */
    private static Map<String, Object> computeExpectedReconciliation(String stationId, String date) {
        String start = Clock.istDayStart(date);
        String end = Clock.istDayEnd(date);

        // (a) Settled shifts (status IN ('CLOSED','FLAGGED') with shift_settlements rows)
        BigDecimal shiftCashSales = moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(ss.expected_amount AS DECIMAL(20,2)) - CAST(s.opening_cash AS DECIMAL(20,2))), 0) AS total
                  FROM shift_settlements ss
                  JOIN shifts s ON s.id = ss.shift_id
                 WHERE s.station_id = ? AND s.business_date = ? AND s.status IN ('CLOSED','FLAGGED') AND ss.mode = 'CASH'
                """, stationId, date);
        BigDecimal shiftDigitalSales = moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(COALESCE(ss.received_amount, ss.expected_amount) AS DECIMAL(20,2))), 0) AS total
                  FROM shift_settlements ss
                  JOIN shifts s ON s.id = ss.shift_id
                 WHERE s.station_id = ? AND s.business_date = ? AND s.status IN ('CLOSED','FLAGGED')
                   AND ss.mode IN ('UPI','CARD','BANK')
                """, stationId, date);
        BigDecimal shiftCreditSales = moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(COALESCE(ss.received_amount, ss.expected_amount) AS DECIMAL(20,2))), 0) AS total
                  FROM shift_settlements ss
                  JOIN shifts s ON s.id = ss.shift_id
                 WHERE s.station_id = ? AND s.business_date = ? AND s.status IN ('CLOSED','FLAGGED')
                   AND ss.mode = 'CREDIT'
                """, stationId, date);

        // Totalizer-only shifts WITHOUT shift_settlements (do not inflate expected.cash)
        Map<String, Object> unsplitShifts = Db.one(
                """
                SELECT COALESCE(SUM(CAST(s.total_litres AS DECIMAL(20,3))), 0) AS litres,
                       COALESCE(SUM(CAST(s.total_value AS DECIMAL(20,2))), 0) AS value
                  FROM shifts s
                 WHERE s.station_id = ? AND s.business_date = ? AND s.status IN ('CLOSED','FLAGGED')
                   AND NOT EXISTS (SELECT 1 FROM shift_settlements ss WHERE ss.shift_id = s.id)
                """, stationId, date);

        // (b) Standalone fuel_transactions (not part of a settled shift)
        BigDecimal txCashSales = standaloneFuelTxTotal(stationId, start, end, List.of("CASH"));
        BigDecimal txDigitalSales = standaloneFuelTxTotal(stationId, start, end, List.of("UPI", "CARD", "BANK"));

        // (c) Lubricant counter sales (disjoint from shifts and payments)
        BigDecimal lubeCashSales = lubeSaleTotal(stationId, start, end, List.of("CASH"));
        BigDecimal lubeDigitalSales = lubeSaleTotal(stationId, start, end, List.of("UPI", "CARD", "BANK"));

        BigDecimal cashSales = shiftCashSales.add(txCashSales).add(lubeCashSales);
        BigDecimal digitalSales = shiftDigitalSales.add(txDigitalSales).add(lubeDigitalSales);

        // Informational credit sales (never added to cash or digital)
        BigDecimal postedCreditEntries = moneyQuery(
                """
                SELECT COALESCE(SUM(ABS(CAST(amount AS DECIMAL(20,2)))), 0) AS total
                  FROM credit_entries
                 WHERE station_id = ? AND type = 'CREDIT_SALE' AND status = 'POSTED'
                   AND occurred_at >= ? AND occurred_at <= ?
                """, stationId, start, end);
        BigDecimal unlinkedCreditTx = moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(ft.amount AS DECIMAL(20,2))), 0) AS total
                  FROM fuel_transactions ft
                 WHERE ft.station_id = ? AND ft.payment_mode = 'CREDIT' AND ft.voided_at IS NULL
                   AND ft.occurred_at >= ? AND ft.occurred_at <= ?
                   AND NOT EXISTS (SELECT 1 FROM credit_entries ce WHERE ce.transaction_id = ft.id AND ce.status = 'POSTED')
                   AND (ft.shift_id IS NULL OR NOT EXISTS (
                        SELECT 1 FROM shift_settlements ss JOIN shifts s ON s.id = ss.shift_id
                         WHERE ss.shift_id = ft.shift_id AND s.status IN ('CLOSED','FLAGGED')))
                """, stationId, start, end);
        BigDecimal unlinkedCreditLube = BigDecimal.ZERO;
        for (Map<String, Object> lm : Db.all(
                """
                SELECT id, amount
                  FROM lube_movements
                 WHERE station_id = ? AND type = 'SALE' AND payment_mode = 'CREDIT'
                   AND occurred_at >= ? AND occurred_at <= ?
                """, stationId, start, end)) {
            String lubeRef = "LUBE:" + Db.str(lm, "id");
            if (Db.one("SELECT id FROM credit_entries WHERE station_id = ? AND type = 'CREDIT_SALE' AND status = 'POSTED' AND reference = ?",
                    stationId, lubeRef) == null) {
                unlinkedCreditLube = unlinkedCreditLube.add(Money.d(lm.get("amount")).abs());
            }
        }
        BigDecimal itemizedCredit = postedCreditEntries.add(unlinkedCreditTx).add(unlinkedCreditLube);
        BigDecimal creditSales = itemizedCredit.max(shiftCreditSales);

        // Receipts & Payouts from non-REVERSED payments
        BigDecimal cashReceipts = paymentTotal(stationId, start, end, "RECEIPT", List.of("CASH"));
        BigDecimal digitalReceipts = paymentTotal(stationId, start, end, "RECEIPT", List.of("UPI", "CARD", "BANK", "CHEQUE"));
        BigDecimal cashPayouts = paymentTotal(stationId, start, end, "PAYOUT", List.of("CASH"));
        BigDecimal digitalPayouts = paymentTotal(stationId, start, end, "PAYOUT", List.of("UPI", "CARD", "BANK", "CHEQUE"));

        // Station expenses by paid_via
        BigDecimal cashExpenses = expenseTotal(stationId, start, end, List.of("CASH"));
        BigDecimal digitalExpenses = expenseTotal(stationId, start, end, List.of("UPI", "CARD", "BANK", "CHEQUE"));

        BigDecimal expectedCash = cashSales.add(cashReceipts).subtract(cashExpenses).subtract(cashPayouts);
        BigDecimal expectedDigital = digitalSales.add(digitalReceipts).subtract(digitalExpenses).subtract(digitalPayouts);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("cash", Money.money(expectedCash));
        expected.put("digital", Money.money(expectedDigital));
        expected.put("cashSales", Money.money(cashSales));
        expected.put("digitalSales", Money.money(digitalSales));
        expected.put("creditSales", Money.money(creditSales));
        expected.put("cashReceipts", Money.money(cashReceipts));
        expected.put("digitalReceipts", Money.money(digitalReceipts));
        expected.put("cashExpenses", Money.money(cashExpenses));
        expected.put("cashPayouts", Money.money(cashPayouts));
        expected.put("digitalExpenses", Money.money(digitalExpenses));
        expected.put("digitalPayouts", Money.money(digitalPayouts));
        expected.put("shiftAggregateLitres", Money.litres(unsplitShifts.get("litres")));
        expected.put("shiftAggregateValue", Money.money(unsplitShifts.get("value")));
        return expected;
    }

    private static BigDecimal moneyQuery(String sql, Object... params) {
        Map<String, Object> row = Db.one(sql, params);
        return row == null ? BigDecimal.ZERO : Money.d(row.get("total"));
    }

    private static BigDecimal standaloneFuelTxTotal(String stationId, String start, String end, List<String> modes) {
        String in = String.join(",", Collections.nCopies(modes.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(stationId);
        params.addAll(modes);
        params.add(start);
        params.add(end);
        return moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(ft.amount AS DECIMAL(20,2))), 0) AS total
                  FROM fuel_transactions ft
                 WHERE ft.station_id = ? AND ft.payment_mode IN (%s) AND ft.voided_at IS NULL
                   AND ft.occurred_at >= ? AND ft.occurred_at <= ?
                   AND (ft.shift_id IS NULL OR NOT EXISTS (
                        SELECT 1 FROM shift_settlements ss JOIN shifts s ON s.id = ss.shift_id
                         WHERE ss.shift_id = ft.shift_id AND s.status IN ('CLOSED','FLAGGED')))
                """.formatted(in), params.toArray());
    }

    private static BigDecimal lubeSaleTotal(String stationId, String start, String end, List<String> modes) {
        String in = String.join(",", Collections.nCopies(modes.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(stationId);
        params.addAll(modes);
        params.add(start);
        params.add(end);
        return moneyQuery(
                """
                SELECT COALESCE(SUM(ABS(CAST(amount AS DECIMAL(20,2)))), 0) AS total
                  FROM lube_movements
                 WHERE station_id = ? AND type = 'SALE' AND payment_mode IN (%s)
                   AND occurred_at >= ? AND occurred_at <= ?
                """.formatted(in), params.toArray());
    }

    private static BigDecimal paymentTotal(String stationId, String start, String end, String direction, List<String> modes) {
        String in = String.join(",", Collections.nCopies(modes.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(stationId);
        params.add(direction);
        params.addAll(modes);
        params.add(start);
        params.add(end);
        return moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(amount AS DECIMAL(20,2))), 0) AS total
                  FROM payments
                 WHERE station_id = ? AND direction = ? AND mode IN (%s)
                   AND status != 'REVERSED'
                   AND occurred_at >= ? AND occurred_at <= ?
                """.formatted(in), params.toArray());
    }

    private static BigDecimal expenseTotal(String stationId, String start, String end, List<String> modes) {
        String in = String.join(",", Collections.nCopies(modes.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(stationId);
        params.addAll(modes);
        params.add(start);
        params.add(end);
        return moneyQuery(
                """
                SELECT COALESCE(SUM(CAST(amount AS DECIMAL(20,2))), 0) AS total
                  FROM expenses
                 WHERE station_id = ? AND paid_via IN (%s)
                   AND occurred_at >= ? AND occurred_at <= ?
                """.formatted(in), params.toArray());
    }

    private static Map<String, Object> shapeReconciliationRow(Map<String, Object> raw) {
        if (raw == null) return null;
        Map<String, Object> shaped = Json.shape(raw);
        BigDecimal cashVar = Money.d(raw.get("cash_counted")).subtract(Money.d(raw.get("cash_expected")));
        BigDecimal digVar = Money.d(raw.get("digital_settled")).subtract(Money.d(raw.get("digital_expected")));
        shaped.put("cashExpected", Money.money(raw.get("cash_expected")));
        shaped.put("cashCounted", Money.money(raw.get("cash_counted")));
        shaped.put("digitalExpected", Money.money(raw.get("digital_expected")));
        shaped.put("digitalSettled", Money.money(raw.get("digital_settled")));
        shaped.put("cashVariance", Money.money(cashVar));
        shaped.put("digitalVariance", Money.money(digVar));
        shaped.put("variance", Money.money(raw.get("variance")));
        return shaped;
    }

    private static void reconciliation(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("reconciliation.read");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        String stationId = Db.str(station, "id");
        String date = Domain.requireDate(ctx.q("businessDate"), "Date");
        Map<String, Object> expected = computeExpectedReconciliation(stationId, date);
        Map<String, Object> existing = Db.one(
                "SELECT * FROM reconciliations WHERE station_id = ? AND business_date = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                stationId, date);
        List<Map<String, Object>> historyRows = Db.all(
                "SELECT * FROM reconciliations WHERE station_id = ? ORDER BY business_date DESC, created_at DESC, id DESC LIMIT 30",
                stationId);
        List<Map<String, Object>> history = new ArrayList<>(historyRows.size());
        for (Map<String, Object> r : historyRows) {
            history.add(shapeReconciliationRow(r));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stationId", stationId);
        data.put("businessDate", date);
        data.put("expected", expected);
        data.put("note", "Expected cash and digital figures combine settled shifts, standalone fuel & lubricant sales, non-reversed receipts/payouts, and expenses without double-counting.");
        data.put("history", history);
        data.put("row", shapeReconciliationRow(existing));
        ctx.ok(data);
    }

    private static void saveReconciliation(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("reconciliation.write");
        String stationId = requiredBody(ctx, "stationId");
        String date = Domain.requireDate(requiredBody(ctx, "businessDate"), "Business date");
        BigDecimal cashCounted = FinanceRules.nonNegativeMoney(
                ctx.str("cashCounted") == null || ctx.str("cashCounted").isBlank() ? "0" : ctx.str("cashCounted"),
                "cashCounted");
        BigDecimal digitalSettled = FinanceRules.nonNegativeMoney(
                ctx.str("digitalSettled") == null || ctx.str("digitalSettled").isBlank() ? "0" : ctx.str("digitalSettled"),
                "digitalSettled");
        String notes = blankToNull(ctx.str("notes"));

        Map<String, Object> saved = Db.tx(() -> {
            auth.station(stationId);
            // Serialize revisions for this station without changing the existing schema.
            if (Db.one("SELECT id FROM stations WHERE id = ? FOR UPDATE", stationId) == null) throw ApiException.notFound("Station not found");
            Domain.assertDayOpen(stationId, date);
            Map<String, Object> expected = computeExpectedReconciliation(stationId, date);
            BigDecimal cashExpected = Money.d(expected.get("cash"));
            BigDecimal digitalExpected = Money.d(expected.get("digital"));
            FinanceRules.Variances variance = FinanceRules.reconciliation(cashExpected, cashCounted, digitalExpected, digitalSettled);
            BigDecimal cashVariance = variance.cash();
            BigDecimal digitalVariance = variance.digital();
            String status = variance.status();
            BigDecimal storedVariance = variance.displayed();

            Map<String, Object> existing = Db.one(
                    "SELECT * FROM reconciliations WHERE station_id = ? AND business_date = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                    stationId, date);
            String now = Clock.nowIso();
            boolean sameCounts = existing != null
                    && Money.money(existing.get("cash_expected")).equals(Money.money(cashExpected))
                    && Money.money(existing.get("cash_counted")).equals(Money.money(cashCounted))
                    && Money.money(existing.get("digital_expected")).equals(Money.money(digitalExpected))
                    && Money.money(existing.get("digital_settled")).equals(Money.money(digitalSettled))
                    && java.util.Objects.equals(blankToNull(Db.str(existing, "notes")), notes);
            if (sameCounts) return shapeReconciliationRow(existing);
            String revisionReason = blankToNull(ctx.str("revisionReason"));
            if (existing != null && "APPROVED".equals(Db.str(existing, "status"))
                    && (revisionReason == null || revisionReason.length() < 10)) {
                throw ApiException.validation("revisionReason", "Changing an approved reconciliation requires a revision reason of at least 10 characters");
            }
            String id = Clock.newId("rec");
            Db.exec(
                    """
                    INSERT INTO reconciliations
                      (id, tenant_id, station_id, business_date, type, cash_expected, cash_counted,
                       digital_expected, digital_settled, variance, status, notes, created_by, created_at)
                    VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, auth.tenantId(), stationId, date,
                    Money.money(cashExpected), Money.money(cashCounted),
                    Money.money(digitalExpected), Money.money(digitalSettled),
                    Money.money(storedVariance), status,
                    revisionReason == null ? notes : (notes == null ? "Revision: " + revisionReason : notes + " · Revision: " + revisionReason),
                    auth.userId, now);
            Map<String, Object> savedRow = Db.one("SELECT * FROM reconciliations WHERE id = ?", id);
            Auth.auditRequired(auth, "RECONCILIATION_SAVED", "Reconciliation", id, stationId,
                    date + " " + status + " (cashVar=" + Money.money(cashVariance) + ", digitalVar=" + Money.money(digitalVariance) + ")",
                    existing, savedRow);
            return shapeReconciliationRow(Db.one("SELECT * FROM reconciliations WHERE id = ?", id));
        });
        ctx.created(saved);
    }

    private static void approveReconciliation(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("reconciliation.write");
        String recId = ctx.p("id");
        String note = blankToNull(ctx.str("note"));
        if (note == null || note.length() < 3) throw ApiException.validation("note", "Approval note must be at least 3 characters");
        Map<String, Object> approved = Db.tx(() -> {
            Map<String, Object> rec = Db.one("SELECT * FROM reconciliations WHERE id = ? AND tenant_id = ? FOR UPDATE", recId, auth.tenantId());
            if (rec == null) throw ApiException.notFound("Reconciliation not found");
            String stationId = Db.str(rec, "station_id");
            auth.station(stationId);
            String date = Db.str(rec, "business_date").substring(0, 10);
            Map<String, Object> latest = Db.one("SELECT id FROM reconciliations WHERE station_id = ? AND business_date = ? ORDER BY created_at DESC, id DESC LIMIT 1", stationId, date);
            if (latest == null || !recId.equals(Db.str(latest, "id"))) throw ApiException.conflict("A newer reconciliation revision exists. Review and approve the latest revision.");
            Domain.assertDayOpen(stationId, date);
            String status = Db.str(rec, "status");
            if ("APPROVED".equals(status)) {
                throw ApiException.conflict("Reconciliation is already approved");
            }
            if ("BALANCED".equals(status)) {
                throw ApiException.conflict("Balanced reconciliations do not require approval");
            }
            String existingNotes = blankToNull(Db.str(rec, "notes"));
            String mergedNotes = note == null ? existingNotes : (existingNotes == null ? note : existingNotes + " · Approved: " + note);
            String now = Clock.nowIso();
            Db.exec("UPDATE reconciliations SET status = 'APPROVED', approved_by = ?, approved_at = ?, notes = ? WHERE id = ?",
                    auth.userId, now, mergedNotes, recId);
            Auth.auditRequired(auth, "RECONCILIATION_APPROVED", "Reconciliation", recId, stationId, date + " variance approved: " + note,
                    rec, Db.one("SELECT * FROM reconciliations WHERE id = ?", recId));
            return shapeReconciliationRow(Db.one("SELECT * FROM reconciliations WHERE id = ?", recId));
        });
        ctx.ok(approved);
    }

    private static void stock(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.read");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        List<Map<String, Object>> tanks = Domain.tankSummary(Db.str(station, "id"));
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> tank : tanks) {
            if (Money.d(tank.get("bookStockLitres")).signum() < 0) {
                warnings.add(Map.of("level", "error", "message", tank.get("code") + " book stock is negative", "tankId", String.valueOf(tank.get("id"))));
            }
            String productId = String.valueOf(tank.get("productId"));
            Map<String, Object> product = byProduct.computeIfAbsent(productId, ignored -> {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("productId", productId);
                card.put("name", tank.get("productName"));
                card.put("code", tank.get("productCode"));
                card.put("color", tank.get("productColor"));
                card.put("bookStockLitres", "0.000");
                return card;
            });
            product.put("bookStockLitres", Money.litres(Money.d(product.get("bookStockLitres")).add(Money.d(tank.get("bookStockLitres")))));
        }
        ctx.ok(Map.of("tanks", tanks, "products", new ArrayList<>(byProduct.values()), "warnings", warnings, "recentMovements", Json.shapeAll(Db.all(
                """
                SELECT m.*, t.code AS tank_code, t.name AS tank_name, p.name AS product_name
                  FROM stock_movements m JOIN tanks t ON t.id = m.tank_id JOIN fuel_products p ON p.id = m.product_id
                 WHERE m.station_id = ? ORDER BY m.occurred_at DESC LIMIT 8
                """, station.get("id"))), "stationId", station.get("id")));
    }

    private static void movements(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.read");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        long total = Db.count("SELECT COUNT(*) AS count FROM stock_movements WHERE station_id = ?", station.get("id"));
        List<Map<String, Object>> raw = Db.all(
                """
                SELECT m.*, t.code AS tank_code, t.name AS tank_name, p.name AS product_name
                  FROM stock_movements m
                  JOIN tanks t ON t.id = m.tank_id
                  JOIN fuel_products p ON p.id = m.product_id
                 WHERE m.station_id = ?
                 ORDER BY m.occurred_at DESC, m.created_at DESC LIMIT ? OFFSET ?
                """, station.get("id"), size, (page - 1) * size);
        List<Map<String, Object>> shaped = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            Map<String, Object> item = Json.shape(row);
            item.put("signedLitres", Money.litres(Domain.signedLitres(Db.str(row, "type"), row.get("quantity_litres"))));
            shaped.add(item);
        }
        ctx.ok(shaped, Domain.page(total, page, size));
    }

    private static void deliveries(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("deliveries.read");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        String where = "d.station_id = ? AND d.tenant_id = ?";
        List<Object> params = new ArrayList<>(List.of(station.get("id"), auth.tenantId()));
        if (ctx.q("status") != null && !ctx.q("status").isBlank()) {
            where += " AND d.status = ?";
            params.add(ctx.q("status").trim());
        }
        if (ctx.q("search") != null && !ctx.q("search").isBlank()) {
            String term = "%" + ctx.q("search").trim() + "%";
            where += " AND (d.invoice_number LIKE ? OR s.name LIKE ? OR COALESCE(d.vehicle_number, '') LIKE ?)";
            params.addAll(List.of(term, term, term));
        }
        long total = Db.count(
                "SELECT COUNT(*) AS count FROM deliveries d JOIN suppliers s ON s.id = d.supplier_id WHERE " + where,
                params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = Db.all(
                """
                SELECT d.*, s.name AS supplier_name, t.code AS tank_code, t.name AS tank_name,
                       p.name AS product_name, st.name AS station_name,
                       COALESCE((SELECT SUM(CAST(pay.amount AS DECIMAL(20,2)))
                                   FROM payments pay
                                  WHERE pay.supplier_id = d.supplier_id
                                    AND pay.station_id = d.station_id
                                    AND pay.tenant_id = d.tenant_id
                                    AND pay.direction = 'PAYOUT'
                                    AND pay.status != 'REVERSED'), 0) AS supplier_paid_total
                  FROM deliveries d
                  JOIN suppliers s ON s.id = d.supplier_id
                  JOIN tanks t ON t.id = d.tank_id
                  JOIN fuel_products p ON p.id = d.product_id
                  JOIN stations st ON st.id = d.station_id
                 WHERE %s
                 ORDER BY d.invoice_date DESC, d.created_at DESC
                 LIMIT ? OFFSET ?
                """.formatted(where),
                params.toArray());
        List<Map<String, Object>> shaped = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = Json.shape(r);
            item.put("supplierPaidTotal", Money.money(r.get("supplier_paid_total")));
            if (r.get("received_litres") != null && r.get("quantity_litres") != null) {
                item.put("discrepancyLitres", Money.litres(Money.d(r.get("received_litres")).subtract(Money.d(r.get("quantity_litres")))));
            }
            shaped.add(item);
        }
        ctx.ok(shaped, Domain.page(total, page, size));
    }

    private static void createDelivery(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("deliveries.write");
        String stationId = requiredBody(ctx, "stationId");
        String supplierId = requiredBody(ctx, "supplierId");
        String invoiceNumber = requiredBody(ctx, "invoiceNumber").trim();
        String invoiceDate = Domain.requireDate(ctx.str("invoiceDate"), "Invoice date");
        String tankId = requiredBody(ctx, "tankId");
        BigDecimal qty = parsePositiveLitres(requiredBody(ctx, "quantityLitres"), "quantityLitres");
        BigDecimal rate = parseNonNegativeDecimal(ctx.str("ratePerLitre") == null ? "0" : ctx.str("ratePerLitre"), "ratePerLitre");
        BigDecimal tax = parseNonNegativeDecimal(ctx.str("taxAmount") == null || ctx.str("taxAmount").isBlank() ? "0" : ctx.str("taxAmount"), "taxAmount");
        BigDecimal taxable = (ctx.str("taxableAmount") != null && !ctx.str("taxableAmount").isBlank())
                ? parseNonNegativeDecimal(ctx.str("taxableAmount"), "taxableAmount")
                : qty.multiply(rate);
        BigDecimal total = (ctx.str("totalAmount") != null && !ctx.str("totalAmount").isBlank())
                ? parseNonNegativeDecimal(ctx.str("totalAmount"), "totalAmount")
                : taxable.add(tax);
        boolean receiveNow = Boolean.TRUE.equals(ctx.body().get("receiveNow"))
                || "true".equalsIgnoreCase(ctx.str("receiveNow"))
                || "1".equals(ctx.str("receiveNow"));
        BigDecimal receivedQty = receiveNow
                ? (ctx.str("receivedLitres") != null && !ctx.str("receivedLitres").isBlank()
                        ? parsePositiveLitres(ctx.str("receivedLitres"), "receivedLitres")
                        : qty)
                : null;

        Map<String, Object> created = Db.tx(() -> {
            Map<String, Object> station = auth.station(stationId);
            Map<String, Object> supplier = Db.one("SELECT * FROM suppliers WHERE id = ? AND tenant_id = ?", supplierId, auth.tenantId());
            if (supplier == null) throw ApiException.notFound("Supplier not found");
            Map<String, Object> tank = loadAuthorizedTank(auth, tankId);
            if (!stationId.equals(Db.str(tank, "station_id"))) {
                throw ApiException.unprocessable("Selected tank does not belong to this station");
            }
            String tankProductId = Db.str(tank, "product_id");
            String reqProductId = ctx.str("productId");
            if (reqProductId != null && !reqProductId.isBlank() && !tankProductId.equals(reqProductId)) {
                throw ApiException.unprocessable("Selected fuel product does not match the tank's fuel product");
            }
            Domain.assertDayOpen(stationId, invoiceDate);
            if (receiveNow && !invoiceDate.equals(Clock.todayIst())) {
                Domain.assertDayOpen(stationId, Clock.todayIst());
            }
            if (Db.one("SELECT id FROM deliveries WHERE tenant_id = ? AND supplier_id = ? AND invoice_number = ?",
                    auth.tenantId(), supplierId, invoiceNumber) != null) {
                throw ApiException.conflict("A delivery invoice with this number already exists for this supplier");
            }
            Map<String, Object> prod = Db.one("SELECT category FROM fuel_products WHERE id = ?", tankProductId);
            String category = prod == null ? null : Db.str(prod, "category");
            String densityObs = blankToNull(ctx.str("densityObserved"));
            String tempObs = blankToNull(ctx.str("tempObservedC"));
            String density15 = blankToNull(ctx.str("densityAt15c"));
            if (density15 == null && densityObs != null) {
                density15 = computeDensity15cString(category, densityObs, tempObs == null ? "15" : tempObs);
            }
            String id = Clock.newId("dlv");
            String now = Clock.nowIso();
            String status = receiveNow ? "RECEIVED" : "DRAFT";
            String receivedLitresStr = receiveNow ? Money.litres(receivedQty) : null;
            String receivedAt = receiveNow ? now : null;
            Db.exec(
                    """
                    INSERT INTO deliveries
                      (id, tenant_id, station_id, supplier_id, invoice_number, invoice_date, product_id, tank_id,
                       quantity_litres, received_litres, rate_per_litre, taxable_amount, tax_amount, total_amount,
                       status, received_at, vehicle_number, driver_name, dip_before_cm, dip_after_cm,
                       density_invoice, density_observed, temp_observed_c, density_at_15c,
                       tt_seal_numbers, sample_seal_no, chamber_details, notes,
                       created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, auth.tenantId(), station.get("id"), supplierId, invoiceNumber, invoiceDate, tankProductId, tankId,
                    Money.litres(qty), receivedLitresStr, Money.rate(rate), Money.money(taxable), Money.money(tax), Money.money(total),
                    status, receivedAt, blankToNull(ctx.str("vehicleNumber")), blankToNull(ctx.str("driverName")),
                    blankToNull(ctx.str("dipBeforeCm")), blankToNull(ctx.str("dipAfterCm")),
                    blankToNull(ctx.str("densityInvoice")), densityObs, tempObs, density15,
                    blankToNull(ctx.str("ttSealNumbers")), blankToNull(ctx.str("sampleSealNo")),
                    blankToNull(ctx.str("chamberDetails")), blankToNull(ctx.str("notes")),
                    auth.userId, now, now);
            if (receiveNow) {
                Domain.movement(auth.tenantId(), stationId, tankId, tankProductId,
                        "DELIVERY", receivedLitresStr, "DELIVERY", id,
                        "Tanker receipt · Inv " + invoiceNumber, auth.userId, false, now);
                Auth.audit(auth, "DELIVERY_RECEIVED", "Delivery", id, stationId,
                        invoiceNumber + ": " + receivedLitresStr + " L received into " + tank.get("code"));
            } else {
                Auth.audit(auth, "DELIVERY_CREATED", "Delivery", id, stationId,
                        invoiceNumber + ": " + Money.litres(qty) + " L draft recorded");
            }
            return loadShapedDelivery(id);
        });
        ctx.created(created);
    }

    private static void deleteDelivery(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("deliveries.write");
        auth.requireOwner();
        Map<String, Object> existing = Db.one("SELECT * FROM deliveries WHERE id = ? AND tenant_id = ?", ctx.p("id"), auth.tenantId());
        if (existing == null) throw ApiException.notFound("Delivery not found");
        String stationId = Db.str(existing, "station_id");
        auth.station(stationId);
        if (!"DRAFT".equals(Db.str(existing, "status"))) {
            throw ApiException.conflict("Only draft deliveries can be deleted. Received deliveries already posted stock and payables.");
        }
        Db.tx(() -> {
            Db.exec("DELETE FROM deliveries WHERE id = ?", existing.get("id"));
            Auth.auditRequired(auth, "DELIVERY_DELETED", "Delivery", Db.str(existing, "id"), stationId, "Owner deleted draft delivery", existing, null);
            return null;
        });
        ctx.ok(Map.of("deleted", true));
    }

    private static void receive(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("deliveries.write");
        String deliveryId = ctx.p("id");
        Map<String, Object> received = Db.tx(() -> {
            Map<String, Object> delivery = Db.one("SELECT * FROM deliveries WHERE id = ? AND tenant_id = ?", deliveryId, auth.tenantId());
            if (delivery == null) throw ApiException.notFound("Delivery not found");
            String stationId = Db.str(delivery, "station_id");
            auth.station(stationId);
            String status = Db.str(delivery, "status");
            if ("RECEIVED".equals(status)) {
                throw ApiException.conflict("This delivery has already been received");
            }
            if (!"DRAFT".equals(status)) {
                throw ApiException.conflict("Only draft deliveries can be received (current status: " + status + ")");
            }
            String invoiceDate = Db.str(delivery, "invoice_date").substring(0, 10);
            Domain.assertDayOpen(stationId, invoiceDate);
            if (!invoiceDate.equals(Clock.todayIst())) {
                Domain.assertDayOpen(stationId, Clock.todayIst());
            }
            String rawRecv = ctx.str("receivedLitres");
            BigDecimal recvQty = (rawRecv != null && !rawRecv.isBlank())
                    ? parsePositiveLitres(rawRecv, "receivedLitres")
                    : parsePositiveLitres(Db.str(delivery, "quantity_litres"), "quantityLitres");
            String recvLitresStr = Money.litres(recvQty);
            Map<String, Object> prod = Db.one("SELECT category FROM fuel_products WHERE id = ?", delivery.get("product_id"));
            String category = prod == null ? null : Db.str(prod, "category");
            String densityObs = blankToNull(ctx.str("densityObserved"));
            String tempObs = blankToNull(ctx.str("tempObservedC"));
            String density15 = blankToNull(ctx.str("densityAt15c"));
            if (density15 == null && densityObs != null) {
                density15 = computeDensity15cString(category, densityObs, tempObs == null ? "15" : tempObs);
            }
            String now = Clock.nowIso();
            Db.exec(
                    """
                    UPDATE deliveries
                       SET status = 'RECEIVED',
                           received_litres = ?,
                           received_at = ?,
                           dip_before_cm = COALESCE(?, dip_before_cm),
                           dip_after_cm = COALESCE(?, dip_after_cm),
                           density_observed = COALESCE(?, density_observed),
                           temp_observed_c = COALESCE(?, temp_observed_c),
                           density_at_15c = COALESCE(?, density_at_15c),
                           tt_seal_numbers = COALESCE(?, tt_seal_numbers),
                           sample_seal_no = COALESCE(?, sample_seal_no),
                           chamber_details = COALESCE(?, chamber_details),
                           notes = COALESCE(?, notes),
                           updated_at = ?
                     WHERE id = ?
                    """,
                    recvLitresStr, now,
                    blankToNull(ctx.str("dipBeforeCm")), blankToNull(ctx.str("dipAfterCm")),
                    densityObs, tempObs, density15,
                    blankToNull(ctx.str("ttSealNumbers")), blankToNull(ctx.str("sampleSealNo")),
                    blankToNull(ctx.str("chamberDetails")), blankToNull(ctx.str("notes")),
                    now, deliveryId);
            Domain.movement(auth.tenantId(), stationId, Db.str(delivery, "tank_id"), Db.str(delivery, "product_id"),
                    "DELIVERY", recvLitresStr, "DELIVERY", deliveryId,
                    "Tanker receipt · Inv " + delivery.get("invoice_number"), auth.userId, false, now);
            Auth.audit(auth, "DELIVERY_RECEIVED", "Delivery", deliveryId, stationId,
                    delivery.get("invoice_number") + ": " + recvLitresStr + " L received");
            return loadShapedDelivery(deliveryId);
        });
        ctx.ok(received);
    }

    private static void dispute(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("deliveries.write");
        String deliveryId = ctx.p("id");
        String reason = ctx.str("reason");
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.validation("reason", "Dispute reason must be at least 5 characters");
        }
        Map<String, Object> disputed = Db.tx(() -> {
            Map<String, Object> delivery = Db.one("SELECT * FROM deliveries WHERE id = ? AND tenant_id = ?", deliveryId, auth.tenantId());
            if (delivery == null) throw ApiException.notFound("Delivery not found");
            String stationId = Db.str(delivery, "station_id");
            auth.station(stationId);
            String status = Db.str(delivery, "status");
            if ("RECEIVED".equals(status)) {
                throw ApiException.conflict("Received deliveries cannot be marked disputed");
            }
            if ("DISPUTED".equals(status)) {
                throw ApiException.conflict("Delivery is already marked disputed");
            }
            Domain.assertDayOpen(stationId, Db.str(delivery, "invoice_date").substring(0, 10));
            Db.exec("UPDATE deliveries SET status = 'DISPUTED', notes = ?, updated_at = ? WHERE id = ?",
                    reason.trim(), Clock.nowIso(), deliveryId);
            Auth.audit(auth, "DELIVERY_DISPUTED", "Delivery", deliveryId, stationId,
                    delivery.get("invoice_number") + " disputed: " + reason.trim());
            return loadShapedDelivery(deliveryId);
        });
        ctx.ok(disputed);
    }

    private static Map<String, Object> loadShapedDelivery(String id) {
        Map<String, Object> row = Db.one(
                """
                SELECT d.*, s.name AS supplier_name, t.code AS tank_code, t.name AS tank_name,
                       p.name AS product_name, st.name AS station_name,
                       COALESCE((SELECT SUM(CAST(pay.amount AS DECIMAL(20,2)))
                                   FROM payments pay
                                  WHERE pay.supplier_id = d.supplier_id
                                    AND pay.station_id = d.station_id
                                    AND pay.tenant_id = d.tenant_id
                                    AND pay.direction = 'PAYOUT'
                                    AND pay.status != 'REVERSED'), 0) AS supplier_paid_total
                  FROM deliveries d
                  JOIN suppliers s ON s.id = d.supplier_id
                  JOIN tanks t ON t.id = d.tank_id
                  JOIN fuel_products p ON p.id = d.product_id
                  JOIN stations st ON st.id = d.station_id
                 WHERE d.id = ?
                """, id);
        Map<String, Object> shaped = Json.shape(row);
        shaped.put("supplierPaidTotal", Money.money(row.get("supplier_paid_total")));
        if (row.get("received_litres") != null && row.get("quantity_litres") != null) {
            shaped.put("discrepancyLitres", Money.litres(Money.d(row.get("received_litres")).subtract(Money.d(row.get("quantity_litres")))));
        }
        return shaped;
    }

    private static String computeDensity15cString(String category, String observedRaw, String tempRaw) {
        return Domain.evaluateDensityAt15c(category, observedRaw, tempRaw).densityAt15c();
    }

    private static BigDecimal parseNonNegativeDecimal(String raw, String field) {
        final BigDecimal val;
        try {
            val = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.validation(field, field + " must be a valid number");
        }
        if (val.signum() < 0) {
            throw ApiException.validation(field, field + " cannot be negative");
        }
        return val;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void adjustment(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.write");
        String tankId = requiredBody(ctx, "tankId");
        String rawQty = requiredBody(ctx, "quantityLitres");
        final BigDecimal qty;
        try {
            qty = new BigDecimal(rawQty.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.validation("quantityLitres", "quantityLitres must be a valid signed number");
        }
        if (qty.signum() == 0) {
            throw ApiException.validation("quantityLitres", "Adjustment quantity cannot be zero");
        }
        String reason = ctx.str("reason") != null && !ctx.str("reason").isBlank() ? ctx.str("reason").trim() : ctx.str("note");
        if (reason == null || reason.trim().length() < 3) {
            throw ApiException.validation("reason", "Adjustment reason is required");
        }
        final String finalReason = reason.trim();
        Map<String, Object> out = Db.tx(() -> {
            Map<String, Object> tank = loadAuthorizedTank(auth, tankId);
            String stationId = Db.str(tank, "station_id");
            String businessDate = ctx.str("businessDate") == null || ctx.str("businessDate").isBlank()
                    ? Clock.todayIst()
                    : Domain.requireDate(ctx.str("businessDate"), "Business date");
            Domain.assertDayOpen(stationId, businessDate);
            String movId = Domain.movement(auth.tenantId(), stationId, tankId, Db.str(tank, "product_id"),
                    "ADJUSTMENT", Money.litres(qty), "ADJUSTMENT", null, finalReason, auth.userId, false, Clock.istDayStart(businessDate));
            Auth.audit(auth, "STOCK_ADJUSTED", "Tank", tankId, stationId, tank.get("code") + ": " + Money.litres(qty) + " L (" + finalReason + ")");
            return Web.map("id", movId, "bookStockLitres", Money.litres(Domain.bookStock(tankId)));
        });
        ctx.created(out);
    }

    private static void opening(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.write");
        String tankId = requiredBody(ctx, "tankId");
        String rawLitres = ctx.str("litres");
        if (rawLitres == null || rawLitres.isBlank()) rawLitres = ctx.str("quantityLitres");
        if (rawLitres == null || rawLitres.isBlank()) {
            throw ApiException.validation("litres", "Opening stock litres is required");
        }
        BigDecimal qty = parsePositiveLitres(rawLitres, "litres");
        Map<String, Object> result = Db.tx(() -> {
            Map<String, Object> tank = loadAuthorizedTank(auth, tankId);
            String stationId = Db.str(tank, "station_id");
            String businessDate = ctx.str("businessDate") == null || ctx.str("businessDate").isBlank()
                    ? Clock.todayIst()
                    : Domain.requireDate(ctx.str("businessDate"), "Business date");
            Domain.assertDayOpen(stationId, businessDate);
            BigDecimal capacity = Money.d(tank.get("capacity_litres"));
            if (capacity.signum() > 0 && qty.compareTo(capacity) > 0) {
                throw ApiException.unprocessable("Opening stock cannot exceed tank capacity (" + Money.litres(capacity) + " L)");
            }
            if (Db.count("SELECT COUNT(*) AS count FROM stock_movements WHERE tank_id = ?", tankId) > 0) {
                throw ApiException.conflict("Opening stock can only be recorded before any stock movement exists for this tank. Use an adjustment instead.");
            }
            String note = ctx.str("note") == null || ctx.str("note").isBlank() ? "Opening stock" : ctx.str("note").trim();
            String movId = Domain.movement(auth.tenantId(), stationId, tankId, Db.str(tank, "product_id"),
                    "OPENING", Money.litres(qty), "OPENING", null, note, auth.userId, false, Clock.istDayStart(businessDate));
            Auth.audit(auth, "OPENING_STOCK_SET", "Tank", tankId, stationId, tank.get("code") + ": " + Money.litres(qty) + " L");
            String book = Money.litres(Domain.bookStock(tankId));
            return Web.map("id", movId, "bookStockLitres", book, "closingBookStock", book);
        });
        ctx.created(result);
    }

    private static void transfer(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.write");
        String fromId = requiredBody(ctx, "fromTankId");
        String toId = requiredBody(ctx, "toTankId");
        if (fromId.equals(toId)) {
            throw ApiException.validation("toTankId", "Source and destination tanks must be different");
        }
        BigDecimal qty = parsePositiveLitres(requiredBody(ctx, "quantityLitres"), "quantityLitres");
        String reason = ctx.str("reason") != null && !ctx.str("reason").isBlank() ? ctx.str("reason").trim() : ctx.str("note");
        final String finalReason = (reason == null || reason.isBlank()) ? "Inter-tank transfer" : reason.trim();
        Map<String, Object> out = Db.tx(() -> {
            Map<String, Object> from = loadAuthorizedTank(auth, fromId);
            Map<String, Object> to = loadAuthorizedTank(auth, toId);
            String businessDate = ctx.str("businessDate") == null || ctx.str("businessDate").isBlank()
                    ? Clock.todayIst()
                    : Domain.requireDate(ctx.str("businessDate"), "Business date");
            Domain.assertDayOpen(Db.str(from, "station_id"), businessDate);
            if (!Db.str(from, "station_id").equals(Db.str(to, "station_id"))) {
                Domain.assertDayOpen(Db.str(to, "station_id"), businessDate);
            }
            if (!Db.str(from, "product_id").equals(Db.str(to, "product_id"))) {
                throw ApiException.unprocessable("Both tanks must hold the same fuel product");
            }
            BigDecimal fromStock = Domain.bookStock(fromId);
            if (fromStock.compareTo(qty) < 0) {
                throw ApiException.unprocessable("Insufficient book stock in source tank (" + Money.litres(fromStock) + " L available)");
            }
            BigDecimal toCap = Money.d(to.get("capacity_litres"));
            if (toCap.signum() > 0 && Domain.bookStock(toId).add(qty).compareTo(toCap) > 0) {
                throw ApiException.unprocessable("Transfer exceeds destination tank capacity");
            }
            String litres = Money.litres(qty);
            Domain.movement(auth.tenantId(), Db.str(from, "station_id"), fromId, Db.str(from, "product_id"),
                    "TRANSFER_OUT", litres, "TRANSFER", toId, finalReason, auth.userId, false, Clock.istDayStart(businessDate));
            Domain.movement(auth.tenantId(), Db.str(to, "station_id"), toId, Db.str(to, "product_id"),
                    "TRANSFER_IN", litres, "TRANSFER", fromId, finalReason, auth.userId, false, Clock.istDayStart(businessDate));
            Auth.audit(auth, "STOCK_TRANSFERRED", "Tank", fromId, Db.str(from, "station_id"),
                    from.get("code") + " -> " + to.get("code") + ": " + litres + " L (" + finalReason + ")");
            return Web.map("transferred", litres);
        });
        ctx.created(out);
    }

    private static void tankLedger(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.read");
        Map<String, Object> tank = loadAuthorizedTank(auth, ctx.p("id"));
        List<Map<String, Object>> ascRows = Db.all(
                """
                SELECT m.*, t.code AS tank_code, t.name AS tank_name, p.name AS product_name
                  FROM stock_movements m
                  JOIN tanks t ON t.id = m.tank_id
                  JOIN fuel_products p ON p.id = m.product_id
                 WHERE m.tank_id = ?
                 ORDER BY m.occurred_at ASC, m.created_at ASC, m.id ASC
                """, tank.get("id"));
        BigDecimal running = BigDecimal.ZERO;
        List<Map<String, Object>> chronological = new ArrayList<>(ascRows.size());
        for (Map<String, Object> row : ascRows) {
            BigDecimal signed = Domain.signedLitres(Db.str(row, "type"), row.get("quantity_litres"));
            running = running.add(signed);
            Map<String, Object> item = Json.shape(row);
            item.put("signedLitres", Money.litres(signed));
            item.put("runningLitres", Money.litres(running));
            chronological.add(item);
        }
        List<Map<String, Object>> ledgerDesc = new ArrayList<>();
        for (int i = chronological.size() - 1; i >= 0 && ledgerDesc.size() < 200; i--) {
            ledgerDesc.add(chronological.get(i));
        }
        String closing = Money.litres(running);
        ctx.ok(Web.map(
                "tank", Json.shape(tank),
                "closingBookStock", closing,
                "bookStockLitres", closing,
                "ledger", ledgerDesc,
                "movements", ledgerDesc));
    }

    private static Map<String, Object> loadAuthorizedTank(Auth auth, String tankId) {
        String tenantId = auth.tenantId();
        Map<String, Object> tank = Db.one(
                """
                SELECT t.*
                  FROM tanks t
                  JOIN stations s ON s.id = t.station_id
                 WHERE t.id = ? AND s.tenant_id = ?
                """, tankId, tenantId);
        if (tank == null) throw ApiException.notFound("Tank not found");
        auth.station(Db.str(tank, "station_id"));
        return tank;
    }

    private static BigDecimal parsePositiveLitres(String raw, String field) {
        final BigDecimal val;
        try {
            val = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.validation(field, field + " must be a valid number");
        }
        if (val.signum() <= 0) {
            throw ApiException.validation(field, field + " must be greater than zero");
        }
        return val;
    }

    private static String required(Web.Ctx ctx, String key) {
        String value = ctx.q(key);
        if (value == null || value.isBlank()) throw ApiException.unprocessable(key + " is required");
        return value;
    }

    private static String requiredBody(Web.Ctx ctx, String key) {
        String value = ctx.str(key);
        if (value == null || value.isBlank()) throw ApiException.validation(key, key + " is required");
        return value;
    }
}
