package in.petrolflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CatalogApi {
    private CatalogApi() {}

    public static void register(Web web) {
        web.get("/catalog/stations", CatalogApi::stations);
        web.post("/catalog/stations", CatalogApi::createStation);
        web.patch("/catalog/stations/:id", CatalogApi::patchStation);
        web.delete("/catalog/stations/:id", ctx -> crudDelete(ctx, "stations", "stations.write", true, false,
                new String[][]{{"shifts","station_id"},{"tanks","station_id"},{"dispensers","station_id"},{"expenses","station_id"},{"payments","station_id"},{"deliveries","station_id"}},
                new String[]{}));
        web.get("/catalog/products", ctx -> crudList(ctx, "fuel_products", "products.read", true, false, "code", "name"));
        web.post("/catalog/products", ctx -> crudCreate(ctx, "fuel_products", "prd", "products.write", true, false, "code", "name", "category", "unit", "color", "isActive"));
        web.patch("/catalog/products/:id", ctx -> crudPatch(ctx, "fuel_products", "products.write", true, "name", "category", "unit", "color", "isActive"));
        web.delete("/catalog/products/:id", ctx -> crudDelete(ctx, "fuel_products", "products.write", true, false,
                new String[][]{{"fuel_prices","product_id"},{"tanks","product_id"},{"nozzles","product_id"},{"deliveries","product_id"},{"fuel_transactions","product_id"},{"stock_movements","product_id"},{"density_checks","product_id"}},
                new String[]{}));
        web.get("/catalog/prices", CatalogApi::prices);
        web.get("/catalog/prices/current", CatalogApi::currentPrices);
        web.post("/catalog/prices", CatalogApi::createPrice);
        web.get("/catalog/tanks", ctx -> crudList(ctx, "tanks", "tanks.read", false, true, "code", "name"));
        web.post("/catalog/tanks", CatalogApi::createTank);
        web.patch("/catalog/tanks/:id", CatalogApi::patchTank);
        web.delete("/catalog/tanks/:id", ctx -> crudDelete(ctx, "tanks", "tanks.write", false, true,
                new String[][]{{"stock_movements","tank_id"},{"tank_measurements","tank_id"},{"deliveries","tank_id"},{"nozzles","tank_id"},{"density_checks","tank_id"}},
                new String[]{"tank_calibration_points"}));
        web.get("/catalog/tanks/:id/calibration", CatalogApi::getCalibration);
        web.put("/catalog/tanks/:id/calibration", CatalogApi::putCalibration);
        web.get("/catalog/tanks/:id/measurements", CatalogApi::listMeasurements);
        web.post("/catalog/tanks/:id/measurements", CatalogApi::measurement);
        web.get("/catalog/dispensers", ctx -> crudList(ctx, "dispensers", "dispensers.read", false, true, "code", "name"));
        web.post("/catalog/dispensers", ctx -> crudCreate(ctx, "dispensers", "dsp", "dispensers.write", false, true, "code", "name", "serialNumber", "model", "protocol", "status"));
        web.patch("/catalog/dispensers/:id", ctx -> crudPatch(ctx, "dispensers", "dispensers.write", false, "name", "model", "serialNumber", "protocol", "status"));
        web.delete("/catalog/dispensers/:id", ctx -> crudDelete(ctx, "dispensers", "dispensers.write", false, true,
                new String[][]{{"nozzles","dispenser_id"}}, new String[]{}));
        web.get("/catalog/nozzles", ctx -> crudList(ctx, "nozzles", "dispensers.read", false, true, "label", "number"));
        web.post("/catalog/nozzles", CatalogApi::createNozzle);
        web.patch("/catalog/nozzles/:id", CatalogApi::patchNozzle);
        web.delete("/catalog/nozzles/:id", ctx -> crudDelete(ctx, "nozzles", "dispensers.write", false, true,
                new String[][]{{"shift_nozzle_readings","nozzle_id"},{"meter_readings","nozzle_id"},{"fuel_transactions","nozzle_id"}}, new String[]{}));
        web.get("/catalog/nozzles/:id/history", CatalogApi::nozzleHistory);
        web.get("/catalog/customers", ctx -> customers(ctx, "code", "name", "phone"));
        web.post("/catalog/customers", ctx -> crudCreate(ctx, "customers", "cus", "customers.write", true, false, "code", "name", "phone", "email", "gstin", "address", "creditLimit", "creditDays", "status"));
        web.patch("/catalog/customers/:id", ctx -> crudPatch(ctx, "customers", "customers.write", true, "name", "phone", "email", "gstin", "address", "creditLimit", "creditDays", "status"));
        web.delete("/catalog/customers/:id", ctx -> crudDelete(ctx, "customers", "customers.write", true, false,
                new String[][]{{"credit_entries","customer_id"},{"payments","customer_id"},{"fuel_transactions","customer_id"},{"lube_movements","customer_id"}},
                new String[]{"customer_vehicles","customer_followups"}));
        web.get("/catalog/customers/:id/vehicles", CatalogApi::vehicles);
        web.post("/catalog/customers/:id/vehicles", CatalogApi::addVehicle);
        web.delete("/catalog/customers/:id/vehicles/:vehicleId", CatalogApi::deleteVehicle);
        web.get("/catalog/suppliers", ctx -> suppliers(ctx, "code", "name", "phone", "gstin"));
        web.post("/catalog/suppliers", ctx -> crudCreate(ctx, "suppliers", "sup", "suppliers.write", true, false, "code", "name", "contactPerson", "phone", "email", "gstin", "address", "creditDays", "openingBalance", "isActive"));
        web.patch("/catalog/suppliers/:id", ctx -> crudPatch(ctx, "suppliers", "suppliers.write", true, "name", "contactPerson", "phone", "email", "gstin", "address", "creditDays", "isActive"));
        web.delete("/catalog/suppliers/:id", ctx -> crudDelete(ctx, "suppliers", "suppliers.write", true, false,
                new String[][]{{"deliveries","supplier_id"},{"payments","supplier_id"},{"lube_movements","supplier_id"}}, new String[]{}));
        web.get("/catalog/expense-categories", ctx -> crudList(ctx, "expense_categories", "expenses.read", true, false, "name"));
        web.post("/catalog/expense-categories", ctx -> crudCreate(ctx, "expense_categories", "exc", "expenses.write", true, false, "name", "isActive"));
        web.patch("/catalog/expense-categories/:id", ctx -> crudPatch(ctx, "expense_categories", "expenses.write", true, "name", "isActive"));
        web.delete("/catalog/expense-categories/:id", ctx -> crudDelete(ctx, "expense_categories", "expenses.write", true, false,
                new String[][]{{"expenses","category_id"}}, new String[]{}));
        web.get("/catalog/employees", CatalogApi::employees);
        web.post("/catalog/employees", CatalogApi::createEmployee);
        web.patch("/catalog/employees/:id", CatalogApi::patchEmployee);
        web.post("/catalog/employees/:id/reset-password", CatalogApi::resetPassword);
        web.delete("/catalog/employees/:id", CatalogApi::disableEmployee);
        web.get("/catalog/followups", CatalogApi::dueFollowups);
        web.get("/catalog/customers/:id/followups", CatalogApi::followups);
        web.post("/catalog/customers/:id/followups", CatalogApi::addFollowup);
        web.get("/customers", ctx -> customers(ctx, "code", "name", "phone"));
    }

    private static void suppliers(Web.Ctx ctx, String... search) {
        Auth auth = ctx.requireAuth();
        auth.require("suppliers.read");
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        String stationParam = ctx.q("stationId");
        final boolean includeTenantOpeningBalance;
        if (stationParam != null && !stationParam.isBlank() && !"all".equalsIgnoreCase(stationParam)) {
            auth.station(stationParam.trim());
            includeTenantOpeningBalance = false;
        } else if (auth.allStations) {
            includeTenantOpeningBalance = true;
        } else {
            if (auth.stationIds.isEmpty()) {
                Map<String, Object> emptyMeta = new LinkedHashMap<>(Domain.page(0, page, size));
                emptyMeta.put("openingBalanceTotal", "0.00");
                ctx.ok(List.of(), emptyMeta);
                return;
            }
            includeTenantOpeningBalance = false;
        }

        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        clauses.add("tenant_id = ?");
        params.add(auth.tenantId());
        if (ctx.q("status") != null && !ctx.q("status").isBlank()) {
            clauses.add("is_active = ?");
            params.add("ACTIVE".equalsIgnoreCase(ctx.q("status")) || "1".equals(ctx.q("status")) ? 1 : 0);
        }
        String q = ctx.q("search");
        if (q != null && !q.isBlank() && search.length > 0) {
            List<String> likes = new ArrayList<>();
            for (String col : search) {
                likes.add(col + " LIKE ?");
                params.add("%" + q.trim() + "%");
            }
            clauses.add("(" + String.join(" OR ", likes) + ")");
        }
        String where = " WHERE " + String.join(" AND ", clauses);
        long total = Db.count("SELECT COUNT(*) AS count FROM suppliers" + where, params.toArray());
        String openingBalanceTotal = "0.00";
        if (includeTenantOpeningBalance) {
            Map<String, Object> sumRow = Db.one(
                    "SELECT COALESCE(SUM(CAST(opening_balance AS DECIMAL(20,2))), 0) AS total FROM suppliers" + where,
                    params.toArray());
            openingBalanceTotal = Money.money(sumRow == null ? null : sumRow.get("total"));
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = Db.all(
                "SELECT * FROM suppliers" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                pageParams.toArray());
        List<Map<String, Object>> shaped = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = Json.shape(row);
            item.put("openingBalance", includeTenantOpeningBalance ? Money.money(row.get("opening_balance")) : "0.00");
            shaped.add(item);
        }
        Map<String, Object> meta = new LinkedHashMap<>(Domain.page(total, page, size));
        meta.put("openingBalanceTotal", openingBalanceTotal);
        ctx.ok(shaped, meta);
    }

    private static void customers(Web.Ctx ctx, String... search) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.read");
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        String stationParam = ctx.q("stationId");
        final List<String> scopedStationIds;
        if (stationParam != null && !stationParam.isBlank() && !"all".equalsIgnoreCase(stationParam)) {
            auth.station(stationParam.trim());
            scopedStationIds = List.of(stationParam.trim());
        } else if (auth.allStations) {
            scopedStationIds = null;
        } else {
            if (auth.stationIds.isEmpty()) {
                ctx.ok(List.of(), Domain.page(0, page, size));
                return;
            }
            scopedStationIds = new ArrayList<>(auth.stationIds);
        }

        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        clauses.add("tenant_id = ?");
        params.add(auth.tenantId());
        String q = ctx.q("search");
        if ((q == null || q.isBlank()) && ctx.q("q") != null) q = ctx.q("q");
        if (q != null && !q.isBlank() && search.length > 0) {
            List<String> likes = new ArrayList<>();
            for (String column : search) {
                likes.add(column + " LIKE ?");
                params.add("%" + q.trim() + "%");
            }
            clauses.add("(" + String.join(" OR ", likes) + ")");
        }
        String where = "WHERE " + String.join(" AND ", clauses);
        long total = Db.count("SELECT COUNT(*) AS count FROM customers " + where, params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rawRows = Db.all(
                "SELECT * FROM customers " + where + " ORDER BY name ASC LIMIT ? OFFSET ?",
                params.toArray());

        Map<String, java.math.BigDecimal> stationBalances = Map.of();
        if (scopedStationIds != null && !rawRows.isEmpty()) {
            List<String> customerIds = new ArrayList<>(rawRows.size());
            for (Map<String, Object> r : rawRows) {
                String cid = Db.str(r, "id");
                if (cid != null) customerIds.add(cid);
            }
            stationBalances = Domain.stationScopedCustomerBalances(auth.tenantId(), customerIds, scopedStationIds);
        }

        List<Map<String, Object>> shaped = new ArrayList<>(rawRows.size());
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> item = Json.shape(r);
            if (scopedStationIds == null) {
                item.put("balance", Money.money(r.get("balance")));
            } else {
                String cid = Db.str(r, "id");
                item.put("balance", Money.money(stationBalances.getOrDefault(cid, java.math.BigDecimal.ZERO)));
            }
            shaped.add(item);
        }
        ctx.ok(shaped, Domain.page(total, page, size));
    }

    private static void stations(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stations.read");
        List<Map<String, Object>> rows = Db.all(
                """
                SELECT s.*,
                       (SELECT COUNT(*) FROM tanks t WHERE t.station_id = s.id) AS tank_count,
                       (SELECT COUNT(*) FROM dispensers d WHERE d.station_id = s.id) AS dispenser_count,
                       (SELECT COUNT(*) FROM nozzles n WHERE n.station_id = s.id) AS nozzle_count,
                       (SELECT COUNT(*) FROM hardware_devices h WHERE h.station_id = s.id) AS device_count
                  FROM stations s WHERE s.tenant_id = ? ORDER BY s.name
                """, auth.tenantId());
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (auth.allStations || auth.stationIds.contains(Db.str(row, "id"))) visible.add(Json.shape(row));
        }
        ctx.ok(visible);
    }

    private static void createStation(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stations.write");
        String code = required(ctx, "code");
        String name = required(ctx, "name");
        long count = Db.count("SELECT COUNT(*) AS count FROM stations WHERE tenant_id = ?", auth.tenantId());
        Map<String, Object> limit = Db.one(
                """
                SELECT p.max_stations FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
                 WHERE s.tenant_id = ? AND s.status IN ('ACTIVE','TRIALING') ORDER BY s.created_at DESC LIMIT 1
                """, auth.tenantId());
        if (limit != null && count >= Db.num(limit, "max_stations")) {
            throw ApiException.unprocessable("Your plan allows " + limit.get("max_stations") + " station(s). Upgrade to add more.");
        }
        if (Db.one("SELECT id FROM stations WHERE tenant_id = ? AND code = ?", auth.tenantId(), code) != null) {
            throw ApiException.conflict("A station with this code already exists");
        }
        String id = Clock.newId("stn");
        String now = Clock.nowIso();
        Db.exec(
                """
                INSERT INTO stations (id, tenant_id, code, name, address, city, state, pincode, timezone, status, is_demo, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Asia/Kolkata', 'ACTIVE', 0, ?, ?)
                """,
                id, auth.tenantId(), code, name, ctx.str("address"), ctx.str("city"), ctx.str("state"), ctx.str("pincode"), now, now);
        Auth.audit(auth, "STATION_CREATED", "Station", id, id, name + " (" + code + ")");
        ctx.created(Json.shape(Db.one("SELECT * FROM stations WHERE id = ?", id)));
    }

    private static void patchStation(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stations.write");
        Map<String, Object> station = auth.station(ctx.p("id"));
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (String key : List.of("name", "address", "city", "state", "pincode", "status")) {
            if (ctx.body().containsKey(key)) {
                sets.add(key + " = ?");
                params.add(ctx.str(key));
            }
        }
        if (!sets.isEmpty()) {
            params.add(Clock.nowIso());
            params.add(station.get("id"));
            Db.exec("UPDATE stations SET " + String.join(", ", sets) + ", updated_at = ? WHERE id = ?", params.toArray());
        }
        ctx.ok(Json.shape(Db.one("SELECT * FROM stations WHERE id = ?", station.get("id"))));
    }

    private static void prices(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("products.read");
        String stationId = ctx.q("stationId");
        if (stationId != null) auth.station(stationId);
        List<Map<String, Object>> rows = Db.all(
                """
                SELECT fp.*, p.name AS product_name, p.code AS product_code, s.name AS station_name
                  FROM fuel_prices fp
                  JOIN fuel_products p ON p.id = fp.product_id
                  LEFT JOIN stations s ON s.id = fp.station_id
                 WHERE fp.tenant_id = ?
                 ORDER BY fp.effective_from DESC LIMIT 200
                """, auth.tenantId());
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String id = Db.str(row, "station_id");
            if (id != null && !auth.allStations && !auth.stationIds.contains(id)) continue;
            if (stationId != null && id != null && !stationId.equals(id)) continue;
            visible.add(Json.shape(row));
        }
        ctx.ok(visible);
    }

    private static void currentPrices(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("products.read");
        String stationId = ctx.q("stationId");
        if (stationId != null && !stationId.isBlank()) auth.station(stationId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> product : Db.all("SELECT * FROM fuel_products WHERE tenant_id = ? AND is_active = 1 ORDER BY code", auth.tenantId())) {
            Map<String, Object> price = Domain.priceAt(auth.tenantId(), Db.str(product, "id"), stationId);
            out.add(Web.map(
                    "product", Json.shape(product),
                    "ratePerLitre", price == null ? null : price.get("rate_per_litre"),
                    "effectiveFrom", price == null ? null : price.get("effective_from"),
                    "priceId", price == null ? null : price.get("id")));
        }
        ctx.ok(out);
    }

    private static void createPrice(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("prices.write");
        String productId = required(ctx, "productId");
        String rate = ctx.str("rate") != null ? ctx.str("rate") : required(ctx, "ratePerLitre");
        String stationId = ctx.str("stationId");
        if (stationId != null && !stationId.isBlank()) auth.station(stationId);
        else stationId = null;
        String id = Clock.newId("prc");
        String now = Clock.nowIso();
        String from = ctx.str("effectiveFrom") == null || ctx.str("effectiveFrom").isBlank() ? now : ctx.str("effectiveFrom");
        Db.exec(
                """
                INSERT INTO fuel_prices (id, tenant_id, station_id, product_id, rate_per_litre, effective_from, note, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, auth.tenantId(), stationId, productId, Money.rate(rate), from, ctx.str("note"), auth.userId, now);
        Auth.audit(auth, "PRICE_CHANGED", "FuelPrice", id, stationId, "Price " + Money.rate(rate));
        ctx.created(Json.shape(Db.one("SELECT * FROM fuel_prices WHERE id = ?", id)));
    }

    private static void getCalibration(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("tanks.read");
        Map<String, Object> tank = Db.one("SELECT * FROM tanks WHERE id = ?", ctx.p("id"));
        if (tank == null) throw ApiException.notFound("Tank not found");
        auth.station(Db.str(tank, "station_id"));
        List<Map<String, Object>> points = Db.all(
                "SELECT id, dip_cm AS dipCm, litres FROM tank_calibration_points WHERE tank_id = ? ORDER BY CAST(dip_cm AS DECIMAL(18,4)) ASC",
                tank.get("id"));
        ctx.ok(Web.map("tank", Json.shape(tank), "points", points));
    }

    @SuppressWarnings("unchecked")
    private static void putCalibration(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("tanks.write");
        Map<String, Object> tank = Db.one("SELECT * FROM tanks WHERE id = ?", ctx.p("id"));
        if (tank == null) throw ApiException.notFound("Tank not found");
        auth.station(Db.str(tank, "station_id"));
        Object raw = ctx.body().get("points");
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw ApiException.unprocessable("At least two calibration points are required");
        }
        Db.tx(() -> {
            Db.exec("DELETE FROM tank_calibration_points WHERE tank_id = ?", tank.get("id"));
            for (Object item : list) {
                Map<String, Object> pt = Json.object(item);
                Db.exec("INSERT INTO tank_calibration_points (id, tank_id, dip_cm, litres) VALUES (?, ?, ?, ?)",
                        Clock.newId("tcp"), tank.get("id"), Money.fixed(pt.get("dipCm"), 2), Money.litres(pt.get("litres")));
            }
            if (ctx.body().containsKey("reference")) {
                Db.exec("UPDATE tanks SET calibration_reference = ?, updated_at = ? WHERE id = ?", ctx.str("reference"), Clock.nowIso(), tank.get("id"));
            }
            return null;
        });
        ctx.ok(Web.map("count", list.size()));
    }

    private static void listMeasurements(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.read");
        Map<String, Object> tank = Db.one(
                "SELECT t.* FROM tanks t JOIN stations s ON s.id = t.station_id WHERE t.id = ? AND s.tenant_id = ?",
                ctx.p("id"), auth.tenantId());
        if (tank == null) throw ApiException.notFound("Tank not found");
        auth.station(Db.str(tank, "station_id"));
        ctx.ok(Json.shapeAll(Db.all("SELECT * FROM tank_measurements WHERE tank_id = ? ORDER BY measured_at DESC LIMIT 60", tank.get("id"))));
    }

    private static void measurement(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("stock.write");
        Map<String, Object> created = Db.tx(() -> {
            Map<String, Object> tank = Db.one(
                    "SELECT t.* FROM tanks t JOIN stations s ON s.id = t.station_id WHERE t.id = ? AND s.tenant_id = ?",
                    ctx.p("id"), auth.tenantId());
            if (tank == null) throw ApiException.notFound("Tank not found");
            String stationId = Db.str(tank, "station_id");
            auth.station(stationId);
            String rawMeasuredAt = ctx.str("measuredAt");
            String measuredAt = (rawMeasuredAt == null || rawMeasuredAt.isBlank())
                    ? Clock.nowIso()
                    : (Clock.isIsoDate(rawMeasuredAt.trim()) ? Clock.istDayStart(rawMeasuredAt.trim()) : rawMeasuredAt.trim());
            String businessDate = Clock.isIsoDate(measuredAt) ? measuredAt : Clock.businessDateOf(measuredAt);
            Domain.assertDayOpen(stationId, businessDate);

            String litres = ctx.str("measuredLitres");
            String dipCm = ctx.str("dipCm");
            if ((litres == null || litres.isBlank()) && dipCm != null && !dipCm.isBlank()) {
                litres = Money.litres(Domain.convertDipToLitres(Db.str(tank, "id"), dipCm));
            }
            if (litres == null || litres.isBlank()) throw ApiException.validation("measuredLitres", "Enter measured litres or dip (cm)");
            var book = Domain.bookStock(Db.str(tank, "id"));
            String id = Clock.newId("tms");
            String now = Clock.nowIso();
            Db.exec(
                    """
                    INSERT INTO tank_measurements
                      (id, tenant_id, station_id, tank_id, method, dip_cm, water_dip_cm, measured_litres, book_stock_litres, variance_litres, measured_at, note, entered_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, auth.tenantId(), stationId, tank.get("id"),
                    ctx.str("method") == null ? (dipCm != null ? "DIP" : "MANUAL") : ctx.str("method"),
                    dipCm, ctx.str("waterDipCm"),
                    Money.litres(litres), Money.litres(book), Money.litres(Money.d(litres).subtract(book)),
                    measuredAt, ctx.str("note"), auth.userId, now);
            return Json.shape(Db.one("SELECT * FROM tank_measurements WHERE id = ?", id));
        });
        ctx.created(created);
    }

    private static void nozzleHistory(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("readings.read");
        Map<String, Object> nozzle = Db.one("SELECT * FROM nozzles WHERE id = ?", ctx.p("id"));
        if (nozzle == null) throw ApiException.notFound("Nozzle not found");
        auth.station(Db.str(nozzle, "station_id"));
        ctx.ok(Json.shapeAll(Db.all("SELECT * FROM meter_readings WHERE nozzle_id = ? ORDER BY reading_at DESC LIMIT 50", nozzle.get("id"))));
    }

    private static void requireTenantCustomer(Auth auth, String customerId) {
        if (customerId == null || customerId.isBlank()
                || Db.one("SELECT id FROM customers WHERE id = ? AND tenant_id = ?", customerId, auth.tenantId()) == null) {
            throw ApiException.notFound("Customer not found");
        }
    }

    private static void vehicles(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.read");
        requireTenantCustomer(auth, ctx.p("id"));
        ctx.ok(Json.shapeAll(Db.all("SELECT * FROM customer_vehicles WHERE customer_id = ? AND tenant_id = ? ORDER BY vehicle_number", ctx.p("id"), auth.tenantId())));
    }

    private static void addVehicle(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.write");
        requireTenantCustomer(auth, ctx.p("id"));
        String vehicleNumber = required(ctx, "vehicleNumber").trim();
        String id = Clock.newId("veh");
        String now = Clock.nowIso();
        Db.exec(
                "INSERT INTO customer_vehicles (id, tenant_id, customer_id, vehicle_number, vehicle_type, driver_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, auth.tenantId(), ctx.p("id"), vehicleNumber, ctx.str("vehicleType"), ctx.str("driverName"), now, now);
        ctx.created(Json.shape(Db.one("SELECT * FROM customer_vehicles WHERE id = ?", id)));
    }

    private static void deleteVehicle(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.write");
        requireTenantCustomer(auth, ctx.p("id"));
        Db.exec("DELETE FROM customer_vehicles WHERE id = ? AND customer_id = ? AND tenant_id = ?", ctx.p("vehicleId"), ctx.p("id"), auth.tenantId());
        ctx.ok(Map.of("deleted", true));
    }

    private static void employees(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("employees.read");
        List<Map<String, Object>> rows = Db.all(
                """
                SELECT id, name, email, phone, role_key, status, last_login_at, created_at, is_demo, granted_permissions
                  FROM users WHERE tenant_id = ? AND role_key IN ('OWNER','MANAGER','STAFF') ORDER BY name
                """, auth.tenantId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!auth.allStations && "OWNER".equals(row.get("role_key"))) continue;
            Map<String, Object> shaped = Json.shape(row);
            List<String> stations = new ArrayList<>();
            for (Map<String, Object> access : Db.all("SELECT station_id FROM user_station_access WHERE user_id = ?", row.get("id"))) {
                String stationId = Db.str(access, "station_id");
                if (auth.allStations || auth.stationIds.contains(stationId)) stations.add(stationId);
            }
            shaped.put("stationIds", stations);
            shaped.put("permissions", "STAFF".equals(row.get("role_key")) ? Permissions.sanitize(row.get("granted_permissions")) : Permissions.forRole(Db.str(row, "role_key")));
            out.add(shaped);
        }
        ctx.ok(out);
    }

    private static void createEmployee(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("employees.write");
        String role = ctx.str("roleKey") == null ? "STAFF" : ctx.str("roleKey");
        if ("OWNER".equals(role)) throw ApiException.conflict("This organization already has one owner. The owner account cannot be duplicated.");
        if ("MANAGER".equals(role) && Db.one("SELECT id FROM users WHERE tenant_id = ? AND role_key = 'MANAGER' AND status = 'ACTIVE'", auth.tenantId()) != null) {
            throw ApiException.conflict("Only one manager is allowed. Add a staff login if you need another person with chosen permissions.");
        }
        long cap = Db.count(
                """
                SELECT p.max_users FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
                 WHERE s.tenant_id = ? ORDER BY s.created_at DESC LIMIT 1
                """, auth.tenantId());
        if (cap == 0) cap = 15;
        if (Db.count("SELECT COUNT(*) AS count FROM users WHERE tenant_id = ? AND status = 'ACTIVE'", auth.tenantId()) >= cap) {
            throw ApiException.conflict("This plan allows " + cap + " team logins. Disable an unused account or ask the platform administrator to raise the limit.");
        }
        String password = required(ctx, "password");
        Passwords.assertStrength(password);
        String email = required(ctx, "email").toLowerCase().trim();
        if (Db.one("SELECT id FROM users WHERE email = ?", email) != null) throw ApiException.conflict("A user with this email already exists");
        List<String> stationIds = stringList(ctx.body().get("stationIds"));
        if (stationIds.isEmpty()) throw ApiException.unprocessable("Assign this login to at least one pump");
        for (String stationId : stationIds) auth.station(stationId);
        List<String> grants = "STAFF".equals(role) ? Permissions.sanitize(ctx.body().get("permissions")) : List.of();
        if ("STAFF".equals(role) && grants.isEmpty()) throw ApiException.unprocessable("Tick at least one permission. A staff login with no access cannot sign in to any screen.");
        String id = Clock.newId("usr");
        String now = Clock.nowIso();
        Db.tx(() -> {
            Db.exec(
                    """
                    INSERT INTO users (id, tenant_id, email, name, phone, password_hash, role_key, status, is_demo, granted_permissions, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?, ?)
                    """,
                    id, auth.tenantId(), email, required(ctx, "name"), ctx.str("phone"), Passwords.hash(password), role, Json.stringify(grants), now, now);
            for (String stationId : stationIds) {
                Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)", Clock.newId("usa"), id, stationId, now);
            }
            return id;
        });
        Auth.audit(auth, "EMPLOYEE_CREATED", "User", id, null, required(ctx, "name") + " (" + role + ")");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", ctx.str("name"));
        data.put("email", email);
        data.put("roleKey", role);
        data.put("stationIds", stationIds);
        data.put("permissions", grants);
        ctx.created(data);
    }

    private static void patchEmployee(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("employees.write");
        Map<String, Object> existing = Db.one("SELECT * FROM users WHERE id = ? AND tenant_id = ?", ctx.p("id"), auth.tenantId());
        if (existing == null) throw ApiException.notFound("Employee not found");
        if ("OWNER".equals(existing.get("role_key")) && "DISABLED".equals(ctx.str("status"))) {
            throw ApiException.unprocessable("The owner account must remain active. You can update its name and phone.");
        }
        List<String> grants = "STAFF".equals(existing.get("role_key")) ? Permissions.sanitize(ctx.body().containsKey("permissions") ? ctx.body().get("permissions") : existing.get("granted_permissions")) : List.of();
        if ("STAFF".equals(existing.get("role_key")) && grants.isEmpty()) throw ApiException.unprocessable("A staff login needs at least one permission.");
        Db.tx(() -> {
            if (ctx.body().containsKey("name") || ctx.body().containsKey("phone") || ctx.body().containsKey("status") || ctx.body().containsKey("permissions")) {
                Db.exec(
                        "UPDATE users SET name = COALESCE(?, name), phone = COALESCE(?, phone), status = COALESCE(?, status), granted_permissions = ?, updated_at = ? WHERE id = ?",
                        ctx.str("name"), ctx.str("phone"), ctx.str("status"), Json.stringify(grants), Clock.nowIso(), existing.get("id"));
            }
            if (ctx.body().containsKey("stationIds") && !"OWNER".equals(existing.get("role_key"))) {
                Db.exec("DELETE FROM user_station_access WHERE user_id = ?", existing.get("id"));
                for (String stationId : stringList(ctx.body().get("stationIds"))) {
                    auth.station(stationId);
                    Db.exec("INSERT INTO user_station_access (id, user_id, station_id, permissions, created_at) VALUES (?, ?, ?, '[]', ?)", Clock.newId("usa"), existing.get("id"), stationId, Clock.nowIso());
                }
            }
            if ("DISABLED".equals(ctx.str("status")) || ("STAFF".equals(existing.get("role_key")) && ctx.body().containsKey("permissions"))) {
                Db.exec("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", Clock.nowIso(), existing.get("id"));
            }
            return null;
        });
        Auth.audit(auth, "EMPLOYEE_UPDATED", "User", Db.str(existing, "id"), null, "Employee updated");
        ctx.ok(Map.of("id", existing.get("id"), "roleKey", existing.get("role_key"), "permissions", "STAFF".equals(existing.get("role_key")) ? grants : Permissions.forRole(Db.str(existing, "role_key"))));
    }

    private static void resetPassword(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("employees.write");
        String password = required(ctx, "newPassword");
        Passwords.assertStrength(password);
        Db.exec("UPDATE users SET password_hash = ?, must_change_password = 1, updated_at = ? WHERE id = ? AND tenant_id = ?", Passwords.hash(password), Clock.nowIso(), ctx.p("id"), auth.tenantId());
        Db.exec("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", Clock.nowIso(), ctx.p("id"));
        ctx.ok(Map.of("reset", true));
    }

    private static void disableEmployee(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("employees.write");
        if (ctx.p("id").equals(auth.userId)) throw ApiException.unprocessable("You cannot disable your own account");
        Map<String, Object> existing = Db.one("SELECT * FROM users WHERE id = ? AND tenant_id = ?", ctx.p("id"), auth.tenantId());
        if (existing == null) throw ApiException.notFound("Employee not found");
        if ("OWNER".equals(existing.get("role_key"))) throw ApiException.unprocessable("The owner account cannot be disabled.");
        Db.exec("UPDATE users SET status = 'DISABLED', updated_at = ? WHERE id = ?", Clock.nowIso(), existing.get("id"));
        Db.exec("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", Clock.nowIso(), existing.get("id"));
        ctx.ok(Map.of("disabled", true));
    }

    private static void dueFollowups(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.read");
        String due = ctx.q("dueBy") == null ? Clock.todayIst() : ctx.q("dueBy");
        String stationParam = ctx.q("stationId");
        if (stationParam != null && !stationParam.isBlank() && !"all".equalsIgnoreCase(stationParam)) {
            String targetStation = stationParam.trim();
            auth.station(targetStation);
            List<Map<String, Object>> rows = Db.all(
                    """
                    SELECT f.*, c.name AS customer_name, c.code AS customer_code
                      FROM customer_followups f JOIN customers c ON c.id = f.customer_id
                     WHERE f.tenant_id = ? AND f.station_id = ? AND f.next_on IS NOT NULL AND f.next_on <= ?
                     ORDER BY f.next_on ASC LIMIT 30
                    """, auth.tenantId(), targetStation, due);
            ctx.ok(shapeFollowupsWithScopedBalances(auth.tenantId(), rows, List.of(targetStation)));
            return;
        }
        if (auth.allStations) {
            List<Map<String, Object>> rows = Db.all(
                    """
                    SELECT f.*, c.name AS customer_name, c.code AS customer_code, c.balance
                      FROM customer_followups f JOIN customers c ON c.id = f.customer_id
                     WHERE f.tenant_id = ? AND f.next_on IS NOT NULL AND f.next_on <= ?
                     ORDER BY f.next_on ASC LIMIT 30
                    """, auth.tenantId(), due);
            List<Map<String, Object>> shaped = Json.shapeAll(rows);
            for (Map<String, Object> item : shaped) {
                item.put("balance", Money.money(item.get("balance")));
            }
            ctx.ok(shaped);
            return;
        }
        if (auth.stationIds.isEmpty()) {
            ctx.ok(List.of());
            return;
        }
        List<String> allowed = new ArrayList<>(auth.stationIds);
        String inSql = String.join(",", Collections.nCopies(allowed.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(auth.tenantId());
        params.addAll(allowed);
        params.add(due);
        List<Map<String, Object>> rows = Db.all(
                "SELECT f.*, c.name AS customer_name, c.code AS customer_code "
                        + "FROM customer_followups f JOIN customers c ON c.id = f.customer_id "
                        + "WHERE f.tenant_id = ? AND f.station_id IN (" + inSql + ") AND f.next_on IS NOT NULL AND f.next_on <= ? "
                        + "ORDER BY f.next_on ASC LIMIT 30",
                params.toArray());
        ctx.ok(shapeFollowupsWithScopedBalances(auth.tenantId(), rows, allowed));
    }

    private static List<Map<String, Object>> shapeFollowupsWithScopedBalances(
            String tenantId,
            List<Map<String, Object>> rawRows,
            List<String> stationIds) {
        if (rawRows.isEmpty()) return List.of();
        List<String> customerIds = new ArrayList<>(rawRows.size());
        for (Map<String, Object> r : rawRows) {
            String cid = Db.str(r, "customer_id");
            if (cid != null) customerIds.add(cid);
        }
        Map<String, java.math.BigDecimal> balances = Domain.stationScopedCustomerBalances(tenantId, customerIds, stationIds);
        List<Map<String, Object>> out = new ArrayList<>(rawRows.size());
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> item = Json.shape(r);
            String cid = Db.str(r, "customer_id");
            item.put("balance", Money.money(balances.getOrDefault(cid, java.math.BigDecimal.ZERO)));
            out.add(item);
        }
        return out;
    }

    private static void followups(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.read");
        requireTenantCustomer(auth, ctx.p("id"));
        String stationParam = ctx.q("stationId");
        if (stationParam != null && !stationParam.isBlank() && !"all".equalsIgnoreCase(stationParam)) {
            auth.station(stationParam.trim());
            ctx.ok(Json.shapeAll(Db.all(
                    """
                    SELECT f.*, u.name AS created_by_name FROM customer_followups f
                      LEFT JOIN users u ON u.id = f.created_by
                     WHERE f.customer_id = ? AND f.tenant_id = ? AND f.station_id = ? ORDER BY f.created_at DESC LIMIT 40
                    """, ctx.p("id"), auth.tenantId(), stationParam.trim())));
            return;
        }
        if (auth.allStations) {
            ctx.ok(Json.shapeAll(Db.all(
                    """
                    SELECT f.*, u.name AS created_by_name FROM customer_followups f
                      LEFT JOIN users u ON u.id = f.created_by
                     WHERE f.customer_id = ? AND f.tenant_id = ? ORDER BY f.created_at DESC LIMIT 40
                    """, ctx.p("id"), auth.tenantId())));
            return;
        }
        if (auth.stationIds.isEmpty()) {
            throw ApiException.forbidden("You do not have access to any station");
        }
        List<String> allowed = new ArrayList<>(auth.stationIds);
        String inSql = String.join(",", Collections.nCopies(allowed.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(ctx.p("id"));
        params.add(auth.tenantId());
        params.addAll(allowed);
        ctx.ok(Json.shapeAll(Db.all(
                "SELECT f.*, u.name AS created_by_name FROM customer_followups f "
                        + "LEFT JOIN users u ON u.id = f.created_by "
                        + "WHERE f.customer_id = ? AND f.tenant_id = ? AND f.station_id IN (" + inSql + ") "
                        + "ORDER BY f.created_at DESC LIMIT 40",
                params.toArray())));
    }

    private static void addFollowup(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("customers.write");
        requireTenantCustomer(auth, ctx.p("id"));
        String rawStation = ctx.str("stationId");
        String resolvedStationId = null;
        if (rawStation != null && !rawStation.isBlank()) {
            resolvedStationId = Db.str(auth.station(rawStation.trim()), "id");
        } else if (!auth.allStations) {
            if (auth.stationIds.isEmpty()) {
                throw ApiException.forbidden("You do not have access to any station");
            }
            if (auth.stationIds.size() == 1) {
                resolvedStationId = auth.stationIds.iterator().next();
            } else {
                throw ApiException.validation("stationId", "stationId is required when assigned to multiple stations");
            }
        }
        String kind = required(ctx, "kind");
        String note = required(ctx, "note");
        String id = Clock.newId("cfu");
        Db.exec(
                """
                INSERT INTO customer_followups (id, tenant_id, customer_id, station_id, kind, note, promised_amount, next_on, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, auth.tenantId(), ctx.p("id"), resolvedStationId, kind, note.trim(), ctx.str("promisedAmount"), ctx.str("nextOn"), auth.userId, Clock.nowIso());
        Auth.audit(auth, "CUSTOMER_FOLLOWUP", "Customer", ctx.p("id"), resolvedStationId, kind);
        ctx.created(Map.of("id", id));
    }

    private static void crudList(Web.Ctx ctx, String table, String permission, boolean tenant, boolean station, String... search) {
        Auth auth = ctx.requireAuth();
        auth.require(permission);
        int page = Domain.pageOf(ctx);
        int size = Domain.pageSize(ctx);
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (tenant) {
            clauses.add("tenant_id = ?");
            params.add(auth.tenantId());
        }
        if (station) {
            String stationId = ctx.q("stationId");
            if (stationId != null) {
                auth.station(stationId);
                clauses.add("station_id = ?");
                params.add(stationId);
            } else if (!auth.allStations) {
                clauses.add("station_id IN (" + placeholders(auth.stationIds.size()) + ")");
                params.addAll(auth.stationIds);
            } else {
                clauses.add("station_id IN (SELECT id FROM stations WHERE tenant_id = ?)");
                params.add(auth.tenantId());
            }
        }
        String q = ctx.q("search");
        if ((q == null || q.isBlank()) && ctx.q("q") != null) q = ctx.q("q");
        if (q != null && !q.isBlank() && search.length > 0) {
            List<String> likes = new ArrayList<>();
            for (String column : search) {
                likes.add(column + " LIKE ?");
                params.add("%" + q.trim() + "%");
            }
            clauses.add("(" + String.join(" OR ", likes) + ")");
        }
        String where = clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
        long total = Db.count("SELECT COUNT(*) AS count FROM " + table + " " + where, params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        String order = switch (table) {
            case "customers", "suppliers", "expense_categories" -> "name ASC";
            case "nozzles" -> "number ASC";
            case "fuel_products", "tanks", "dispensers" -> "code ASC";
            default -> "id DESC";
        };
        ctx.ok(Json.shapeAll(Db.all("SELECT * FROM " + table + " " + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", params.toArray())), Domain.page(total, page, size));
    }

    private static void crudCreate(Web.Ctx ctx, String table, String prefix, String permission, boolean tenant, boolean station, String... fields) {
        Auth auth = ctx.requireAuth();
        auth.require(permission);
        String id = Clock.newId(prefix);
        List<String> columns = new ArrayList<>(List.of("id"));
        List<String> marks = new ArrayList<>(List.of("?"));
        List<Object> values = new ArrayList<>(List.of(id));
        if (station) {
            Map<String, Object> st = auth.station(required(ctx, "stationId"));
            columns.add("station_id");
            marks.add("?");
            values.add(st.get("id"));
            if (tenant) {
                columns.add("tenant_id");
                marks.add("?");
                values.add(st.get("tenant_id"));
            }
        } else if (tenant) {
            columns.add("tenant_id");
            marks.add("?");
            values.add(auth.tenantId());
        }
        for (String field : fields) {
            if ("stationId".equals(field) || !ctx.body().containsKey(field)) continue;
            if ("code".equals(field) && (ctx.body().get(field) == null || String.valueOf(ctx.body().get(field)).isBlank())) continue;
            if ("suppliers".equals(table) && "openingBalance".equals(field) && !auth.allStations) {
                Object rawOpening = ctx.body().get(field);
                if (rawOpening != null && !String.valueOf(rawOpening).isBlank() && Money.d(rawOpening).compareTo(java.math.BigDecimal.ZERO) != 0) {
                    throw ApiException.forbidden("Only tenant owners can set tenant-wide supplier opening balances");
                }
                continue;
            }
            columns.add(snake(field));
            marks.add("?");
            values.add(valueOf(field, ctx.body().get(field)));
        }
        if ("dispensers".equals(table) && !columns.contains("serial_number")) {
            columns.add("serial_number");
            marks.add("?");
            values.add("SN-" + id.substring(4, 12));
        }
        if ("customers".equals(table)) {
            if (!columns.contains("code")) {
                columns.add("code");
                marks.add("?");
                values.add("CUST-" + String.format("%03d", Db.count("SELECT COUNT(*) FROM customers WHERE tenant_id = ?", auth.tenantId()) + 1));
            }
            columns.add("balance");
            marks.add("?");
            values.add("0.00");
        }
        if ("suppliers".equals(table)) {
            if (!auth.allStations && auth.stationIds.isEmpty()) {
                throw ApiException.forbidden("You do not have access to any station");
            }
            if (!columns.contains("code")) {
                columns.add("code");
                marks.add("?");
                values.add("SUP-" + String.format("%03d", Db.count("SELECT COUNT(*) FROM suppliers WHERE tenant_id = ?", auth.tenantId()) + 1));
            }
            if (!auth.allStations && !columns.contains("opening_balance")) {
                columns.add("opening_balance");
                marks.add("?");
                values.add("0.00");
            }
        }
        String now = Clock.nowIso();
        if (!"expense_categories".equals(table)) {
            columns.add("created_at");
            marks.add("?");
            values.add(now);
            columns.add("updated_at");
            marks.add("?");
            values.add(now);
        }
        Db.exec("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", marks) + ")", values.toArray());
        Map<String, Object> created = Json.shape(Db.one("SELECT * FROM " + table + " WHERE id = ?", id));
        if ("suppliers".equals(table)) {
            created.put("openingBalance", auth.allStations ? Money.money(created.get("openingBalance")) : "0.00");
        }
        ctx.created(created);
    }

    private static void createTank(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("tanks.write");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        String stationId = Db.str(station, "id");
        String code = required(ctx, "code").trim();
        String name = required(ctx, "name").trim();
        String productId = required(ctx, "productId").trim();
        java.math.BigDecimal capacity = Money.d(required(ctx, "capacityLitres"));
        if (capacity.signum() <= 0) throw ApiException.validation("capacityLitres", "Capacity must be greater than zero");
        java.math.BigDecimal deadStock = ctx.str("deadStockLitres") == null || ctx.str("deadStockLitres").isBlank()
                ? java.math.BigDecimal.ZERO : Money.d(ctx.str("deadStockLitres"));
        if (deadStock.signum() < 0 || deadStock.compareTo(capacity) >= 0) {
            throw ApiException.validation("deadStockLitres", "Dead stock must be non-negative and less than tank capacity");
        }
        Map<String, Object> product = Db.one("SELECT id FROM fuel_products WHERE id = ? AND tenant_id = ?", productId, auth.tenantId());
        if (product == null) throw ApiException.validation("productId", "Selected fuel product was not found");
        String status = ctx.str("status") == null || ctx.str("status").isBlank() ? "ACTIVE" : ctx.str("status").trim().toUpperCase();

        Map<String, Object> created = Db.tx(() -> {
            if (Db.one("SELECT id FROM tanks WHERE station_id = ? AND code = ?", stationId, code) != null) {
                throw ApiException.conflict("Tank code " + code + " already exists on this station");
            }
            String id = Clock.newId("tnk");
            String now = Clock.nowIso();
            String calRef = ctx.str("calibrationReference");
            Db.exec(
                    """
                    INSERT INTO tanks
                      (id, station_id, code, name, product_id, capacity_litres, dead_stock_litres, calibration_reference, status, is_demo, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    id, stationId, code, name, productId,
                    Money.litres(capacity), Money.litres(deadStock),
                    (calRef == null || calRef.isBlank()) ? null : calRef.trim(),
                    status, now, now);
            return Json.shape(Db.one("SELECT * FROM tanks WHERE id = ?", id));
        });
        ctx.created(created);
    }

    private static void patchTank(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("tanks.write");
        Map<String, Object> updated = Db.tx(() -> {
            Map<String, Object> existing = Db.one(
                    "SELECT t.* FROM tanks t JOIN stations s ON s.id = t.station_id WHERE t.id = ? AND s.tenant_id = ?",
                    ctx.p("id"), auth.tenantId());
            if (existing == null) throw ApiException.notFound("Tank not found");
            String stationId = Db.str(existing, "station_id");
            auth.station(stationId);
            String tankId = Db.str(existing, "id");

            String effectiveProductId = ctx.body().containsKey("productId") && ctx.str("productId") != null && !ctx.str("productId").isBlank()
                    ? ctx.str("productId").trim() : Db.str(existing, "product_id");
            if (!effectiveProductId.equals(Db.str(existing, "product_id"))) {
                if (Db.one("SELECT id FROM fuel_products WHERE id = ? AND tenant_id = ?", effectiveProductId, auth.tenantId()) == null) {
                    throw ApiException.validation("productId", "Selected fuel product was not found");
                }
                long mvCount = Db.count("SELECT COUNT(*) AS count FROM stock_movements WHERE tank_id = ?", tankId);
                long nzCount = Db.count("SELECT COUNT(*) AS count FROM nozzles WHERE tank_id = ?", tankId);
                if (mvCount > 0 || nzCount > 0) {
                    throw ApiException.conflict("Cannot change tank fuel product while stock movements or mapped nozzles exist");
                }
            }

            String effectiveStatus = ctx.body().containsKey("status") && ctx.str("status") != null && !ctx.str("status").isBlank()
                    ? ctx.str("status").trim().toUpperCase() : Db.str(existing, "status");
            if (!"ACTIVE".equals(effectiveStatus)) {
                long activeNozzles = Db.count("SELECT COUNT(*) AS count FROM nozzles WHERE tank_id = ? AND status = 'ACTIVE'", tankId);
                if (activeNozzles > 0) {
                    throw ApiException.conflict("Cannot deactivate tank while active nozzles are mapped to it; remap or deactivate those nozzles first");
                }
            }

            java.math.BigDecimal capacity = ctx.body().containsKey("capacityLitres") && ctx.body().get("capacityLitres") != null
                    ? Money.d(ctx.body().get("capacityLitres")) : Money.d(existing.get("capacity_litres"));
            java.math.BigDecimal deadStock = ctx.body().containsKey("deadStockLitres") && ctx.body().get("deadStockLitres") != null
                    ? Money.d(ctx.body().get("deadStockLitres")) : Money.d(existing.get("dead_stock_litres"));
            if (capacity.signum() <= 0) throw ApiException.validation("capacityLitres", "Capacity must be greater than zero");
            if (deadStock.signum() < 0 || deadStock.compareTo(capacity) >= 0) {
                throw ApiException.validation("deadStockLitres", "Dead stock must be non-negative and less than tank capacity");
            }

            String name = ctx.body().containsKey("name") && ctx.str("name") != null && !ctx.str("name").isBlank()
                    ? ctx.str("name").trim() : Db.str(existing, "name");
            String calRef = ctx.body().containsKey("calibrationReference")
                    ? (ctx.str("calibrationReference") == null || ctx.str("calibrationReference").isBlank() ? null : ctx.str("calibrationReference").trim())
                    : Db.str(existing, "calibration_reference");

            Db.exec(
                    """
                    UPDATE tanks
                       SET name = ?, product_id = ?, capacity_litres = ?, dead_stock_litres = ?,
                           calibration_reference = ?, status = ?, updated_at = ?
                     WHERE id = ?
                    """,
                    name, effectiveProductId, Money.litres(capacity), Money.litres(deadStock),
                    calRef, effectiveStatus, Clock.nowIso(), tankId);
            return Json.shape(Db.one("SELECT * FROM tanks WHERE id = ?", tankId));
        });
        ctx.ok(updated);
    }

    private static void createNozzle(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("dispensers.write");
        Map<String, Object> station = auth.station(required(ctx, "stationId"));
        String stationId = Db.str(station, "id");
        String dispenserId = required(ctx, "dispenserId").trim();
        String productId = required(ctx, "productId").trim();
        String label = required(ctx, "label").trim();
        if (ctx.body().get("number") == null) throw ApiException.validation("number", "Nozzle number is required");
        int number = (int) Db.num(Map.of("n", ctx.body().get("number")), "n");
        if (number < 1 || number > 99) throw ApiException.validation("number", "Nozzle number must be between 1 and 99");
        String status = ctx.str("status") == null || ctx.str("status").isBlank() ? "ACTIVE" : ctx.str("status").trim().toUpperCase();
        String rawTankId = ctx.str("tankId");
        String tankId = (rawTankId == null || rawTankId.isBlank()) ? null : rawTankId.trim();
        String unit = ctx.str("totalizerUnit") == null || ctx.str("totalizerUnit").isBlank() ? "L" : ctx.str("totalizerUnit").trim();

        Map<String, Object> created = Db.tx(() -> {
            Map<String, Object> dispenser = Db.one("SELECT * FROM dispensers WHERE id = ?", dispenserId);
            if (dispenser == null || !stationId.equals(Db.str(dispenser, "station_id"))) {
                throw ApiException.validation("dispenserId", "Selected dispenser does not belong to this station");
            }
            if (Db.one("SELECT id FROM nozzles WHERE dispenser_id = ? AND number = ?", dispenserId, number) != null) {
                throw ApiException.conflict("Nozzle #" + number + " already exists on this dispenser");
            }
            validateNozzleProductAndTank(auth.tenantId(), stationId, productId, tankId, status);

            String id = Clock.newId("noz");
            String now = Clock.nowIso();
            Db.exec(
                    """
                    INSERT INTO nozzles
                      (id, station_id, dispenser_id, product_id, tank_id, number, label, totalizer_unit, status, is_demo, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    id, stationId, dispenserId, productId, tankId, number, label, unit, status, now, now);
            return Json.shape(Db.one("SELECT * FROM nozzles WHERE id = ?", id));
        });
        ctx.created(created);
    }

    private static void patchNozzle(Web.Ctx ctx) {
        Auth auth = ctx.requireAuth();
        auth.require("dispensers.write");
        Map<String, Object> updated = Db.tx(() -> {
            Map<String, Object> existing = Db.one(
                    "SELECT n.* FROM nozzles n JOIN stations s ON s.id = n.station_id WHERE n.id = ? AND s.tenant_id = ?",
                    ctx.p("id"), auth.tenantId());
            if (existing == null) throw ApiException.notFound("Nozzle not found");
            String stationId = Db.str(existing, "station_id");
            auth.station(stationId);

            String effectiveLabel = ctx.body().containsKey("label") && ctx.str("label") != null && !ctx.str("label").isBlank()
                    ? ctx.str("label").trim() : Db.str(existing, "label");
            String effectiveProductId = ctx.body().containsKey("productId") && ctx.str("productId") != null && !ctx.str("productId").isBlank()
                    ? ctx.str("productId").trim() : Db.str(existing, "product_id");
            String effectiveTankId;
            if (ctx.body().containsKey("tankId")) {
                String raw = ctx.str("tankId");
                effectiveTankId = (raw == null || raw.isBlank()) ? null : raw.trim();
            } else {
                effectiveTankId = Db.str(existing, "tank_id");
            }
            String effectiveStatus = ctx.body().containsKey("status") && ctx.str("status") != null && !ctx.str("status").isBlank()
                    ? ctx.str("status").trim().toUpperCase() : Db.str(existing, "status");
            String effectiveUnit = ctx.body().containsKey("totalizerUnit") && ctx.str("totalizerUnit") != null && !ctx.str("totalizerUnit").isBlank()
                    ? ctx.str("totalizerUnit").trim() : Db.str(existing, "totalizer_unit");

            validateNozzleProductAndTank(auth.tenantId(), stationId, effectiveProductId, effectiveTankId, effectiveStatus);

            Db.exec(
                    """
                    UPDATE nozzles
                       SET label = ?, product_id = ?, tank_id = ?, totalizer_unit = ?, status = ?, updated_at = ?
                     WHERE id = ?
                    """,
                    effectiveLabel, effectiveProductId, effectiveTankId, effectiveUnit, effectiveStatus,
                    Clock.nowIso(), existing.get("id"));
            return Json.shape(Db.one("SELECT * FROM nozzles WHERE id = ?", existing.get("id")));
        });
        ctx.ok(updated);
    }

    private static void validateNozzleProductAndTank(String tenantId, String stationId, String productId, String tankId, String status) {
        Map<String, Object> product = Db.one("SELECT * FROM fuel_products WHERE id = ? AND tenant_id = ?", productId, tenantId);
        if (product == null) throw ApiException.validation("productId", "Selected fuel product was not found");
        boolean activeNozzle = "ACTIVE".equals(status);
        if (activeNozzle && Db.num(product, "is_active") == 0) {
            throw ApiException.validation("productId", "An ACTIVE nozzle cannot use an inactive fuel product");
        }
        if (activeNozzle && (tankId == null || tankId.isBlank())) {
            throw ApiException.validation("tankId", "An ACTIVE nozzle must be mapped to an active underground tank");
        }
        if (tankId != null && !tankId.isBlank()) {
            Map<String, Object> tank = Db.one("SELECT * FROM tanks WHERE id = ?", tankId);
            if (tank == null || !stationId.equals(Db.str(tank, "station_id"))) {
                throw ApiException.validation("tankId", "Selected tank does not belong to this station");
            }
            if (!productId.equals(Db.str(tank, "product_id"))) {
                throw ApiException.validation("tankId", "Nozzle fuel product must match the mapped tank's fuel product");
            }
            if (activeNozzle && !"ACTIVE".equals(Db.str(tank, "status"))) {
                throw ApiException.validation("tankId", "An ACTIVE nozzle cannot be mapped to a non-ACTIVE tank");
            }
        }
    }

    private static void crudPatch(Web.Ctx ctx, String table, String permission, boolean tenant, String... fields) {
        Auth auth = ctx.requireAuth();
        auth.require(permission);
        Map<String, Object> existing = Db.one("SELECT * FROM " + table + " WHERE id = ?", ctx.p("id"));
        if (existing == null) throw ApiException.notFound("Record not found");
        if (tenant && !auth.tenantId().equals(Db.str(existing, "tenant_id"))) throw ApiException.notFound("Record not found");
        if (existing.get("station_id") != null) {
            Map<String, Object> st = Db.one("SELECT id, tenant_id FROM stations WHERE id = ?", existing.get("station_id"));
            if (st == null || !auth.tenantId().equals(Db.str(st, "tenant_id"))) throw ApiException.notFound("Record not found");
            auth.station(Db.str(existing, "station_id"));
        }
        if ("suppliers".equals(table) && !auth.allStations && auth.stationIds.isEmpty()) {
            throw ApiException.forbidden("You do not have access to any station");
        }
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (String field : fields) {
            if (!ctx.body().containsKey(field)) continue;
            sets.add(snake(field) + " = ?");
            params.add(valueOf(field, ctx.body().get(field)));
        }
        if (!sets.isEmpty()) {
            if (!"expense_categories".equals(table)) {
                sets.add("updated_at = ?");
                params.add(Clock.nowIso());
            }
            params.add(existing.get("id"));
            Db.exec("UPDATE " + table + " SET " + String.join(", ", sets) + " WHERE id = ?", params.toArray());
        }
        Map<String, Object> shaped = Json.shape(Db.one("SELECT * FROM " + table + " WHERE id = ?", existing.get("id")));
        if ("customers".equals(table) && !auth.allStations) {
            shaped.put("balance", Domain.stationScopedCustomerBalance(auth.tenantId(), Db.str(existing, "id"), auth.stationIds));
        }
        if ("suppliers".equals(table)) {
            shaped.put("openingBalance", auth.allStations ? Money.money(shaped.get("openingBalance")) : "0.00");
        }
        ctx.ok(shaped);
    }

    /**
     * Owner-only hard delete for master data. Refuses when the row is referenced by
     * operational/financial history (so ledgers and stock never orphan), and cascades
     * only pure configuration children. Station-scoped and strictly audited.
     */
    private static void crudDelete(Web.Ctx ctx, String table, String permission, boolean tenant, boolean station,
                                   String[][] references, String[] cascadeChildren) {
        Auth auth = ctx.requireAuth();
        auth.require(permission);
        auth.requireOwner();
        Map<String, Object> existing = Db.one("SELECT * FROM " + table + " WHERE id = ?", ctx.p("id"));
        if (existing == null) throw ApiException.notFound("Record not found");
        if (tenant && !auth.tenantId().equals(Db.str(existing, "tenant_id"))) throw ApiException.notFound("Record not found");
        String stationId = null;
        if (station && existing.get("station_id") != null) {
            stationId = Db.str(existing, "station_id");
            auth.station(stationId);
        }
        if ("stations".equals(table)) stationId = Db.str(existing, "id");
        for (String[] ref : references) {
            long used = Db.count("SELECT COUNT(*) AS count FROM " + ref[0] + " WHERE " + ref[1] + " = ?", existing.get("id"));
            if (used > 0) {
                throw ApiException.conflict("Cannot delete: this record is used by " + used + " " + ref[0].replace('_', ' ')
                        + " row(s). Deactivate/archive it instead of deleting so history stays intact.");
            }
        }
        final String auditStation = stationId;
        Db.tx(() -> {
            for (String child : cascadeChildren) {
                Db.exec("DELETE FROM " + child + " WHERE " + ("stations".equals(table) ? "station_id" : parentColumn(child)) + " = ?", existing.get("id"));
            }
            Db.exec("DELETE FROM " + table + " WHERE id = ?", existing.get("id"));
            Auth.auditRequired(auth, "MASTER_DELETED", table, Db.str(existing, "id"), auditStation,
                    "Owner deleted " + table + " " + Db.str(existing, "id"), existing, null);
            return null;
        });
        ctx.ok(Map.of("deleted", true));
    }

    private static String parentColumn(String childTable) {
        return switch (childTable) {
            case "customer_vehicles", "customer_followups" -> "customer_id";
            case "tank_calibration_points" -> "tank_id";
            default -> "parent_id";
        };
    }

    private static Object valueOf(String field, Object value) {
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        if (field.endsWith("Litres") || field.endsWith("Limit") || field.endsWith("Balance") || "rate".equals(field)) return String.valueOf(value);
        if (value instanceof String s && s.isBlank() && List.of("email", "gstin", "phone", "address", "contactPerson", "calibrationReference", "model", "protocol").contains(field)) return null;
        return value;
    }

    private static String snake(String field) {
        return field.replaceAll("([A-Z])", "_$1").toLowerCase();
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

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) if (item != null) out.add(String.valueOf(item));
        return out;
    }
}
