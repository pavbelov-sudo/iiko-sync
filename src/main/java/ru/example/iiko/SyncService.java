package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Оркестрация синхронизации. Каждый шаг изолирован: сбой одного не останавливает остальные. */
public class SyncService {

    private interface Step { void run() throws Exception; }

    private final IikoClient api;
    private final Db db;

    public SyncService(IikoClient api, Db db) {
        this.api = api;
        this.db = db;
    }

    public void runAll() throws Exception {
        JsonNode orgs = api.post("/api/1/organizations",
                Map.of("returnAdditionalInfo", false, "includeDisabled", false)).path("organizations");
        db.saveOrganizations(orgs);

        List<String> orgIds = new ArrayList<>();
        orgs.forEach(o -> orgIds.add(o.path("id").asText()));
        System.out.println("Организаций: " + orgIds.size());

        step("Номенклатура", () -> syncNomenclature(orgIds));
        step("Стоп-листы", () -> syncStopLists(orgIds));
        step("Внешние меню", () -> syncMenus(orgIds));
    }

    /** /api/1/nomenclature - по каждой организации, с инкрементом по revision. */
    private void syncNomenclature(List<String> orgIds) throws Exception {
        for (String orgId : orgIds) {
            String key = "nomenclature.revision." + orgId;
            long rev = Long.parseLong(db.getState(key) == null ? "0" : db.getState(key));

            JsonNode r = api.post("/api/1/nomenclature",
                    Map.of("organizationId", orgId, "startRevision", rev));
            db.saveNomenclature(orgId, r);
            db.setState(key, String.valueOf(r.path("revision").asLong(rev)));

            System.out.printf("  [%s] групп: %d, товаров: %d, revision: %d%n", orgId,
                    r.path("groups").size(), r.path("products").size(), r.path("revision").asLong());
        }
    }

    /** /api/1/stop_lists - одним запросом на все организации; в ответе balance = остаток. */
    private void syncStopLists(List<String> orgIds) throws Exception {
        JsonNode r = api.post("/api/1/stop_lists", Map.of("organizationIds", orgIds));
        db.replaceStopLists(r);
        int n = 0;
        for (JsonNode org : r.path("terminalGroupStopLists"))
            for (JsonNode tg : org.path("items"))
                n += tg.path("items").size();
        System.out.println("  позиций в стоп-листах: " + n);
    }

    /** /api/2/menu (список) -> /api/2/menu/by_id (содержимое каждого меню). */
    private void syncMenus(List<String> orgIds) throws Exception {
        JsonNode list = api.post("/api/2/menu", Map.of());
        db.saveMenuList(list);

        for (JsonNode m : list.path("externalMenus")) {
            String menuId = m.path("id").asText();
            JsonNode r = api.post("/api/2/menu/by_id",
                    Map.of("externalMenuId", menuId, "organizationIds", orgIds));
            db.replaceMenu(menuId, r);
            System.out.printf("  меню %s (%s): категорий %d%n",
                    menuId, m.path("name").asText(), r.path("itemCategories").size());
        }
    }

    private void step(String name, Step s) {
        System.out.println("== " + name);
        try {
            s.run();
        } catch (Exception e) {
            System.err.println("!! " + name + ": " + e.getMessage());
        }
    }
}
