package ru.example.iiko;

import com.sun.net.httpserver.HttpServer;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Точка входа. Режим выбирается переменной окружения MODE:
 *
 * <p><b>MODE=api</b> (по умолчанию) - поднимает HTTP-шлюз с одним эндпоинтом
 * (см. {@link ApiServer}): POST /api/nomenclature. Вызывающая сторона передаёт
 * apiKey/appId/clientSecret и organizationId/terminalId, а приложение само
 * получает токен от iiko и запрашивает номенклатуру - ничего не хранит между запросами.
 *   PORT                       - порт сервера (по умолчанию 8080 для HTTP, 8443 для HTTPS)
 *   TLS_ENABLED                - true, чтобы поднять HTTPS вместо HTTP (по умолчанию false)
 *   TLS_KEYSTORE               - путь к PKCS12-хранилищу с сертификатом (обязательно при TLS_ENABLED=true)
 *   TLS_KEYSTORE_PASSWORD      - пароль хранилища (обязательно при TLS_ENABLED=true)
 *   TLS_CLIENT_AUTH_ENABLED    - true, чтобы требовать клиентский сертификат (mTLS; требует TLS_ENABLED=true)
 *   TLS_CLIENT_TRUSTSTORE      - PKCS12-хранилище с доверенным CA/сертификатами клиентов (обязательно при TLS_CLIENT_AUTH_ENABLED=true)
 *   TLS_CLIENT_TRUSTSTORE_PASSWORD - пароль этого хранилища (обязательно при TLS_CLIENT_AUTH_ENABLED=true)
 *   TLS_CLIENT_ALLOWED_CNS_FILE - путь к текстовому файлу со списком разрешённых CommonName
 *                                 сертификата клиента, по одному на строку (строки с # и пустые
 *                                 игнорируются); обязателен при TLS_CLIENT_AUTH_ENABLED=true.
 *                                 Файл можно редактировать прямо на сервере - изменения
 *                                 подхватываются на лету, без перезапуска приложения (см. {@link ClientCnAllowlist})
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
        // Консоль может быть настроена не на UTF-8 (напр. systemd/некоторые терминалы) -
        // явно переопределяем кодировку stdout/stderr, чтобы кириллица в логах не превращалась в "?".
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

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
        boolean tls = Boolean.parseBoolean(System.getenv().getOrDefault("TLS_ENABLED", "false"));
        ApiServer api = new ApiServer();
        HttpServer server;
        String scheme;

        boolean clientAuth = Boolean.parseBoolean(System.getenv().getOrDefault("TLS_CLIENT_AUTH_ENABLED", "false"));
        if (clientAuth && !tls) {
            System.err.println("TLS_CLIENT_AUTH_ENABLED=true требует также TLS_ENABLED=true (mTLS работает только поверх HTTPS)");
            System.exit(1);
            return;
        }

        if (tls) {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8443"));
            String keystorePath = System.getenv("TLS_KEYSTORE");
            String keystorePassword = System.getenv("TLS_KEYSTORE_PASSWORD");
            if (keystorePath == null || keystorePath.isBlank()) {
                System.err.println("TLS_ENABLED=true, но не задан TLS_KEYSTORE (путь к PKCS12-хранилищу)");
                System.exit(1);
                return;
            }
            if (keystorePassword == null) {
                System.err.println("TLS_ENABLED=true, но не задан TLS_KEYSTORE_PASSWORD");
                System.exit(1);
                return;
            }

            ApiServer.ClientAuthConfig clientAuthConfig = null;
            if (clientAuth) {
                String truststorePath = System.getenv("TLS_CLIENT_TRUSTSTORE");
                String truststorePassword = System.getenv("TLS_CLIENT_TRUSTSTORE_PASSWORD");
                String allowedCnsFile = System.getenv("TLS_CLIENT_ALLOWED_CNS_FILE");
                if (truststorePath == null || truststorePath.isBlank()) {
                    System.err.println("TLS_CLIENT_AUTH_ENABLED=true, но не задан TLS_CLIENT_TRUSTSTORE");
                    System.exit(1);
                    return;
                }
                if (truststorePassword == null) {
                    System.err.println("TLS_CLIENT_AUTH_ENABLED=true, но не задан TLS_CLIENT_TRUSTSTORE_PASSWORD");
                    System.exit(1);
                    return;
                }
                if (allowedCnsFile == null || allowedCnsFile.isBlank()) {
                    System.err.println("TLS_CLIENT_AUTH_ENABLED=true, но не задан TLS_CLIENT_ALLOWED_CNS_FILE (путь к файлу со списком CN)");
                    System.exit(1);
                    return;
                }
                Path cnsPath = Path.of(allowedCnsFile);
                if (!Files.isReadable(cnsPath)) {
                    System.err.println("TLS_CLIENT_ALLOWED_CNS_FILE=" + allowedCnsFile + " не найден или недоступен для чтения");
                    System.exit(1);
                    return;
                }
                ClientCnAllowlist allowedCns;
                try {
                    allowedCns = ClientCnAllowlist.loadInitial(cnsPath);
                } catch (Exception e) {
                    System.err.println("Не удалось загрузить TLS_CLIENT_ALLOWED_CNS_FILE=" + allowedCnsFile + ": " + e.getMessage());
                    System.exit(1);
                    return;
                }
                clientAuthConfig = new ApiServer.ClientAuthConfig(
                        truststorePath, truststorePassword.toCharArray(), allowedCns);
            }

            server = api.startHttps(port, keystorePath, keystorePassword.toCharArray(), clientAuthConfig);
            scheme = clientAuth ? "https (mTLS, проверка CN сертификата клиента)" : "https";
        } else {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            server = api.start(port);
            scheme = "http";
        }

        System.out.println("iiko-sync API слушает по " + scheme + " на порту " +
                (tls ? System.getenv().getOrDefault("PORT", "8443") : System.getenv().getOrDefault("PORT", "8080")) +
                " (POST /api/nomenclature, GET /health)");
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
