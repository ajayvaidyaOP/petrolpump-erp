package in.petrolflow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShiftApi {
    private ShiftApi() {}

    public static void register(Web web) {
        web.get("/shifts", ShiftApi::list);
        web.get("/shifts/alerts", ShiftApi::alerts);
        web.get("/shifts/day-closings", ShiftApi::dayClosings);
        web.post("/shifts/day-close", ShiftApi::dayClose);
        web.post("/shifts/day-close/reopen", ShiftApi::reopen);
        web.get("/shifts/:id", ShiftApi::detail);
        web.post("/shifts", ShiftApi::open);
        web.post("/shifts/:id/capture", ShiftApi::capture);
        web.post("/shifts/:id/close", ShiftApi::close);
        web.post("/shifts/:id/approve", ShiftApi::approve);
        web.post("/shifts/:id/reverse", ShiftApi::reverse);
        web.patch("/shifts/:id/nozzles/:nozzleId", ShiftApi::patchNozzle);
    }

    private static void list(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.read");
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        String where = "s.tenant_id = ?";
        List<Object> queryParams = new ArrayList<>();
        queryParams.add(auth.tenantId());
        if (ctx.q("stationId") != null) {
            auth.station(ctx.q("stationId"));
            where += " AND s.station_id = ?";
            queryParams.add(ctx.q("stationId"));
        } else if (!auth.allStations) {
            where += " AND s.station_id IN (" + placeholders(auth.stationIds.size()) + ")";
            queryParams.addAll(auth.stationIds);
        }
        if (ctx.q("status") != null) {
            where += " AND s.status = ?";
            queryParams.add(ctx.q("status"));
        }
        if (ctx.q("businessDate") != null) {
            where += " AND s.business_date = ?";
            queryParams.add(ctx.q("businessDate"));
        }
        String from = ctx.q("from");
        String to = ctx.q("to");
        if (from != null && !from.isBlank()) {
            if (!Clock.isIsoDate(from)) throw ApiException.validation("from", "from must be YYYY-MM-DD");
            where += " AND s.business_date >= ?";
            queryParams.add(from);
        }
        if (to != null && !to.isBlank()) {
            if (!Clock.isIsoDate(to)) throw ApiException.validation("to", "to must be YYYY-MM-DD");
            where += " AND s.business_date <= ?";
            queryParams.add(to);
        }
        if (from != null && to != null && !from.isBlank() && !to.isBlank() && from.compareTo(to) > 0) {
            throw ApiException.validation("to", "to must be on or after from");
        }
        long total = Db.count("SELECT COUNT(*) AS count FROM shifts s WHERE " + where, queryParams.toArray());
        queryParams.add(size);
        queryParams.add((page - 1) * size);
        ctx.ok(Json.shapeAll(Db.all(
                """
                SELECT s.*, st.name AS station_name, u.name AS operator_name
                  FROM shifts s JOIN stations st ON st.id = s.station_id
                  LEFT JOIN users u ON u.id = s.operator_id
                 WHERE %s ORDER BY s.business_date DESC, s.shift_number DESC LIMIT ? OFFSET ?
                """.formatted(where), queryParams.toArray())), Domain.page(total, page, size));
    }

    private static void alerts(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.read");
        if (ctx.q("stationId") != null) auth.station(ctx.q("stationId"));
        double hours = Domain.settingNumber("shifts.unclosedAlertHours", auth.tenantId(), ctx.q("stationId"), 14);
        List<Map<String, Object>> rows = ctx.q("stationId") == null
                ? Db.all("SELECT s.*, st.name AS station_name FROM shifts s JOIN stations st ON st.id = s.station_id WHERE s.tenant_id = ? AND s.status = 'OPEN'", auth.tenantId())
                : Db.all("SELECT s.*, st.name AS station_name FROM shifts s JOIN stations st ON st.id = s.station_id WHERE s.tenant_id = ? AND s.status = 'OPEN' AND s.station_id = ?", auth.tenantId(), ctx.q("stationId"));
        List<Map<String, Object>> alerts = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map<String, Object> row : rows) {
            if (!auth.allStations && !auth.stationIds.contains(Db.str(row, "station_id"))) continue;
            long opened = java.time.Instant.parse(Db.str(row, "opened_at")).toEpochMilli();
            if (now - opened <= hours * 3_600_000) continue;
            Map<String, Object> alert = Json.shape(row);
            alert.put("hoursOpen", String.format(java.util.Locale.ROOT, "%.1f", (now - opened) / 3_600_000d));
            alerts.add(alert);
        }
        ctx.ok(Map.of("thresholdHours", hours, "count", alerts.size(), "alerts", alerts));
    }

    private static void dayClosings(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.read");
        Map<String, Object> station = auth.station(ctx.q("stationId"));
        ctx.ok(Json.shapeAll(Db.all(
                """
                SELECT dc.*, uc.name AS closed_by_name, ur.name AS reopened_by_name
                  FROM day_closings dc
                  LEFT JOIN users uc ON uc.id = dc.closed_by
                  LEFT JOIN users ur ON ur.id = dc.reopened_by
                 WHERE dc.station_id = ? ORDER BY dc.business_date DESC LIMIT 93
                """, station.get("id"))));
    }

    private static void dayClose(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("dayclose.manage");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        String date = required(ctx, "businessDate");
        if (!Clock.isIsoDate(date)) throw ApiException.validation("businessDate", "Enter a valid YYYY-MM-DD date");
        if (date.compareTo(Clock.todayIst()) > 0) throw ApiException.unprocessable("A future business date cannot be closed");
        Map<String, Object> saved = Db.tx(() -> {
            Db.one("SELECT id FROM stations WHERE id = ? FOR UPDATE", station.get("id"));
            Map<String, Object> open = Db.one("SELECT id, shift_number, business_date FROM shifts WHERE station_id = ? AND status IN ('OPEN','FLAGGED') AND business_date <= ? LIMIT 1", station.get("id"), date);
            if (open != null) throw ApiException.conflict("Shift #" + open.get("shift_number") + " (" + open.get("business_date") + ") is still open/flagged — close and approve it before closing the day.");
            Map<String, Object> existing = Db.one("SELECT id, status FROM day_closings WHERE station_id = ? AND business_date = ? FOR UPDATE", station.get("id"), date);
            if (existing != null && "CLOSED".equals(existing.get("status"))) throw ApiException.conflict("Business date " + date + " is already closed");
            String id = existing == null ? Clock.newId("dcl") : Db.str(existing, "id");
            if (existing == null) {
                Db.exec("INSERT INTO day_closings (id, tenant_id, station_id, business_date, status, closed_by, closed_at, notes, created_at) VALUES (?, ?, ?, ?, 'CLOSED', ?, ?, ?, ?)",
                        id, station.get("tenant_id"), station.get("id"), date, auth.userId, Clock.nowIso(), ctx.str("notes"), Clock.nowIso());
            } else {
                Db.exec("UPDATE day_closings SET status = 'CLOSED', closed_by = ?, closed_at = ?, notes = COALESCE(?, notes) WHERE id = ?", auth.userId, Clock.nowIso(), ctx.str("notes"), id);
            }
            Auth.auditRequired(auth, "DAY_CLOSED", "DayClosing", id, Db.str(station, "id"), "Business date " + date + " closed", existing, Db.one("SELECT * FROM day_closings WHERE id = ?", id));
            return Db.one("SELECT * FROM day_closings WHERE id = ?", id);
        });
        ctx.created(Json.shape(saved));
    }

    private static void reopen(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("dayclose.manage");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        String date = required(ctx, "businessDate");
        String reason = required(ctx, "reason");
        if (reason.length() < 10) throw ApiException.validation("reason", "Reason must be at least 10 characters");
        Map<String, Object> reopened = Db.tx(() -> {
            Db.one("SELECT id FROM stations WHERE id = ? FOR UPDATE", station.get("id"));
            Map<String, Object> existing = Db.one("SELECT * FROM day_closings WHERE station_id = ? AND business_date = ? FOR UPDATE", station.get("id"), date);
            if (existing == null || !"CLOSED".equals(existing.get("status"))) throw ApiException.conflict("Business date " + date + " is not closed");
            Db.exec("UPDATE day_closings SET status = 'REOPENED', reopened_by = ?, reopened_at = ?, reopen_reason = ? WHERE id = ?", auth.userId, Clock.nowIso(), reason, existing.get("id"));
            Map<String, Object> after = Db.one("SELECT * FROM day_closings WHERE id = ?", existing.get("id"));
            Auth.auditRequired(auth, "DAY_REOPENED", "DayClosing", Db.str(existing, "id"), Db.str(station, "id"), "Business date " + date + " reopened: " + reason, existing, after);
            return after;
        });
        ctx.ok(Json.shape(reopened));
    }

    private static void detail(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.read");
        ctx.ok(detailOf(load(auth, ctx.p("id"))));
    }

    private static void open(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.open");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        String date = ctx.str("businessDate") == null ? Clock.todayIst() : ctx.str("businessDate");
        if (!Clock.isIsoDate(date)) throw ApiException.validation("businessDate", "Enter a valid YYYY-MM-DD date");
        String operatorId = ctx.str("operatorId") == null || ctx.str("operatorId").isBlank() ? auth.userId : ctx.str("operatorId").trim();
        Map<String, Object> operator = Db.one("SELECT id, status FROM users WHERE id = ? AND tenant_id = ?", operatorId, auth.tenantId());
        if (operator == null || !"ACTIVE".equals(Db.str(operator, "status"))) throw ApiException.validation("operatorId", "Operator must be an active user in this tenant");
        if (!auth.allStations && !auth.stationIds.contains(Db.str(station, "id"))) throw ApiException.forbidden();
        if (Db.one("SELECT 1 AS ok FROM user_station_access WHERE user_id = ? AND station_id = ?", operatorId, station.get("id")) == null
                && Db.one("SELECT 1 AS ok FROM users WHERE id = ? AND role_key = 'OWNER'", operatorId) == null) {
            throw ApiException.validation("operatorId", "Operator is not assigned to this station");
        }
        BigDecimal openingCash = FinanceRules.nonNegativeMoney(ctx.str("openingCash") == null ? "0" : ctx.str("openingCash"), "openingCash");
        List<Map<String, Object>> nozzles = Db.all("SELECT * FROM nozzles WHERE station_id = ? AND status = 'ACTIVE' ORDER BY number", station.get("id"));
        if (nozzles.isEmpty()) throw ApiException.unprocessable("Configure nozzles before opening a shift");
        for (Map<String, Object> nozzle : nozzles) {
            String mappedTankId = Db.str(nozzle, "tank_id");
            if (mappedTankId == null || mappedTankId.isBlank()) {
                throw ApiException.unprocessable("Active nozzle " + nozzle.get("label") + " is not mapped to an underground tank");
            }
            Map<String, Object> mappedTank = Db.one("SELECT id, station_id, product_id, status FROM tanks WHERE id = ?", mappedTankId);
            if (mappedTank == null
                    || !Db.str(station, "id").equals(Db.str(mappedTank, "station_id"))
                    || !Db.str(nozzle, "product_id").equals(Db.str(mappedTank, "product_id"))
                    || !"ACTIVE".equals(Db.str(mappedTank, "status"))) {
                throw ApiException.unprocessable("Active nozzle " + nozzle.get("label") + " must be mapped to an ACTIVE tank of the same fuel product");
            }
        }
        String id = Clock.newId("shf");
        String now = Clock.nowIso();
        Db.tx(() -> {
            Db.one("SELECT id FROM stations WHERE id = ? FOR UPDATE", station.get("id"));
            Domain.assertDayOpen(Db.str(station, "id"), date);
            Map<String, Object> open = Db.one("SELECT id, shift_number FROM shifts WHERE station_id = ? AND status IN ('OPEN','FLAGGED') LIMIT 1", station.get("id"));
            if (open != null) throw ApiException.conflict("Shift #" + open.get("shift_number") + " is still open. Close it before opening a new shift.");
            int number = (int) Db.count("SELECT COALESCE(MAX(shift_number), 0) + 1 AS count FROM shifts WHERE station_id = ? AND business_date = ?", station.get("id"), date);
            Db.exec(
                    """
                    INSERT INTO shifts
                      (id, tenant_id, station_id, shift_number, business_date, opened_at, operator_id, opened_by, status,
                       total_litres, total_value, opening_cash, closing_cash, notes, is_demo, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', '0', '0', ?, '0', ?, 0, ?, ?)
                    """,
                    id, station.get("tenant_id"), station.get("id"), number, date, now,
                    operatorId, auth.userId,
                    Money.money(openingCash), ctx.str("notes"), now, now);
            for (Map<String, Object> nozzle : nozzles) {
                Map<String, Object> latest = Db.one("SELECT id, reading_value FROM meter_readings WHERE nozzle_id = ? AND is_countable = 1 ORDER BY reading_at DESC LIMIT 1", nozzle.get("id"));
                Db.exec(
                        """
                        INSERT INTO shift_nozzle_readings
                          (id, shift_id, nozzle_id, opening_reading, opening_reading_id, opening_source, adjustment_litres, testing_litres, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, '0', '0', 'PENDING', ?, ?)
                        """,
                        Clock.newId("snr"), id, nozzle.get("id"),
                        latest == null ? null : latest.get("reading_value"),
                        latest == null ? null : latest.get("id"),
                        latest == null ? null : "DEVICE", now, now);
            }
            return id;
        });
        Map<String, Object> detail = detailOf(Db.one("SELECT * FROM shifts WHERE id = ?", id));
        Auth.audit(auth, "SHIFT_OPENED", "Shift", id, Db.str(station, "id"), "Shift #" + detail.get("shiftNumber") + " opened for " + date);
        detail.put("warnings", List.of());
        ctx.created(detail);
    }

    private static void capture(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.close");
        Map<String, Object> shift = load(auth, ctx.p("id"));
        ctx.ok(Map.of("shiftId", shift.get("id"), "preview", closings(shift, false), "note", "Preview only — no readings or stock movements were written."));
    }

    private static void close(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.close");
        Db.tx(() -> {
            Map<String, Object> shift = Db.one("SELECT * FROM shifts WHERE id = ? AND tenant_id = ? FOR UPDATE", ctx.p("id"), auth.tenantId());
            if (shift == null) throw ApiException.notFound("Shift not found");
            auth.station(Db.str(shift, "station_id"));
            if (!"OPEN".equals(shift.get("status"))) throw ApiException.conflict("This shift is not open");
            Domain.assertDayOpen(Db.str(shift, "station_id"), Db.str(shift, "business_date"));
            List<Map<String, Object>> ops = closings(shift, true);
            BigDecimal litres = BigDecimal.ZERO;
            BigDecimal value = BigDecimal.ZERO;
            boolean flagged = ops.isEmpty();
            for (Map<String, Object> op : ops) {
                if ("FLAGGED".equals(op.get("status"))) flagged = true;
                if (op.get("litres") != null) {
                    litres = litres.add(Money.d(op.get("litres")));
                    value = value.add(Money.d(op.get("value")));
                    if (op.get("tankId") != null) {
                        Domain.movement(Db.str(shift, "tenant_id"), Db.str(shift, "station_id"), Db.str(op, "tankId"), Db.str(op, "productId"),
                                "DISPENSED", Money.litres(op.get("litres")), "SHIFT", Db.str(shift, "id"),
                                "Dispensed during shift #" + shift.get("shift_number"), auth.userId, Db.num(shift, "is_demo") == 1, Db.str(shift, "opened_at"));
                    }
                }
                Db.exec(
                        """
                        UPDATE shift_nozzle_readings
                           SET closing_reading = ?, closing_reading_id = ?, closing_source = ?, litres_sold = ?, rate = ?,
                               sales_value = ?, variance_litres = ?, status = ?, note = ?, updated_at = ?
                         WHERE id = ?
                        """,
                        op.get("closing"), op.get("closingReadingId"), op.get("source"), op.get("litres"), op.get("rate"),
                        op.get("value"), op.get("variance"), op.get("status"), op.get("note"), Clock.nowIso(), op.get("rowId"));
            }
            String status = flagged ? "FLAGGED" : "CLOSED";
            BigDecimal closingCash = FinanceRules.nonNegativeMoney(ctx.str("closingCash") == null ? "0" : ctx.str("closingCash"), "closingCash");
            BigDecimal nonCashReceived = BigDecimal.ZERO;
            String now = Clock.nowIso();
            Map<String, String> recvByMode = new LinkedHashMap<>();
            if (ctx.body().get("settlements") instanceof List<?> stmts) {
                for (Object item : stmts) {
                    Map<String, Object> s = Json.object(item);
                    String m = Db.str(s, "mode") == null ? "" : Db.str(s, "mode").trim().toUpperCase(java.util.Locale.ROOT);
                    if (!List.of("CASH", "UPI", "CARD", "BANK", "CREDIT").contains(m)) {
                        throw ApiException.validation("settlements", "Settlement mode must be CASH, UPI, CARD, BANK or CREDIT");
                    }
                    if (recvByMode.containsKey(m)) throw ApiException.validation("settlements", "Duplicate settlement mode: " + m);
                    String r = s.get("received") != null ? String.valueOf(s.get("received")) : Db.str(s, "receivedAmount");
                    recvByMode.put(m, Money.money(FinanceRules.nonNegativeMoney(r == null ? "0" : r, "settlements." + m)));
                }
            }
            if (recvByMode.containsKey("CASH") && Money.d(recvByMode.get("CASH")).compareTo(closingCash) != 0) {
                throw ApiException.validation("closingCash", "closingCash must equal the CASH settlement received amount");
            }
            recvByMode.putIfAbsent("CASH", Money.money(closingCash));
            BigDecimal denominationTotal = BigDecimal.ZERO;
            boolean hasDenominations = false;
            if (ctx.body().get("denominations") instanceof List<?> denoms) {
                Db.exec("DELETE FROM shift_cash_denominations WHERE shift_id = ?", shift.get("id"));
                java.util.Set<Integer> seenDenoms = new java.util.HashSet<>();
                for (Object item : denoms) {
                    Map<String, Object> d = Json.object(item);
                    BigDecimal denomValue;
                    BigDecimal countValue;
                    try {
                        denomValue = new BigDecimal(String.valueOf(d.get("denomination")));
                        countValue = new BigDecimal(String.valueOf(d.get("count")));
                    } catch (NumberFormatException ex) {
                        throw ApiException.validation("denominations", "Denomination and count must be valid numbers");
                    }
                    if (denomValue.stripTrailingZeros().scale() > 0 || countValue.stripTrailingZeros().scale() > 0
                            || denomValue.signum() <= 0 || countValue.signum() < 0) {
                        throw ApiException.validation("denominations", "Denomination must be positive and count must be a non-negative whole number");
                    }
                    int denom = denomValue.intValueExact();
                    int count = countValue.intValueExact();
                    if (!List.of(1, 2, 5, 10, 20, 50, 100, 200, 500).contains(denom)) {
                        throw ApiException.validation("denominations", "Unsupported INR denomination: " + denom);
                    }
                    if (!seenDenoms.add(denom)) throw ApiException.validation("denominations", "Duplicate denomination: " + denom);
                    hasDenominations = true;
                    denominationTotal = denominationTotal.add(BigDecimal.valueOf((long) denom * count));
                    if (count > 0) {
                        Db.exec("INSERT INTO shift_cash_denominations (id, shift_id, denomination, count, amount) VALUES (?, ?, ?, ?, ?)",
                                Clock.newId("scd"), shift.get("id"), denom, count, Money.money(BigDecimal.valueOf((long) denom * count)));
                    }
                }
            }
            if (hasDenominations && denominationTotal.compareTo(closingCash) != 0) {
                throw ApiException.validation("denominations", "Denomination total " + Money.money(denominationTotal) + " must equal closing cash " + Money.money(closingCash));
            }
            for (String m : List.of("UPI", "CARD", "BANK", "CREDIT")) {
                if (recvByMode.containsKey(m)) nonCashReceived = nonCashReceived.add(Money.d(recvByMode.get(m)));
            }
            BigDecimal expectedCash = Money.d(shift.get("opening_cash")).add(value).subtract(nonCashReceived);
            BigDecimal shortExcess = closingCash.subtract(expectedCash);
            Db.exec("DELETE FROM shift_settlements WHERE shift_id = ?", shift.get("id"));
            for (String m : List.of("CASH", "UPI", "CARD", "BANK", "CREDIT")) {
                if (!recvByMode.containsKey(m) && !"CASH".equals(m)) continue;
                String exp = "CASH".equals(m) ? Money.money(expectedCash) : recvByMode.get(m);
                String rec = recvByMode.get(m);
                String diff = Money.money(Money.d(rec).subtract(Money.d(exp)));
                Db.exec(
                        """
                        INSERT INTO shift_settlements (id, shift_id, tenant_id, station_id, mode, expected_amount, received_amount, difference_amount, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        Clock.newId("stl"), shift.get("id"), shift.get("tenant_id"), shift.get("station_id"), m, exp, rec, diff, now);
            }
            Db.exec(
                    """
                    UPDATE shifts SET status = ?, closed_at = ?, closed_by = ?, total_litres = ?, total_value = ?,
                           closing_cash = ?, cash_counted = ?, short_excess = ?, close_notes = ?, updated_at = ? WHERE id = ?
                    """,
                    status, now, auth.userId, Money.litres(litres), Money.money(value),
                    Money.money(closingCash), Money.money(closingCash), Money.money(shortExcess), ctx.str("notes"), now, shift.get("id"));
            Auth.auditRequired(auth, "SHIFT_CLOSED", "Shift", Db.str(shift, "id"), Db.str(shift, "station_id"),
                    "Shift #" + shift.get("shift_number") + " closed: " + Money.litres(litres) + " L / " + Money.money(value), null, Db.one("SELECT * FROM shifts WHERE id = ?", shift.get("id")));
            return status;
        });
        Map<String, Object> out = detailOf(Db.one("SELECT * FROM shifts WHERE id = ?", ctx.p("id")));
        out.put("warnings", List.of());
        ctx.ok(out);
    }

    private static void approve(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.approve");
        Map<String, Object> shift = load(auth, ctx.p("id"));
        if (!"FLAGGED".equals(shift.get("status")) && !"CLOSED".equals(shift.get("status"))) throw ApiException.conflict("Only closed or flagged shifts can be approved");
        if (auth.userId.equals(Db.str(shift, "closed_by")) && !"OWNER".equals(auth.roleKey)) {
            throw ApiException.forbidden("A shift must be approved by someone other than the person who closed it");
        }
        Domain.assertDayOpen(Db.str(shift, "station_id"), Db.str(shift, "business_date").substring(0, 10));
        Db.exec("UPDATE shifts SET status = 'CLOSED', approved_by = ?, approved_at = ?, updated_at = ? WHERE id = ?", auth.userId, Clock.nowIso(), Clock.nowIso(), shift.get("id"));
        Db.exec("UPDATE shift_nozzle_readings SET status = 'APPROVED', updated_at = ? WHERE shift_id = ? AND status != 'REVERSED'", Clock.nowIso(), shift.get("id"));
        ctx.ok(detailOf(Db.one("SELECT * FROM shifts WHERE id = ?", shift.get("id"))));
    }

    private static void reverse(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.approve");
        String shiftId = ctx.p("id");
        String reason = required(ctx, "reason").trim();
        if (reason.length() < 10) throw ApiException.validation("reason", "Reason must be at least 10 characters");
        Map<String, Object> reversed = Db.tx(() -> {
            Map<String, Object> shift = Db.one("SELECT * FROM shifts WHERE id = ? AND tenant_id = ? FOR UPDATE", shiftId, auth.tenantId());
            if (shift == null) throw ApiException.notFound("Shift not found");
            auth.station(Db.str(shift, "station_id"));
            String currentStatus = Db.str(shift, "status");
            if ("REVERSED".equals(currentStatus)) {
                throw ApiException.conflict("This shift has already been reversed");
            }
            if (!"CLOSED".equals(currentStatus) && !"FLAGGED".equals(currentStatus)) {
                throw ApiException.conflict("Only closed or flagged shifts can be reversed");
            }
            Domain.assertDayOpen(Db.str(shift, "station_id"), Db.str(shift, "business_date"));
            for (Map<String, Object> movement : Db.all("SELECT * FROM stock_movements WHERE reference_type = 'SHIFT' AND reference_id = ? AND type = 'DISPENSED'", shift.get("id"))) {
                Domain.movement(Db.str(shift, "tenant_id"), Db.str(shift, "station_id"), Db.str(movement, "tank_id"), Db.str(movement, "product_id"),
                        "ADJUSTMENT", Money.litres(Money.d(movement.get("quantity_litres")).abs()), "SHIFT_REVERSAL", Db.str(shift, "id"),
                        "Reversal of shift #" + shift.get("shift_number") + ": " + reason, auth.userId, false);
            }
            String now = Clock.nowIso();
            Db.exec("UPDATE shifts SET status = 'REVERSED', close_notes = ?, updated_at = ? WHERE id = ?", "REVERSED: " + reason, now, shift.get("id"));
            Db.exec("UPDATE shift_nozzle_readings SET status = 'REVERSED', updated_at = ? WHERE shift_id = ?", now, shift.get("id"));
            Auth.auditRequired(auth, "SHIFT_REVERSED", "Shift", Db.str(shift, "id"), Db.str(shift, "station_id"), "Shift #" + shift.get("shift_number") + " reversed: " + reason, null, Db.one("SELECT * FROM shifts WHERE id = ?", shift.get("id")));
            return detailOf(Db.one("SELECT * FROM shifts WHERE id = ?", shift.get("id")));
        });
        ctx.ok(reversed);
    }

    private static void patchNozzle(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("shifts.close");
        Map<String, Object> shift = load(auth, ctx.p("id"));
        if (!"OPEN".equals(shift.get("status"))) throw ApiException.conflict("Only open shifts can be edited.");
        Domain.assertDayOpen(Db.str(shift, "station_id"), Db.str(shift, "business_date"));
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (ctx.body().containsKey("openingReading")) { sets.add("opening_reading = ?"); params.add(Money.rate(ctx.str("openingReading"))); sets.add("opening_source = 'MANUAL'"); }
        if (ctx.body().containsKey("closingReading")) { sets.add("closing_reading = ?"); params.add(Money.rate(ctx.str("closingReading"))); sets.add("closing_source = 'MANUAL'"); }
        if (ctx.body().containsKey("testingLitres")) {
            BigDecimal testing = new BigDecimal(ctx.str("testingLitres"));
            if (testing.signum() < 0) throw ApiException.validation("testingLitres", "testingLitres cannot be negative");
            sets.add("testing_litres = ?"); params.add(Money.litres(testing));
        }
        if (ctx.body().containsKey("note")) { sets.add("note = ?"); params.add(ctx.str("note")); }
        if (!sets.isEmpty()) {
            sets.add("updated_at = ?");
            params.add(Clock.nowIso());
            params.add(shift.get("id"));
            params.add(ctx.p("nozzleId"));
            Db.exec("UPDATE shift_nozzle_readings SET " + String.join(", ", sets) + " WHERE shift_id = ? AND nozzle_id = ?", params.toArray());
        }
        ctx.ok(detailOf(Db.one("SELECT * FROM shifts WHERE id = ?", shift.get("id"))));
    }

    private static List<Map<String, Object>> closings(Map<String, Object> shift, boolean commit) {
        List<Map<String, Object>> rows = Db.all(
                """
                SELECT snr.*, n.label, n.product_id, n.tank_id
                  FROM shift_nozzle_readings snr JOIN nozzles n ON n.id = snr.nozzle_id
                 WHERE snr.shift_id = ?
                """, shift.get("id"));
        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String closing = Db.str(row, "closing_reading");
            String source = Db.str(row, "closing_source");
            String closingId = Db.str(row, "closing_reading_id");
            if (closing == null) {
                Map<String, Object> device = Db.one(
                        "SELECT id, reading_value, reading_at FROM meter_readings WHERE nozzle_id = ? AND is_countable = 1 ORDER BY reading_at DESC LIMIT 1",
                        row.get("nozzle_id"));
                if (device != null && Db.str(device, "reading_at").compareTo(Db.str(shift, "opened_at")) >= 0) {
                    closing = Db.str(device, "reading_value");
                    closingId = Db.str(device, "id");
                    source = "DEVICE";
                }
            }
            String opening = Db.str(row, "opening_reading");
            Map<String, Object> price = Domain.priceAt(Db.str(shift, "tenant_id"), Db.str(row, "product_id"), Db.str(shift, "station_id"), Db.str(shift, "opened_at"));
            String rate = price == null ? "0.000" : Money.rate(price.get("rate_per_litre"));
            String status = "CAPTURED";
            String litres = null;
            String note = Db.str(row, "note");
            if (opening == null || closing == null) {
                status = "FLAGGED";
                note = note == null ? "Opening or closing reading is missing" : note;
            } else {
                BigDecimal net = Money.d(closing).subtract(Money.d(opening))
                        .add(Money.d(row.get("adjustment_litres")))
                        .subtract(Money.d(row.get("testing_litres")));
                if (net.signum() < 0) status = "FLAGGED";
                else litres = Money.litres(net);
            }
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("rowId", row.get("id"));
            op.put("nozzleId", row.get("nozzle_id"));
            op.put("label", row.get("label"));
            op.put("productId", row.get("product_id"));
            op.put("tankId", row.get("tank_id"));
            op.put("opening", opening);
            op.put("closing", closing);
            op.put("closingReadingId", closingId);
            op.put("source", source);
            op.put("litres", litres);
            op.put("rate", rate);
            op.put("value", litres == null ? "0.00" : Money.money(Money.d(litres).multiply(Money.d(rate))));
            op.put("variance", "0.000");
            op.put("status", status);
            op.put("note", note);
            ops.add(op);
            if (commit && "MANUAL".equals(source) && closingId == null && closing != null) {
                String readingId = Clock.newId("rdg");
                Db.exec(
                        """
                        INSERT INTO meter_readings
                          (id, tenant_id, station_id, nozzle_id, reading_value, unit, quality, source, reading_at, sequence, is_countable, is_simulated, note, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, 'L', 'VALID', 'MANUAL', ?, 0, 1, 0, 'Shift closing capture', ?, ?)
                        """,
                        readingId, shift.get("tenant_id"), shift.get("station_id"), row.get("nozzle_id"), closing, Clock.nowIso(), authUser(shift), Clock.nowIso());
                op.put("closingReadingId", readingId);
            }
        }
        return ops;
    }

    private static String authUser(Map<String, Object> shift) {
        return Db.str(shift, "opened_by");
    }

    private static Map<String, Object> load(Auth auth, String id) {
        Map<String, Object> shift = Db.one("SELECT * FROM shifts WHERE id = ?", id);
        if (shift == null || !auth.tenantId().equals(Db.str(shift, "tenant_id"))) throw ApiException.notFound("Shift not found");
        auth.station(Db.str(shift, "station_id"));
        return shift;
    }

    private static Map<String, Object> detailOf(Map<String, Object> shift) {
        List<Map<String, Object>> nozzles = Json.shapeAll(Db.all(
                """
                SELECT snr.*, n.label AS nozzle_label, n.number AS nozzle_number, d.code AS dispenser_code,
                       p.name AS product_name, p.color AS product_color
                  FROM shift_nozzle_readings snr
                  JOIN nozzles n ON n.id = snr.nozzle_id
                  JOIN dispensers d ON d.id = n.dispenser_id
                  JOIN fuel_products p ON p.id = n.product_id
                 WHERE snr.shift_id = ? ORDER BY d.code, n.number
                """, shift.get("id")));
        Map<String, Object> station = Db.one("SELECT name, code FROM stations WHERE id = ?", shift.get("station_id"));
        Map<String, Object> detail = Json.shape(shift);
        detail.put("stationName", station == null ? null : station.get("name"));
        detail.put("stationCode", station == null ? null : station.get("code"));
        detail.put("operatorName", nameOf(Db.str(shift, "operator_id")));
        detail.put("openedByName", nameOf(Db.str(shift, "opened_by")));
        detail.put("closedByName", nameOf(Db.str(shift, "closed_by")));
        detail.put("approvedByName", nameOf(Db.str(shift, "approved_by")));
        detail.put("nozzles", nozzles);
        detail.put("nozzleReadings", nozzles);
        detail.put("settlements", Json.shapeAll(Db.all("SELECT * FROM shift_settlements WHERE shift_id = ? ORDER BY mode", shift.get("id"))));
        detail.put("denominations", Json.shapeAll(Db.all("SELECT * FROM shift_cash_denominations WHERE shift_id = ? ORDER BY denomination DESC", shift.get("id"))));
        BigDecimal testing = BigDecimal.ZERO;
        int flagged = 0;
        for (Map<String, Object> nozzle : nozzles) {
            testing = testing.add(Money.d(nozzle.get("testingLitres")));
            if ("FLAGGED".equals(nozzle.get("status"))) flagged++;
        }
        detail.put("totals", Map.of(
                "litres", String.valueOf(shift.get("total_litres")),
                "value", String.valueOf(shift.get("total_value")),
                "testingLitres", Money.litres(testing),
                "nozzleCount", nozzles.size(),
                "flaggedNozzles", flagged));
        return detail;
    }

    private static String nameOf(String userId) {
        if (userId == null) return null;
        Map<String, Object> row = Db.one("SELECT name FROM users WHERE id = ?", userId);
        return row == null ? null : Db.str(row, "name");
    }

    private static String placeholders(int count) {
        if (count == 0) return "''";
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String required(Web.Ctx ctx, String key) {
        String value = ctx.str(key);
        if (value == null || value.isBlank()) throw ApiException.validation(key, key + " is required");
        return value;
    }
}
