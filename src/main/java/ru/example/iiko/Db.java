package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.*;
import java.time.Instant;

/** Локальное хранилище на SQLite. */
public class Db implements AutoCloseable {

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS sync_state(key TEXT PRIMARY KEY, value TEXT)",

        "CREATE TABLE IF NOT EXISTS organizations(id TEXT PRIMARY KEY, name TEXT)",

        "CREATE TABLE IF NOT EXISTS product_groups(" +
            "organization_id TEXT, id TEXT, name TEXT, parent_group TEXT, is_deleted INTEGER, " +
            "PRIMARY KEY(organization_id, id))",

        "CREATE TABLE IF NOT EXISTS products(" +
            "organization_id TEXT, id TEXT, code TEXT, name TEXT, description TEXT, type TEXT, " +
            "group_id TEXT, category_id TEXT, measure_unit TEXT, weight REAL, price REAL, " +
            "is_deleted INTEGER, raw TEXT, updated_at TEXT, " +
            "PRIMARY KEY(organization_id, id))",

        // balance - остаток; 0 или отсутствие остатка = позиция в стоп-листе
        "CREATE TABLE IF NOT EXISTS stop_list(" +
            "organization_id TEXT, terminal_group_id TEXT, product_id TEXT, size_id TEXT, " +
            "balance REAL, sku TEXT, date_add TEXT, synced_at TEXT, " +
            "PRIMARY KEY(organization_id, terminal_group_id, product_id, size_id))",

