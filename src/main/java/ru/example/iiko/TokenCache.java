package ru.example.iiko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
 *
 * <p><b>Важно (см. CHANGELOG/инцидент с утечкой памяти):</b> этот кэш обслуживает
 * публичный эндпоинт {@code /api/nomenclature}, который не требует никакой
 * аутентификации на уровне самого приложения (любой учтёт учётные данные для iiko
 * сам по себе и является "ключом" кэша). Поэтому кэш обязан:
 * <ol>
 *   <li>не оставлять запись, если запрос токена для неё так и не удался - иначе
 *       любой запрос с заведомо неверными/случайными данными (например, от
 *       сканирующего бота) бессрочно занимает память;</li>
 *   <li>быть ограничен по размеру ({@link #MAX_ENTRIES}) с вытеснением наименее
 *       давно использованных записей (LRU) - защита на случай, если кто-то
 *       намеренно "накормит" эндпоинт множеством разных валидных на вид наборов
 *       данных, чтобы исчерпать память.</li>
 * </ol>
 */
class TokenCache {
    private static final long SAFETY_MARGIN_SECONDS = 120;
    private static final long FALLBACK_TTL_SECONDS = 55 * 60;
    private static final int MAX_ENTRIES = 500;

    private static final ObjectMapper OM = new ObjectMapper();

    // LinkedHashMap в режиме access-order + removeEldestEntry = простой LRU без внешних
    // зависимостей. Доступ только под синхронизацией по cache - LinkedHashMap не потокобезопасен.
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    interface TokenFetcher {
        String fetch() throws Exception;
    }

    /**
     * Возвращает закэшированный токен для данного набора учётных данных, если он ещё
     * действителен, иначе синхронно запрашивает новый через {@code fetcher} и кэширует его.
     * Конкурентные вызовы с одним и тем же набором данных не порождают параллельные
     * запросы токена - обновление одного ключа сериализовано. Если запрос токена
     * провалился, запись в кэше не остаётся (см. класс-javadoc).
     */
    String getOrRefresh(String apiLogin, String appId, String clientSecret, TokenFetcher fetcher) throws Exception {
        String key = key(apiLogin, appId, clientSecret);
        CacheEntry entry;
        synchronized (cache) {
            entry = cache.computeIfAbsent(key, k -> new CacheEntry());
        }
        try {
            return entry.getOrRefresh(fetcher);
        } catch (Exception e) {
            // Не удалось получить токен для этого набора данных - не оставляем пустую
            // запись в кэше навсегда (иначе это и есть утечка памяти на внешнем трафике).
            // Удаляем, только если в entry так и не появился валидный токен (на случай
            // если конкурентный поток успел обновить её между вызовами).
            synchronized (cache) {
                CacheEntry current = cache.get(key);
                if (current == entry && !entry.hasToken()) {
                    cache.remove(key);
                }
            }
            throw e;
        }
    }

    /** Принудительно сбросить кэш для набора учётных данных (например, после HTTP 401 от iiko). */
    void invalidate(String apiLogin, String appId, String clientSecret) {
        synchronized (cache) {
            cache.remove(key(apiLogin, appId, clientSecret));
        }
    }

    /** Текущее число закэшированных наборов данных - для диагностики/мониторинга. */
    int size() {
        synchronized (cache) {
            return cache.size();
        }
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

        synchronized boolean hasToken() {
            return token != null;
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
