package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный клиент iikoCloud API: получает и обновляет токен, делает POST-запросы с повторами.
 *
 * <p>С 1 июня 2026 iiko переводит ключи на новую схему авторизации: часть ключей отвечает
 * "This API key does not support /api/1/access_token. Please use /api/v2/access_token instead",
 * и требует помимо apiLogin ещё appId и clientSecret (выдаются в портале разработчика iiko,
 * отдельно от apiLogin из iikoWeb). Старые ключи по-прежнему работают через /api/1/access_token
 * с одним apiLogin.
 *
 * <p>Этот клиент не требует знать заранее, какая схема нужна конкретному ключу: он пробует
 * подходящий по вашей конфигурации эндпоинт первым, а при отказе - второй, и запоминает,
 * какой сработал, чтобы не пробовать оба при каждом обновлении токена.
 */
public class IikoClient {
    private static final String BASE = "https://api-ru.iiko.services";
    private static final String AUTH_PATH_V1 = "/api/1/access_token";
    private static final String AUTH_PATH_V2 = "/api/v2/access_token";
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper om = new ObjectMapper();

    private final String apiLogin;
    private final String appId;
    private final String clientSecret;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;
    /** Эндпоинт авторизации, который сработал в прошлый раз; null - ещё не определён. */
    private volatile String workingAuthPath;

    /** Старая схема: только apiLogin (ключи, выданные до перехода на v2). */
    public IikoClient(String apiLogin) {
        this(apiLogin, null, null);
    }

    /**
     * Новая схема: apiLogin + appId + clientSecret (обязательны для ключей "электронного меню"
     * и других, выданных после введения /api/v2/access_token). appId и clientSecret можно
     * передать как null, если ключ работает по старой схеме - тогда используется только v1.
     */
    public IikoClient(String apiLogin, String appId, String clientSecret) {
        this.apiLogin = apiLogin;
        this.appId = appId;
        this.clientSecret = clientSecret;
    }

    public JsonNode post(String path, Object body) throws IOException, InterruptedException {
        String json = om.writeValueAsString(body);
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            b.header("Authorization", "Bearer " + token());

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();

            if (code / 100 == 2) {
                return om.readTree(resp.body());
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new IOException("iiko " + path + " -> HTTP " + code + ": " + resp.body());
            }
            if (code == 401) {
                invalidateToken();                       // токен протух - получим новый
            } else if (code == 429 || code >= 500) {
                Thread.sleep(1000L * attempt * attempt); // мягкий backoff
            } else {
                throw new IOException("iiko " + path + " -> HTTP " + code + ": " + resp.body());
            }
        }
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }

        // Порядок попыток: если заданы appId/clientSecret - сначала v2 (новая схема),
        // иначе сначала v1 (старая). Уже определённый рабочий путь пробуем первым всегда.
        boolean haveAppCreds = appId != null && !appId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
        List<String> order = haveAppCreds
                ? List.of(AUTH_PATH_V2, AUTH_PATH_V1)
                : List.of(AUTH_PATH_V1, AUTH_PATH_V2);
        if (workingAuthPath != null) {
            order = workingAuthPath.equals(order.get(0))
                    ? order
                    : List.of(workingAuthPath, order.get(0));
        }

        IOException lastError = null;
        for (String path : order) {
            try {
                JsonNode r = requestToken(path);
                String t = r.path("token").asText(null);
                if (t == null) {
                    lastError = new IOException("В ответе " + path + " нет поля token: " + r);
                    continue;
                }
                token = t;
                workingAuthPath = path;
                // Официальный срок жизни токена v2 - 1 час, у v1 такой же порядок величины.
                // Обновляем заранее, с запасом.
                tokenExpiresAt = Instant.now().plus(Duration.ofMinutes(45));
                return token;
            } catch (IOException e) {
                lastError = e;
                // пробуем следующий эндпоинт из order
            }
        }

        String hint = haveAppCreds
                ? ""
                : " Если ключ требует новую схему авторизации (ошибка содержит " +
                  "\"use /api/v2/access_token\"), задайте IIKO_APP_ID и IIKO_CLIENT_SECRET " +
                  "(получаются в портале разработчика iiko).";
        throw new IOException("Не удалось получить токен ни через " + AUTH_PATH_V1 +
                ", ни через " + AUTH_PATH_V2 + "." + hint, lastError);
    }

    /** Один запрос токена (с ретраями на 429/5xx), без обращения к token() - тут ещё нет токена. */
    private JsonNode requestToken(String path) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiLogin", apiLogin);
        if (AUTH_PATH_V2.equals(path)) {
            // v2 требует appId и clientSecret; если их нет - iiko вернёт понятную 400-ошибку,
            // которую мы прокинем наверх как IOException.
            if (appId != null && !appId.isBlank()) body.put("appId", appId);
            if (clientSecret != null && !clientSecret.isBlank()) body.put("clientSecret", clientSecret);
        }
        String json = om.writeValueAsString(body);

        for (int attempt = 1; ; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();

            if (code / 100 == 2) {
                return om.readTree(resp.body());
            }
            if (attempt >= MAX_ATTEMPTS || !(code == 429 || code >= 500)) {
                throw new IOException(path + " -> HTTP " + code + ": " + resp.body());
            }
            Thread.sleep(1000L * attempt * attempt);
        }
    }

    private synchronized void invalidateToken() {
        token = null;
    }
}
