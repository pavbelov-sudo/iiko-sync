package ru.example.iiko;

import com.sun.net.httpserver.HttpServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Точка входа. Режим выбирается переменной окружения MODE:
 *
 * <p><b>MODE=api</b> (по умолчанию) - поднимает HTTP-шлюз с двумя эндпоинтами
 * (см. {@link ApiServer}): POST /api/token и POST /api/nomenclature. Учётные данные
 * и токен передаются в каждом запросе вызывающей стороной, приложение ничего
 * не хранит между запросами.
 *   PORT - порт HTTP-сервера (по умолчанию 8080)
 *
 * <p><b>MODE=sync</b> - прежний пакетный режим: сам получает и обновляет токен,
 * периодически выгружает номенклатуру/стоп-листы/меню в локальный SQLite (см. {@link SyncService}).
 *   IIKO_API_LOGIN      - apiLogin из iikoWeb (обязательно)
 *   IIKO_APP_ID         - appId из портала разработчика iiko (для ключей новой схемы)
 *   IIKO_CLIENT_SECRET  - clientSecret из портала разработчика iiko (пара к IIKO_APP_ID)
 *   IIKO_DB             - путь к файлу SQLite (по умолчанию iiko.db)
 *   SYNC_INTERVAL_MIN   - если задано, синхронизация повторяется каждые N минут; иначе один запуск
 */
public class Main {
    public static void main(String[] args) throws Exception {
        String mode = System.getenv().getOrDefault("MODE", "api").trim().toLowerCase();
        switch (mode) {
            case "api":
                runApiServer();
                break;
            case "sync":
                runSync();
                break;
            default:
                System.err.println("Неизвестный MODE=" + mode + " (ожидается api или sync)");
                System.exit(1);
        }
    }

    private static void runApiServer() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = new ApiServer().start(port);
        System.out.println("iiko-sync API слушает на порту " + port +
                " (POST /api/token, POST /api/nomenclature, GET /health)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        Thread.currentThread().join(); // держим процесс живым
    }

    private static void runSync() throws Exception {
        String apiLogin = System.getenv("IIKO_API_LOGIN");
        if (apiLogin == null || apiLogin.isBlank()) {
            System.err.println("Задайте переменную окружения IIKO_API_LOGIN");
            System.exit(1);
        }
        String appId = System.getenv("IIKO_APP_ID");
        String clientSecret = System.getenv("IIKO_CLIENT_SECRET");
        String dbPath = System.getenv().getOrDefault("IIKO_DB", "iiko.db");
        String interval = System.getenv("SYNC_INTERVAL_MIN");

        Db db = new Db(dbPath);
        SyncService sync = new SyncService(new IikoClient(apiLogin, appId, clientSecret), db);

        if (interval == null || interval.isBlank()) {
            sync.runAll();
            db.close();
            return;
        }

        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        ses.scheduleWithFixedDelay(() -> {
            try {
                sync.runAll();
            } catch (Exception e) {
                System.err.println("Ошибка синхронизации: " + e.getMessage());
            }
        }, 0, Long.parseLong(interval), TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ses.shutdown();
            try { db.close(); } catch (Exception ignored) { }
        }));
    }
}
