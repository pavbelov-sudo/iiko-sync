package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Set;
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
 * <p>Токен кэшируется в памяти процесса (см. {@link TokenCache}) отдельно для каждого набора
 * входящих учётных данных (apiKey/appId/clientSecret) - разные ключи получают разные токены
 * и не мешают друг другу. Токен переиспользуется, пока не истёк (с запасом), поэтому при частых
 * вызовах с одним и тем же ключом лишний запрос к {@code /api/v2/access_token} на каждый вызов
 * не делается. Если iiko всё же отвечает 401 на уже закэшированный токен (например, ключ отозвали
 * или он истёк раньше срока), кэш для этого набора данных сбрасывается и токен запрашивается
 * заново один раз автоматически.
 */
public class ApiServer {
    private final ObjectMapper om = new ObjectMapper();
    private final IikoGatewayClient iiko = new IikoGatewayClient();
    private final TokenCache tokenCache = new TokenCache();
    private ClientAuthConfig clientAuthConfig; // не null => mTLS с проверкой CN обязателен

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        registerContexts(server);
        server.start();
        return server;
    }

    /**
     * Поднимает тот же шлюз по HTTPS без аутентификации клиента по сертификату. Ключ и
     * сертификат берутся из PKCS12-хранилища (см. README - раздел про TLS): самоподписанный
     * сертификат для быстрого запуска, либо настоящий сертификат (например, от Let's Encrypt),
     * сконвертированный в PKCS12.
     */
    public HttpServer startHttps(int port, String keystorePath, char[] keystorePassword) throws Exception {
        return startHttps(port, keystorePath, keystorePassword, null);
    }

    /**
     * Поднимает шлюз по HTTPS с обязательной mTLS-аутентификацией клиента по сертификату,
     * если {@code clientAuth} не null (см. README - раздел "Аутентификация клиента по
     * сертификату"). Сервер требует от клиента предъявить сертификат, подписанный CA из
     * {@code clientAuth.truststorePath}; клиент без валидного сертификата не сможет даже
     * установить TLS-соединение - это закрывает эндпоинт от анонимного трафика ещё до того,
     * как запрос доберётся до кода приложения. Дополнительно CommonName (CN) сертификата
     * клиента сверяется со списком {@code clientAuth.allowedCommonNames} - см. {@link #wrap}.
     */
    public HttpServer startHttps(int port, String keystorePath, char[] keystorePassword,
                                  ClientAuthConfig clientAuth) throws Exception {
        this.clientAuthConfig = clientAuth;

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, keystorePassword);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);

        TrustManagerFactory tmf;
        if (clientAuth != null) {
            // Доверяем только тому CA/сертификатам, что лежат в отдельном truststore для клиентов -
            // не собственному серверному keystore (иначе проверка клиентских сертификатов не имела бы смысла).
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(clientAuth.truststorePath)) {
                trustStore.load(fis, clientAuth.truststorePassword);
            }
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
        } else {
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters sslParams = getSSLContext().getDefaultSSLParameters();
                if (clientAuth != null) {
                    // Важно: выставлять needClientAuth нужно на самом объекте SSLParameters,
                    // а не (только) через params.setNeedClientAuth() - иначе следующий же вызов
                    // params.setSSLParameters(sslParams) применит sslParams целиком и затрёт
                    // ранее выставленный на HttpsParameters флаг обратно в false (проверено на практике).
                    sslParams.setNeedClientAuth(true);
                }
                params.setSSLParameters(sslParams);
            }
        });
        registerContexts(server);
        server.start();
        return server;
    }

    private void registerContexts(HttpServer server) {
        server.createContext("/api/nomenclature", wrap(this::handleNomenclature));
        server.createContext("/health", wrap(ex -> respond(ex, 200, om.createObjectNode().put("status", "ok"))));
        server.setExecutor(Executors.newCachedThreadPool());
    }

    /**
     * Гарантирует {@code HttpExchange.close()} на любом исходе обработчика (успех, ожидаемая
     * ошибка, необработанное исключение). Без явного close() ресурсы обмена (особенно под TLS,
     * где на соединение приходится больше буферов) не гарантированно освобождаются под нагрузкой -
     * см. Javadoc HttpExchange и README/инцидент с ростом памяти.
     *
     * <p>Если включена аутентификация по сертификату ({@link #clientAuthConfig} не null), здесь же,
     * до вызова самого обработчика, проверяется CommonName (CN) сертификата клиента против
     * разрешённого списка - применяется ко всем путям единообразно (TLS-рукопожатие уже требует
     * сертификат, подписанный доверенным CA, на уровне всего соединения, так что /health на
     * защищённом порту тоже требует сертификат).
     */
    private HttpHandler wrap(HttpHandler handler) {
        return ex -> {
            try {
                if (clientAuthConfig != null) {
                    String cn = extractClientCommonName(ex);
                    if (cn == null || !clientAuthConfig.allowedCommonNames.contains(cn)) {
                        respondError(ex, 403, "Сертификат клиента не авторизован"
                                + (cn != null ? " (CN=" + cn + ")" : " (CN не определён)"));
                        return;
                    }
                }
                handler.handle(ex);
            } catch (Exception e) {
                try {
                    respondError(ex, 500, "Внутренняя ошибка: " + e.getMessage());
                } catch (Exception ignored) {
                    // соединение могло быть уже повреждено - ответить не получится, просто закрываем
                }
            } finally {
                ex.close();
            }
        };
    }

    /** CommonName (CN) из сертификата клиента текущего TLS-соединения, либо null, если его нет/не удалось прочитать. */
    private static String extractClientCommonName(HttpExchange ex) {
        if (!(ex instanceof HttpsExchange)) {
            return null; // обычный HTTP (без TLS) - сертификата клиента в принципе быть не может
        }
        try {
            Certificate[] peerCerts = ((HttpsExchange) ex).getSSLSession().getPeerCertificates();
            if (peerCerts.length == 0 || !(peerCerts[0] instanceof X509Certificate)) {
                return null;
            }
            X509Certificate cert = (X509Certificate) peerCerts[0];
            LdapName dn = new LdapName(cert.getSubjectX500Principal().getName());
            for (Rdn rdn : dn.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
            return null;
        } catch (SSLPeerUnverifiedException e) {
            return null; // клиент не предъявил сертификат (при setNeedClientAuth(true) сюда дойти не должны)
        } catch (Exception e) {
            return null; // сертификат есть, но CN из DN извлечь не удалось - считаем неавторизованным
        }
    }

    /** Параметры mTLS: truststore с доверенным CA/сертификатами клиентов и разрешённые CN. */
    public static class ClientAuthConfig {
        final String truststorePath;
        final char[] truststorePassword;
        final Set<String> allowedCommonNames;

        public ClientAuthConfig(String truststorePath, char[] truststorePassword, Set<String> allowedCommonNames) {
            this.truststorePath = truststorePath;
            this.truststorePassword = truststorePassword;
            this.allowedCommonNames = allowedCommonNames;
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
            token = fetchTokenCached(apiLogin, appId, clientSecret);
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

        JsonNode iikoResp;
        try {
            iikoResp = iiko.fetchNomenclature(token, organizationId, terminalId);
        } catch (IikoGatewayClient.UpstreamException e) {
            if (e.statusCode != 401) {
                respondUpstreamError(ex, "/api/1/nomenclature", e);
                return;
            }
            // Закэшированный токен отклонён (истёк раньше срока/отозван) - сбрасываем кэш
            // для этого набора данных и пробуем один раз с заведомо свежим токеном.
            tokenCache.invalidate(apiLogin, appId, clientSecret);
            try {
                String freshToken = fetchTokenCached(apiLogin, appId, clientSecret);
                iikoResp = iiko.fetchNomenclature(freshToken, organizationId, terminalId);
            } catch (IikoGatewayClient.UpstreamException retryTokenErr) {
                respondUpstreamError(ex, "/api/v2/access_token", retryTokenErr);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                respondError(ex, 503, "Запрос к iiko прерван, попробуйте ещё раз");
                return;
            } catch (Exception retryErr) {
                respondError(ex, 502, "Не удалось повторно получить токен/номенклатуру от iiko: " + retryErr.getMessage());
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondError(ex, 503, "Запрос номенклатуры к iiko прерван, попробуйте ещё раз");
            return;
        } catch (Exception e) {
            respondError(ex, 502, "Не удалось получить номенклатуру от iiko: " + e.getMessage());
            return;
        }

        respond(ex, 200, iikoResp);
    }

    /** Токен из кэша (см. {@link TokenCache}), если он ещё жив, иначе - свежий через /api/v2/access_token. */
    private String fetchTokenCached(String apiLogin, String appId, String clientSecret) throws Exception {
        return tokenCache.getOrRefresh(apiLogin, appId, clientSecret, () -> {
            JsonNode tokenResp = iiko.fetchTokenV2(apiLogin, appId, clientSecret);
            String token = tokenResp.path("token").asText(null);
            if (token == null || token.isBlank()) {
                // iiko вернул 2xx, но без поля token - отдаём этот ответ как есть, чтобы не потерять контекст.
                throw new IOException("/api/v2/access_token не вернул поле token: " + tokenResp);
            }
            return token;
        });
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
