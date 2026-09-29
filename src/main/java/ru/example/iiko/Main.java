package ru.example.iiko;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Настройки через переменные окружения:
 *   IIKO_API_LOGIN      - apiLogin из iikoWeb (обязательно)
 *   IIKO_APP_ID         - appId из портала разработчика iiko (нужен для ключей новой схемы,
 *                         которые требуют /api/v2/access_token; для старых ключей не задавайте)
 *   IIKO_CLIENT_SECRET  - clientSecret из портала разработчика iiko (пара к IIKO_APP_ID)
 *   IIKO_DB             - путь к файлу SQLite (по умолчанию iiko.db)
 *   SYNC_INTERVAL_MIN   - если задано, синхронизация повторяется каждые N минут; иначе один запуск
 */
public class Main {
    public static void main(String[] args) throws Exception {
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