        "CREATE TABLE IF NOT EXISTS external_menus(id TEXT PRIMARY KEY, name TEXT)",
        "CREATE TABLE IF NOT EXISTS price_categories(id TEXT PRIMARY KEY, name TEXT)",
        "CREATE TABLE IF NOT EXISTS menu_raw(menu_id TEXT PRIMARY KEY, raw TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS menu_items(" +
            "menu_id TEXT, organization_id TEXT, item_id TEXT, size_id TEXT, sku TEXT, name TEXT, " +
            "category_id TEXT, category_name TEXT, price REAL, " +
            "PRIMARY KEY(menu_id, organization_id, item_id, size_id))"
    };

    private interface Work { void run() throws SQLException; }

    private final Connection c;

    public Db(String path) throws SQLException {
        c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            for (String ddl : SCHEMA) s.execute(ddl);
        }
    }

    // ---------- служебное состояние (revision для номенклатуры) ----------

    public String getState(String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM sync_state WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setState(String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sync_state(key,value) VALUES(?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ---------- организации ----------

    public void saveOrganizations(JsonNode orgs) throws SQLException {
        tx(() -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO organizations(id,name) VALUES(?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name")) {
                for (JsonNode o : orgs) {
                    ps.setString(1, o.path("id").asText());
                    ps.setString(2, txt(o, "name"));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    // ---------- номенклатура ----------

    public void saveNomenclature(String orgId, JsonNode r) throws SQLException {
        String now = Instant.now().toString();
        tx(() -> {
            try (PreparedStatement g = c.prepareStatement(
                    "INSERT INTO product_groups(organization_id,id,name,parent_group,is_deleted) " +
                    "VALUES(?,?,?,?,?) ON CONFLICT(organization_id,id) DO UPDATE SET " +
                    "name=excluded.name, parent_group=excluded.parent_group, is_deleted=excluded.is_deleted")) {
                for (JsonNode n : r.path("groups")) {
                    g.setString(1, orgId);
                    g.setString(2, n.path("id").asText());
                    g.setString(3, txt(n, "name"));
                    g.setString(4, txt(n, "parentGroup"));
                    g.setInt(5, n.path("isDeleted").asBoolean(false) ? 1 : 0);
                    g.addBatch();
                }
                g.executeBatch();
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO products(organization_id,id,code,name,description,type,group_id,category_id," +
                    "measure_unit,weight,price,is_deleted,raw,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(organization_id,id) DO UPDATE SET code=excluded.code, name=excluded.name, " +
                    "description=excluded.description, type=excluded.type, group_id=excluded.group_id, " +
                    "category_id=excluded.category_id, measure_unit=excluded.measure_unit, " +
                    "weight=excluded.weight, price=excluded.price, is_deleted=excluded.is_deleted, " +
                    "raw=excluded.raw, updated_at=excluded.updated_at")) {
                for (JsonNode n : r.path("products")) {
                    JsonNode price = n.path("sizePrices").path(0).path("price").path("currentPrice");
                    p.setString(1, orgId);
                    p.setString(2, n.path("id").asText());
                    p.setString(3, txt(n, "code"));
                    p.setString(4, txt(n, "name"));
                    p.setString(5, txt(n, "description"));
                    p.setString(6, txt(n, "type"));
                    p.setString(7, txt(n, "groupId"));
                    p.setString(8, txt(n, "productCategoryId"));
                    p.setString(9, txt(n, "measureUnit"));
                    p.setObject(10, n.hasNonNull("weight") ? n.get("weight").asDouble() : null);
                    p.setObject(11, price.isNumber() ? price.asDouble() : null);
                    p.setInt(12, n.path("isDeleted").asBoolean(false) ? 1 : 0);
                    p.setString(13, n.toString());
                    p.setString(14, now);
                    p.addBatch();
                }
                p.executeBatch();
            }
        });
    }

    // ---------- стоп-листы и остатки ----------

    /** Стоп-лист - это снимок состояния: старые строки удаляются, пишутся актуальные. */
    public void replaceStopLists(JsonNode r) throws SQLException {
        String now = Instant.now().toString();
        tx(() -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM stop_list");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO stop_list(organization_id,terminal_group_id,product_id,size_id," +
                    "balance,sku,date_add,synced_at) VALUES(?,?,?,?,?,?,?,?)")) {
                for (JsonNode org : r.path("terminalGroupStopLists")) {
                    String orgId = org.path("organizationId").asText();
                    for (JsonNode tg : org.path("items")) {
                        String tgId = tg.path("terminalGroupId").asText();
                        for (JsonNode it : tg.path("items")) {
                            ps.setString(1, orgId);
                            ps.setString(2, tgId);
                            ps.setString(3, it.path("productId").asText());
                            ps.setString(4, it.hasNonNull("sizeId") ? it.get("sizeId").asText() : "");
                            ps.setObject(5, it.hasNonNull("balance") ? it.get("balance").asDouble() : null);
                            ps.setString(6, txt(it, "sku"));
                            ps.setString(7, txt(it, "dateAdd"));
                            ps.setString(8, now);
                            ps.addBatch();
                        }
                    }
                }
                ps.executeBatch();
            }
        });
    }

    // ---------- внешние меню ----------

    public void saveMenuList(JsonNode r) throws SQLException {
        tx(() -> {
            try (PreparedStatement m = c.prepareStatement(
                    "INSERT INTO external_menus(id,name) VALUES(?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name");
                 PreparedStatement pc = c.prepareStatement(
                    "INSERT INTO price_categories(id,name) VALUES(?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name")) {
                for (JsonNode n : r.path("externalMenus")) {
                    m.setString(1, n.path("id").asText());
                    m.setString(2, txt(n, "name"));
                    m.addBatch();
                }
                m.executeBatch();
                for (JsonNode n : r.path("priceCategories")) {
                    pc.setString(1, n.path("id").asText());
                    pc.setString(2, txt(n, "name"));
                    pc.addBatch();
                }
                pc.executeBatch();
            }
        });
    }

    /** Полностью заменяет содержимое одного меню (строка на позицию x размер x организацию). */
    public void replaceMenu(String menuId, JsonNode r) throws SQLException {
        String now = Instant.now().toString();
        tx(() -> {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM menu_items WHERE menu_id=?")) {
                d.setString(1, menuId);
                d.executeUpdate();
            }
            try (PreparedStatement raw = c.prepareStatement(
                    "INSERT INTO menu_raw(menu_id,raw,updated_at) VALUES(?,?,?) " +
                    "ON CONFLICT(menu_id) DO UPDATE SET raw=excluded.raw, updated_at=excluded.updated_at")) {
                raw.setString(1, menuId);
                raw.setString(2, r.toString());
                raw.setString(3, now);
                raw.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO menu_items(menu_id,organization_id,item_id,size_id,sku,name," +
                    "category_id,category_name,price) VALUES(?,?,?,?,?,?,?,?,?)")) {
                for (JsonNode cat : r.path("itemCategories")) {
                    for (JsonNode item : cat.path("items")) {
                        for (JsonNode size : item.path("itemSizes")) {
                            for (JsonNode pr : size.path("prices")) {
                                ps.setString(1, menuId);
                                ps.setString(2, pr.hasNonNull("organizationId") ? pr.get("organizationId").asText() : "");
                                ps.setString(3, item.path("itemId").asText());
                                ps.setString(4, size.hasNonNull("sizeId") ? size.get("sizeId").asText() : "");
                                ps.setString(5, txt(item, "sku"));
                                ps.setString(6, txt(item, "name"));
                                ps.setString(7, txt(cat, "id"));
                                ps.setString(8, txt(cat, "name"));
                                ps.setObject(9, pr.hasNonNull("price") ? pr.get("price").asDouble() : null);
                                ps.addBatch();
                            }
                        }
                    }
                }
                ps.executeBatch();
            }
        });
    }

    // ---------- helpers ----------

    private static String txt(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private void tx(Work w) throws SQLException {
        c.setAutoCommit(false);
        try {
            w.run();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws SQLException {
        c.close();
    }
}
