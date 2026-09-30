package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Кэш токенов iikoCloud. Каждый уникальный набор входящих учётных данных
 * (apiKey/apiLogin + appId + clientSecret) держит свой собственный токен и своё
 * собственное время жизни - разные вызывающие с разными ключами никогда не видят
 * чужой токен и не мешают друг другу обновляться.
 *
 * <p>Токен переиспользуется, пока не истёк (с запасом {@link #SAFETY_MARGIN_SECONDS}
 * до реального истечения, которое читается из JWT-claim {@code exp}, а не считается
 * "на глаз" - см. README, официальный срок жизни токена iiko - 1 час, обновление
 * без переопрошенного вызова /api/v2/access_token невозможно, refresh-токена нет).
 */
class TokenCache {
    private static final long SAFETY_MARGIN_SECONDS = 120;
    private static final long FALLBACK_TTL_SECONDS = 55 * 60;

    private static final ObjectMapper OM = new ObjectMapper();

    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    interface TokenFetcher {
        String fetch() throws Exception;
    }

    /**
     * Возвращает закэшированный токен для данного набора учётных данных, если он ещё
     * действителен, иначе синхронно запрашивает новый через {@code fetcher} и кэширует его.
     * Конкурентные вызовы с одним и тем же набором данных не порождают параллельные
     * запросы токена - обновление одного ключа сериализовано.
     */
    String getOrRefresh(String apiLogin, String appId, String clientSecret, TokenFetcher fetcher) throws Exception {
        String key = key(apiLogin, appId, clientSecret);
        CacheEntry entry = cache.computeIfAbsent(key, k -> new CacheEntry());
        return entry.getOrRefresh(fetcher);
    }

    /** Принудительно сбросить кэш для набора учётных данных (например, после HTTP 401 от iiko). */
    void invalidate(String apiLogin, String appId, String clientSecret) {
        cache.remove(key(apiLogin, appId, clientSecret));
    }

    private static String key(String apiLogin, String appId, String clientSecret) {
        return Objects.toString(apiLogin, "") + '\u0000'
                + Objects.toString(appId, "") + '\u0000'
                + Objects.toString(clientSecret, "");
    }

    /** Одна запись кэша: хранит токен и время истечения, сериализует обновление. */
    private static class CacheEntry {
        private String token;
        private Instant expiresAt = Instant.EPOCH;

        synchronized String getOrRefresh(TokenFetcher fetcher) throws Exception {
            if (token != null && Instant.now().isBefore(expiresAt.minusSeconds(SAFETY_MARGIN_SECONDS))) {
                return token;
            }
            String fresh = fetcher.fetch();
            token = fresh;
            expiresAt = expiryOf(fresh);
            return token;
        }
    }

    /** Читает время истечения из JWT-claim {@code exp}; при неудаче - запасной TTL 55 минут. */
    private static Instant expiryOf(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                byte[] payload = Base64.getUrlDecoder().decode(padBase64(parts[1]));
                JsonNode node = OM.readTree(new String(payload, StandardCharsets.UTF_8));
                long exp = node.path("exp").asLong(-1);
                if (exp > 0) {
                    return Instant.ofEpochSecond(exp);
                }
            }
        } catch (Exception ignored) {
            // токен не в ожидаемом формате JWT - используем запасной TTL ниже
        }
        return Instant.now().plusSeconds(FALLBACK_TTL_SECONDS);
    }

    private static String padBase64(String s) {
        int mod = s.length() % 4;
        return mod == 0 ? s : s + "====".substring(mod);
    }
}
