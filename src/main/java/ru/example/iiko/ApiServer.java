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
 * HTTP-шлюз к iikoCloud API с двумя эндпоинтами:
 *
 * <p><b>POST /api/token</b> - получить токен доступа.<br>
 * Тело запроса: {@code {"apiKey": "...", "appId": "...", "clientSecret": "..."}}
 * ({@code apiLogin} принимается как синоним {@code apiKey} - это тот же параметр,
 * что в личном кабинете iikoWeb называется apiLogin). {@code appId}/{@code clientSecret}
 * обязательны для ключей новой схемы авторизации iiko (см. README), для старых ключей их
 * можно не передавать. Приложение обращается к {@code /api/v2/access_token} и возвращает
 * ответ iiko как есть (поля {@code token}, {@code correlationId}).
 *
 * <p><b>POST /api/nomenclature</b> - получить номенклатуру.<br>
 * Тело запроса: {@code {"token": "...", "organizationId": "...", "terminalId": "..."}}
 * ({@code terminalId} необязателен). Приложение обращается к {@code /api/1/nomenclature}
 * с заголовком {@code Authorization: Bearer <token>} и возвращает JSON-ответ iiko как есть.
 *
 * <p>Оба эндпоинта не хранят и не кэшируют ничего между запросами: токен и учётные данные
 * вызывающая сторона передаёт каждый раз сама.
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
        server.createContext("/api/token", this::handleToken);
        server.createContext("/api/nomenclature", this::handleNomenclature);
        server.createContext("/health", ex -> respond(ex, 200, om.createObjectNode().put("status", "ok")));
        server.setExecutor(Executors.newCachedThreadPool());
    }

    private void handleToken(HttpExchange ex) throws IOException {
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

        if (apiLogin == null) {
            respondError(ex, 400, "Укажите apiKey (он же apiLogin из iikoWeb)");
            return;
        }

        try {
            JsonNode iikoResp = iiko.fetchTokenV2(apiLogin, appId, clientSecret);
            respond(ex, 200, iikoResp);
        } catch (IikoGatewayClient.UpstreamException e) {
            respondUpstreamError(ex, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondError(ex, 503, "Запрос к iiko прерван, попробуйте ещё раз");
        } catch (Exception e) {
            respondError(ex, 502, "Не удалось связаться с iiko: " + e.getMessage());
        }
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

        String token = text(req, "token");
        String organizationId = text(req, "organizationId");
        String terminalId = text(req, "terminalId");

        if (token == null) {
            respondError(ex, 400, "Укажите token (полученный от /api/token)");
            return;
        }
        if (organizationId == null) {
            respondError(ex, 400, "Укажите organizationId");
            return;
        }

        try {
            JsonNode iikoResp = iiko.fetchNomenclature(token, organizationId, terminalId);
            respond(ex, 200, iikoResp);
        } catch (IikoGatewayClient.UpstreamException e) {
            respondUpstreamError(ex, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondError(ex, 503, "Запрос к iiko прерван, попробуйте ещё раз");
        } catch (Exception e) {
            respondError(ex, 502, "Не удалось связаться с iiko: " + e.getMessage());
        }
    }

    /** Пробрасываем ошибку iiko вызывающей стороне: 401/403 как есть, остальное - 502. */
    private void respondUpstreamError(HttpExchange ex, IikoGatewayClient.UpstreamException e) throws IOException {
        int status = (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 400) ? e.statusCode : 502;
        ObjectNode body = om.createObjectNode();
        body.put("error", "iiko вернул ошибку (HTTP " + e.statusCode + ")");
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
