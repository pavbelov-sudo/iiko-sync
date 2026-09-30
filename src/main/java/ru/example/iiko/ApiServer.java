package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * HTTP-шлюз к iikoCloud API с одним эндпоинтом:
 *
 * <p><b>POST /api/nomenclature</b> - получить номенклатуру по учётным данным.<br>
 * Тело запроса: {@code {"apiKey": "...", "appId": "...", "clientSecret": "...",
 * "organizationId": "...", "terminalId": "..."}}
 * ({@code apiLogin} принимается как синоним {@code apiKey}; {@code terminalId} необязателен).
 *
 * <p>Приложение само, последовательно:
 * <ol>
 *   <li>запрашивает токен через {@code POST /api/v2/access_token} (см. {@link IikoGatewayClient#fetchTokenV2});</li>
 *   <li>полученным токеном запрашивает номенклатуру через {@code POST /api/1/nomenclature}
 *       (см. {@link IikoGatewayClient#fetchNomenclature});</li>
 *   <li>отдаёт вызывающей стороне либо номенклатуру, либо содержимое ошибки от того из двух
 *       методов iiko, который её вернул (см. {@link #respondUpstreamError}).</li>
 * </ol>
 *
 * <p>Токен не кэшируется - на каждый вызов запрашивается заново (срок жизни токена iiko - 1 час,
 * см. README). Ничего не хранится и не кэшируется между запросами: все учётные данные вызывающая
 * сторона передаёт каждый раз сама.
 */
public class ApiServer {
    private final ObjectMapper om = new ObjectMapper();
    private final IikoGatewayClient iiko = new IikoGatewayClient();

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        registerContexts(server);
        server.start();
        return server;
    }

    /**
     * Поднимает тот же шлюз по HTTPS. Ключ и сертификат берутся из PKCS12-хранилища
     * (см. README - раздел про TLS): самоподписанный сертификат для быстрого запуска,
     * либо настоящий сертификат (например, от Let's Encrypt), сконвертированный в PKCS12.
     */
    public HttpServer startHttps(int port, String keystorePath, char[] keystorePassword) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, keystorePassword);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        registerContexts(server);
        server.start();
        return server;
    }

    private void registerContexts(HttpServer server) {
        server.createContext("/api/nomenclature", this::handleNomenclature);
        server.createContext("/health", ex -> respond(ex, 200, om.createObjectNode().put("status", "ok")));
        server.setExecutor(Executors.newCachedThreadPool());
    }

    private void handleNomenclature(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondError(ex, 405, "Только POST");
            return;
        }
        JsonNode req;
        try {
            req = om.readTree(ex.getRequestBody());
        } catch (Exception e) {
            respondError(ex, 400, "Тело запроса должно быть JSON-объектом");
            return;
        }

        // apiKey - удобное имя для внешнего API; iiko называет этот же параметр apiLogin.
        String apiLogin = firstNonBlank(text(req, "apiKey"), text(req, "apiLogin"));
        String appId = text(req, "appId");
        String clientSecret = text(req, "clientSecret");
        String organizationId = text(req, "organizationId");
        String terminalId = text(req, "terminalId");

        if (apiLogin == null) {
            respondError(ex, 400, "Укажите apiKey (он же apiLogin из iikoWeb)");
            return;
        }
        if (organizationId == null) {
            respondError(ex, 400, "Укажите organizationId");
            return;
        }

        String token;
        try {
            JsonNode tokenResp = iiko.fetchTokenV2(apiLogin, appId, clientSecret);
            token = tokenResp.path("token").asText(null);
            if (token == null || token.isBlank()) {
                // iiko вернул 2xx, но без поля token - отдаём этот ответ как есть, чтобы не потерять контекст.
                respondError(ex, 502, "/api/v2/access_token не вернул поле token: " + tokenResp);
                return;
            }
        } catch (IikoGatewayClient.UpstreamException e) {
            respondUpstreamError(ex, "/api/v2/access_token", e);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondError(ex, 503, "Запрос токена к iiko прерван, попробуйте ещё раз");
            return;
        } catch (Exception e) {
            respondError(ex, 502, "Не удалось получить токен от iiko: " + e.getMessage());
            return;
        }

        try {
            JsonNode iikoResp = iiko.fetchNomenclature(token, organizationId, terminalId);
            respond(ex, 200, iikoResp);
        } catch (IikoGatewayClient.UpstreamException e) {
            respondUpstreamError(ex, "/api/1/nomenclature", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondError(ex, 503, "Запрос номенклатуры к iiko прерван, попробуйте ещё раз");
        } catch (Exception e) {
            respondError(ex, 502, "Не удалось получить номенклатуру от iiko: " + e.getMessage());
        }
    }

    /** Пробрасываем ошибку iiko вызывающей стороне вместе с тем, какой из двух методов её вернул. */
    private void respondUpstreamError(HttpExchange ex, String failedStep, IikoGatewayClient.UpstreamException e) throws IOException {
        int status = (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 400) ? e.statusCode : 502;
        ObjectNode body = om.createObjectNode();
        body.put("error", "iiko вернул ошибку (HTTP " + e.statusCode + ") на шаге " + failedStep);
        body.put("failedStep", failedStep);
        try {
            body.set("iikoResponse", om.readTree(e.responseBody));
        } catch (Exception parseFailed) {
            body.put("iikoResponse", e.responseBody);
        }
        respond(ex, status, body);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v != null && !v.isNull() && !v.asText().isBlank()) ? v.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private void respondError(HttpExchange ex, int status, String message) throws IOException {
        respond(ex, status, om.createObjectNode().put("error", message));
    }

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] json = om.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, json.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(json);
        }
    }
}
