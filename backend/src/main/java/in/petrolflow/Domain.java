package in.petrolflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {}

    public record DensityEvaluation(
            String observedDensity,
            String observedTempC,
            String densityAt15c,
            String specMin,
            String specMax,
            int withinSpec,
            String specificationUsed) {}

    /**
     * Legacy thermal-correction estimate for a glass-hydrometer reading. The coefficient set is
     * retained for backward compatibility, but this build has NOT been certified against ASTM/IS
     * tables and must not be represented as statutory compliance evidence. The min/max bands are
     * configurable screening bands only.
     */
    private static final BigDecimal TABLE_53B_MIN_DENSITY = new BigDecimal("653.0");
    private static final BigDecimal TABLE_53B_MAX_DENSITY = new BigDecimal("1075.0");

    public static DensityEvaluation evaluateDensityAt15c(String fuelCategory, String observedRaw, String tempRaw) {
        final BigDecimal obsDec;
        try {
            obsDec = new BigDecimal(observedRaw.trim());
        } catch (RuntimeException ex) {
            throw ApiException.validation("observedDensity", "Observed density must be a valid number in kg/m³");
        }
        double obs = obsDec.doubleValue();
        if (!Double.isFinite(obs)
                || obsDec.compareTo(TABLE_53B_MIN_DENSITY) < 0
                || obsDec.compareTo(TABLE_53B_MAX_DENSITY) > 0) {
            throw ApiException.validation(
                    "observedDensity",
                    "Observed density must be within the supported estimate domain 653.0–1075.0 kg/m³ (e.g. 742.5, not 0.742)");
        }
        final BigDecimal tempDec;
        try {
            tempDec = new BigDecimal(tempRaw.trim());
        } catch (RuntimeException ex) {
            throw ApiException.validation("observedTempC", "Observed temperature (°C) must be numeric");
        }
        double temp = tempDec.doubleValue();
        if (!Double.isFinite(temp) || temp < -20.0 || temp > 65.0) {
            throw ApiException.validation("observedTempC", "Observed temperature must be between -20°C and 65°C");
        }

        double dt = temp - 15.0;
        double hyd = 1.0 - 0.000023 * dt - 0.00000002 * dt * dt;
        double rhoT = obs * hyd;
        double rho15 = rhoT;
        for (int i = 0; i < 20; i++) {
            double alpha;
            if (rho15 < 770.0) {
                alpha = (346.4228 + 0.4388 * rho15) / (rho15 * rho15);
            } else if (rho15 < 787.5) {
                alpha = -0.00336312 + 2680.3206 / (rho15 * rho15);
            } else if (rho15 < 839.0) {
                alpha = 594.5418 / (rho15 * rho15);
            } else {
                alpha = (186.9696 + 0.4862 * rho15) / (rho15 * rho15);
            }
            double vcf = Math.exp(-alpha * dt * (1.0 + 0.8 * alpha * dt));
            double next = rhoT / vcf;
            if (Math.abs(next - rho15) < 1e-7) {
                rho15 = next;
                break;
            }
            rho15 = next;
        }
        if (!Double.isFinite(rho15)) {
            throw ApiException.validation("observedDensity", "Unable to converge the legacy density-at-15°C estimate");
        }
        BigDecimal d15 = BigDecimal.valueOf(rho15).setScale(1, java.math.RoundingMode.HALF_UP);
        if (d15.compareTo(TABLE_53B_MIN_DENSITY) < 0 || d15.compareTo(TABLE_53B_MAX_DENSITY) > 0) {
            throw ApiException.validation(
                    "observedDensity",
                    "Corrected density at 15°C (" + Money.fixed(d15, 1)
                            + " kg/m³) falls outside the supported estimate domain 653.0–1075.0 kg/m³; extrapolation is not permitted");
        }
        String cat = fuelCategory == null ? "" : fuelCategory.trim().toUpperCase();
        String specMin;
        String specMax;
        if ("DIESEL".equals(cat)) {
            specMin = "810";
            specMax = "845";
        } else if ("PETROL".equals(cat)) {
            specMin = "720";
            specMax = "775";
        } else {
            specMin = null;
            specMax = null;
        }
        String specLabel = densitySpecificationLabel(cat);
        int within = 0;
        if (specMin != null && specMax != null) {
            within = (d15.compareTo(new BigDecimal(specMin)) >= 0 && d15.compareTo(new BigDecimal(specMax)) <= 0) ? 1 : 0;
        }
        return new DensityEvaluation(
                Money.fixed(obsDec, 1),
                Money.fixed(tempDec, 1),
                Money.fixed(d15, 1),
                specMin,
                specMax,
                within,
                specLabel);
    }

    public static String densitySpecificationLabel(String fuelCategory) {
        String cat = fuelCategory == null ? "" : fuelCategory.trim().toUpperCase();
        if ("DIESEL".equals(cat)) {
            return "Uncertified legacy thermal estimate @ 15°C · Diesel screening band (810–845 kg/m³), not statutory compliance";
        }
        if ("PETROL".equals(cat)) {
            return "Uncertified legacy thermal estimate @ 15°C · Petrol screening band (720–775 kg/m³), not statutory compliance";
        }
        return "Uncertified legacy thermal estimate @ 15°C · Unsupported fuel category (no screening band)";
    }

    public static BigDecimal applyCreditEntryDelta(BigDecimal acc, Map<String, Object> entry) {
        String type = Db.str(entry, "type");
        BigDecimal rawAmt = Money.d(entry.get("amount"));
        BigDecimal absAmt = rawAmt.abs();
        if ("CREDIT_SALE".equals(type)) return acc.add(absAmt);
        if ("PAYMENT".equals(type)) return acc.subtract(absAmt);
        if ("PAYMENT_REVERSAL".equals(type)) return acc.add(absAmt);
        return acc.add(rawAmt);
    }

    public static Map<String, BigDecimal> stationScopedCustomerBalances(
            String tenantId,
            Collection<String> customerIds,
            Collection<String> stationIds) {
        if (tenantId == null || customerIds == null || customerIds.isEmpty() || stationIds == null || stationIds.isEmpty()) {
            return Map.of();
        }
        List<String> custList = new ArrayList<>(customerIds);
        List<String> stnList = new ArrayList<>(stationIds);
        String custIn = String.join(",", Collections.nCopies(custList.size(), "?"));
        String stnIn = String.join(",", Collections.nCopies(stnList.size(), "?"));
        List<Object> params = new ArrayList<>(1 + custList.size() + stnList.size());
        params.add(tenantId);
        params.addAll(custList);
        params.addAll(stnList);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> row : Db.all(
                "SELECT customer_id, type, amount FROM credit_entries "
                        + "WHERE tenant_id = ? AND status = 'POSTED' "
                        + "AND customer_id IN (" + custIn + ") "
                        + "AND station_id IN (" + stnIn + ")",
                params.toArray())) {
            String cid = Db.str(row, "customer_id");
            if (cid != null) {
                out.put(cid, applyCreditEntryDelta(out.getOrDefault(cid, BigDecimal.ZERO), row));
            }
        }
        return out;
    }

    public static String stationScopedCustomerBalance(String tenantId, String customerId, Collection<String> stationIds) {
        if (customerId == null || stationIds == null || stationIds.isEmpty()) {
            return "0.00";
        }
        Map<String, BigDecimal> map = stationScopedCustomerBalances(tenantId, List.of(customerId), stationIds);
        return Money.money(map.getOrDefault(customerId, BigDecimal.ZERO));
    }

    public static BigDecimal signedLitres(String type, Object quantity) {
        BigDecimal raw = Money.d(quantity);
        if ("ADJUSTMENT".equals(type)) return raw;
        BigDecimal amount = raw.abs();
        if ("DISPENSED".equals(type) || "TRANSFER_OUT".equals(type)) return amount.negate();
        if (!java.util.Set.of("OPENING", "RECEIVED", "DELIVERY", "TRANSFER_IN").contains(type)) {
            throw ApiException.validation("type", "Unsupported stock movement type: " + type);
        }
        return amount;
    }

    public static BigDecimal bookStock(String tankId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : Db.all("SELECT type, quantity_litres FROM stock_movements WHERE tank_id = ?", tankId)) {
            total = total.add(signedLitres(Db.str(row, "type"), row.get("quantity_litres")));
        }
        return total;
    }

    public static BigDecimal convertDipToLitres(String tankId, String dipCm) {
        List<Map<String, Object>> points = Db.all(
                "SELECT dip_cm, litres FROM tank_calibration_points WHERE tank_id = ? ORDER BY CAST(dip_cm AS DECIMAL(18,4)) ASC",
                tankId);
        if (points.size() < 2) {
            throw ApiException.unprocessable("No calibration table is configured for this tank — record measured litres instead");
        }
        BigDecimal dip = Money.d(dipCm);
        BigDecimal firstDip = Money.d(points.get(0).get("dip_cm"));
        BigDecimal lastDip = Money.d(points.get(points.size() - 1).get("dip_cm"));
        if (dip.compareTo(firstDip) < 0 || dip.compareTo(lastDip) > 0) {
            throw ApiException.unprocessable("Dip reading falls outside the calibrated range for this tank");
        }
        for (int i = 1; i < points.size(); i++) {
            Map<String, Object> low = points.get(i - 1);
            Map<String, Object> high = points.get(i);
            BigDecimal lowDip = Money.d(low.get("dip_cm"));
            BigDecimal highDip = Money.d(high.get("dip_cm"));
            if (dip.compareTo(lowDip) >= 0 && dip.compareTo(highDip) <= 0) {
                BigDecimal span = highDip.subtract(lowDip);
                if (span.signum() == 0) return Money.d(low.get("litres"));
                BigDecimal ratio = dip.subtract(lowDip).divide(span, 8, java.math.RoundingMode.HALF_UP);
                BigDecimal lowLitres = Money.d(low.get("litres"));
                BigDecimal highLitres = Money.d(high.get("litres"));
                return lowLitres.add(highLitres.subtract(lowLitres).multiply(ratio));
            }
        }
        return Money.d(points.get(points.size() - 1).get("litres"));
    }

    public static List<Map<String, Object>> tankSummary(String stationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> tank : Db.all(
                """
                SELECT t.*, p.name AS product_name, p.code AS product_code, p.color AS product_color
                  FROM tanks t JOIN fuel_products p ON p.id = t.product_id
                 WHERE t.station_id = ? ORDER BY t.code
                """, stationId)) {
            BigDecimal book = bookStock(Db.str(tank, "id"));
            Map<String, Object> last = Db.one(
                    """
                    SELECT measured_litres, variance_litres, measured_at, method, water_dip_cm
                      FROM tank_measurements WHERE tank_id = ? ORDER BY measured_at DESC LIMIT 1
                    """, Db.str(tank, "id"));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", tank.get("id"));
            card.put("stationId", tank.get("station_id"));
            card.put("code", tank.get("code"));
            card.put("name", tank.get("name"));
            card.put("productId", tank.get("product_id"));
            card.put("productName", tank.get("product_name"));
            card.put("productCode", tank.get("product_code"));
            card.put("productColor", tank.get("product_color"));
            card.put("capacityLitres", String.valueOf(tank.get("capacity_litres")));
            card.put("deadStockLitres", String.valueOf(tank.get("dead_stock_litres")));
            card.put("calibrationReference", tank.get("calibration_reference"));
            card.put("status", tank.get("status"));
            card.put("bookStockLitres", Money.litres(book));
            card.put("fillPct", Money.percent(book, tank.get("capacity_litres"), 1));
            card.put("lastMeasuredLitres", last == null ? null : last.get("measured_litres"));
            card.put("lastVarianceLitres", last == null ? null : last.get("variance_litres"));
            card.put("lastMeasuredAt", last == null ? null : last.get("measured_at"));
            card.put("lastMeasurementMethod", last == null ? null : last.get("method"));
            card.put("lastWaterDipCm", last == null ? null : last.get("water_dip_cm"));
            out.add(card);
        }
        return out;
    }

    public static String movement(String tenantId, String stationId, String tankId, String productId, String type, String litres, String refType, String refId, String note, String userId, boolean demo) {
        return movement(tenantId, stationId, tankId, productId, type, litres, refType, refId, note, userId, demo, Clock.nowIso());
    }

    /** Must run inside the caller's transaction. Locks the tank and rejects negative book stock. */
    public static String movement(String tenantId, String stationId, String tankId, String productId, String type, String litres, String refType, String refId, String note, String userId, boolean demo, String occurredAt) {
        Map<String, Object> tank = Db.one("SELECT id, tenant_id, station_id, product_id FROM tanks WHERE id = ? FOR UPDATE", tankId);
        if (tank == null || !tenantId.equals(Db.str(tank, "tenant_id")) || !stationId.equals(Db.str(tank, "station_id")) || !productId.equals(Db.str(tank, "product_id"))) {
            throw ApiException.unprocessable("Stock movement tank/product does not belong to this station");
        }
        BigDecimal qty = Money.d(litres);
        if (!Double.isFinite(qty.doubleValue())) throw ApiException.validation("litres", "Stock litres must be finite");
        if (!"ADJUSTMENT".equals(type) && qty.signum() < 0) throw ApiException.validation("litres", "Stock litres cannot be negative for " + type);
        BigDecimal delta = signedLitres(type, qty);
        BigDecimal projected = bookStock(tankId).add(delta);
        if (projected.signum() < 0) {
            throw ApiException.conflict("Tank book stock would become negative (" + Money.litres(projected) + " L). Record a delivery/opening stock or investigate before dispensing.");
        }
        String id = Clock.newId("mov");
        String now = Clock.nowIso();
        Db.exec(
                """
                INSERT INTO stock_movements
                  (id, tenant_id, station_id, tank_id, product_id, type, quantity_litres, reference_type, reference_id,
                   occurred_at, note, created_by, is_demo, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, stationId, tankId, productId, type, Money.litres(qty), refType, refId,
                occurredAt == null || occurredAt.isBlank() ? now : occurredAt, note, userId, demo ? 1 : 0, now);
        return id;
    }

    public static Map<String, Object> priceAt(String tenantId, String productId, String stationId) {
        return priceAt(tenantId, productId, stationId, Clock.nowIso());
    }

    public static Map<String, Object> priceAt(String tenantId, String productId, String stationId, String atIso) {
        String now = atIso == null || atIso.isBlank() ? Clock.nowIso() : atIso;
        Map<String, Object> row = stationId == null ? null : Db.one(
                """
                SELECT * FROM fuel_prices
                 WHERE tenant_id = ? AND product_id = ? AND station_id = ?
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY effective_from DESC LIMIT 1
                """, tenantId, productId, stationId, now, now);
        if (row == null) {
            row = Db.one(
                    """
                    SELECT * FROM fuel_prices
                     WHERE tenant_id = ? AND product_id = ? AND station_id IS NULL
                       AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                     ORDER BY effective_from DESC LIMIT 1
                    """, tenantId, productId, now, now);
        }
        return row;
    }

    public static String setting(String key, String tenantId, String stationId, String fallback) {
        Map<String, Object> row = Db.one(
                """
                SELECT value FROM app_settings
                 WHERE `key` = ? AND tenant_id IN (?, '') AND station_id IN (?, '')
                 ORDER BY (tenant_id != '') DESC, (station_id != '') DESC LIMIT 1
                """, key, tenantId == null ? "" : tenantId, stationId == null ? "" : stationId);
        if (row == null || row.get("value") == null || String.valueOf(row.get("value")).isBlank()) return fallback;
        return String.valueOf(row.get("value"));
    }

    public static double settingNumber(String key, String tenantId, String stationId, double fallback) {
        try {
            return Double.parseDouble(setting(key, tenantId, stationId, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static boolean dayClosed(String stationId, String date) {
        Map<String, Object> row = Db.one("SELECT status FROM day_closings WHERE station_id = ? AND business_date = ?", stationId, date);
        return row != null && "CLOSED".equals(row.get("status"));
    }

    public static void assertDayOpen(String stationId, String date) {
        if (dayClosed(stationId, date)) {
            throw ApiException.conflict("Business date " + date + " is closed (din band ho chuka hai). Reopen it from Shifts → Day closing before editing entries for that date.");
        }
    }

    public static Map<String, Object> page(long total, int page, int pageSize) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page);
        meta.put("pageSize", pageSize);
        meta.put("total", total);
        meta.put("totalPages", Math.max(1, (int) Math.ceil(total / (double) pageSize)));
        return meta;
    }

    public static int pageOf(Web.Ctx ctx) {
        try {
            int page = Integer.parseInt(ctx.q("page") == null ? "1" : ctx.q("page"));
            return Math.max(page, 1);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public static int pageSize(Web.Ctx ctx) {
        try {
            int size = Integer.parseInt(ctx.q("pageSize") == null ? "25" : ctx.q("pageSize"));
            return size <= 0 ? 25 : Math.min(size, 100);
        } catch (NumberFormatException ignored) {
            return 25;
        }
    }

    public static String requireDate(String value, String label) {
        if (value == null || value.isBlank()) return Clock.todayIst();
        if (!Clock.isIsoDate(value)) throw ApiException.badRequest(label + " must be in YYYY-MM-DD format");
        return value;
    }

    public static long ageHours(String iso) {
        if (iso == null) return 0;
        return (System.currentTimeMillis() - Instant.parse(iso).toEpochMilli()) / 3_600_000L;
    }
}
