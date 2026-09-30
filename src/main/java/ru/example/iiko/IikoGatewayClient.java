package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Без-состояния (stateless) клиент к iikoCloud API для режима шлюза (см. {@link ApiServer}):
 * каждый вызов получает учётные данные / токен от вызывающей стороны, ничего не кэширует
 * и не хранит между запросами - в отличие от {@link IikoClient}, который используется
 * пакетным режимом синхронизации (см. {@link SyncService}) и сам управляет токеном.
 */
public class IikoGatewayClient {
    private static final String BASE = "https://api-ru.iiko.services";
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper om = new ObjectMapper();

    /**
     * Получить токен через /api/v2/access_token по apiLogin/appId/clientSecret,
     * переданным вызывающей стороной.
     */
    public JsonNode fetchTokenV2(String apiLogin, String appId, String clientSecret)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiLogin", apiLogin);
        if (appId != null && !appId.isBlank()) body.put("appId", appId);
        if (clientSecret != null && !clientSecret.isBlank()) body.put("clientSecret", clientSecret);
        return post("/api/v2/access_token", body, null);
    }

    /**
     * Получить номенклатуру через /api/1/nomenclature по готовому токену.
     *
     * <p>Официальная схема запроса iiko принимает {@code organizationId} (обязательно) и
     * {@code startRevision} (для инкрементальной выгрузки). Параметр {@code terminalId}
     * официальной документацией iikoCloud для этого метода не описан - мы передаём его
     * в теле запроса как {@code terminalGroupId} на случай, если он всё же учитывается
     * (например, для фильтрации по терминальной группе), но если iiko его игнорирует,
     * поведение не изменится: в ответ придёт полная номенклатура организации.
     */
    public JsonNode fetchNomenclature(String token, String organizationId, String terminalId)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId);
        if (terminalId != null && !terminalId.isBlank()) {
            body.put("terminalGroupId", terminalId);
        }
        return post("/api/1/nomenclature", body, token);
    }

    private JsonNode post(String path, Object body, String bearerToken) throws IOException, InterruptedException {
        String json = om.writeValueAsString(body);
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (bearerToken != null) {
                b.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();

            if (code / 100 == 2) {
                return om.readTree(resp.body());
            }
            if (attempt >= MAX_ATTEMPTS || !(code == 429 || code >= 500)) {
                throw new UpstreamException(code, resp.body());
            }
            Thread.sleep(1000L * attempt * attempt); // мягкий backoff на 429/5xx
        }
    }

    /** Ошибка, пришедшая от самого iiko (не сетевая) - несёт исходный HTTP-статус и тело ответа. */
    public static class UpstreamException extends IOException {
        public final int statusCode;
        public final String responseBody;

        public UpstreamException(int statusCode, String responseBody) {
            super("iiko HTTP " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}
