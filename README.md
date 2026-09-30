# iiko-sync

Java-приложение для работы с iikoCloud API. Два режима, переключаются переменной `MODE`:

- **`MODE=api`** (по умолчанию) — HTTP-шлюз с двумя эндпоинтами: получить токен и получить
  номенклатуру. Учётные данные и токен передаются в каждом запросе, сервер ничего не хранит.
- **`MODE=sync`** — прежний пакетный режим: сам управляет токеном, периодически выгружает
  номенклатуру, стоп-листы и меню в локальный SQLite.

## Сборка

```bash
mvn -q package
```

Получится `target/iiko-sync-1.0.0.jar` (со всеми зависимостями, shaded).

## Режим API (по умолчанию)

```bash
export PORT=8080   # необязательно, по умолчанию 8080
java -jar target/iiko-sync-1.0.0.jar
```

### HTTPS

По умолчанию сервер поднимается по обычному HTTP. Чтобы включить HTTPS:

```bash
export TLS_ENABLED=true
export TLS_KEYSTORE=/путь/к/keystore.p12
export TLS_KEYSTORE_PASSWORD='пароль_хранилища'
export PORT=8443   # необязательно, по умолчанию 8443 при включённом TLS
java -jar target/iiko-sync-1.0.0.jar
```

Сертификат и ключ должны лежать в PKCS12-хранилище. Самоподписанный сертификат для быстрого
старта (браузеры и `curl` без `-k`/`--insecure` будут показывать предупреждение о недоверенном
сертификате, но канал зашифрован):

```bash
keytool -genkeypair -alias iiko-sync -keyalg RSA -keysize 2048 -validity 3650 \
  -keystore keystore.p12 -storetype PKCS12 -storepass 'пароль_хранилища' \
  -dname "CN=<ваш-домен-или-IP>, OU=iiko-sync, O=iiko-sync, C=RU"
```

Для настоящего сертификата без предупреждений (например, от Let's Encrypt) нужен домен,
указывающий на сервер, и его нужно сконвертировать в PKCS12:

```bash
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem \
  -out keystore.p12 -name iiko-sync -passout pass:'пароль_хранилища'
```

Приложение не занимается получением/обновлением сертификатов само - это должно делать
внешнее средство (certbot/acme.sh и т.п.), а готовое хранилище просто указывается через
`TLS_KEYSTORE`.

### POST /api/token — получить токен

Принимает `apiKey` (то же самое, что `apiLogin` в iikoWeb — можно передать любым из двух имён),
и, для ключей новой схемы авторизации, `appId` и `clientSecret`. Внутри дёргает
`/api/v2/access_token` и возвращает ответ iiko как есть.

```bash
curl -s -X POST http://localhost:8080/api/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"ваш_apiLogin","appId":"ваш_appId","clientSecret":"ваш_clientSecret"}'
```

```json
{"correlationId":"...","token":"..."}
```

Если ключ работает по старой схеме (без `/api/v2/access_token`), просто не передавайте
`appId`/`clientSecret` — но учтите, что этот эндпоинт всегда обращается именно к `/api/v2/access_token`
(так задано в задаче); для ключей, которые поддерживают только `/api/1/access_token`, используйте
`MODE=sync`, где `IikoClient` сам подбирает рабочую схему (см. раздел ниже).

### POST /api/nomenclature — получить номенклатуру

Принимает `token` (из `/api/token`), `organizationId` (обязательно) и `terminalId`
(необязательно). Внутри дёргает `/api/1/nomenclature` с заголовком
`Authorization: Bearer <token>` и возвращает ответ iiko как есть (группы, товары, цены).

```bash
curl -s -X POST http://localhost:8080/api/nomenclature \
  -H "Content-Type: application/json" \
  -d '{"token":"...","organizationId":"...","terminalId":"..."}'
```

> **Про `terminalId`:** официальная документация `/api/1/nomenclature` описывает только
> `organizationId` (и `startRevision` для инкрементальной выгрузки) — параметра для фильтрации
> по терминальной группе там нет. Мы передаём `terminalId` в исходящем запросе к iiko как
> `terminalGroupId` на случай, если он всё же на что-то влияет; если iiko его игнорирует, в ответ
> придёт полная номенклатура организации, как обычно. Если для вашей задачи важна именно фильтрация
> по конкретному терминалу — уточните этот момент в поддержке iiko или в актуальной документации,
> которая недоступна мне для автоматической проверки.

### GET /health

Простая проверка живости: `{"status":"ok"}`.

### Ошибки

Оба эндпоинта возвращают `{"error": "..."}` с кодом:
- `400` — не хватает обязательного параметра или тело запроса не JSON;
- `401`/`403` — iiko отклонил запрос (неверный ключ/токен) — пробрасывается как есть, вместе
  с `iikoResponse` (исходный ответ iiko);
- `502` — иная ошибка от iiko или сеть недоступна;
- `503` — запрос к iiko был прерван, стоит повторить.

## Режим синхронизации (`MODE=sync`)

```bash
export MODE=sync
export IIKO_API_LOGIN="ваш_apiLogin"

# Только для ключей новой схемы авторизации (см. ниже) — иначе не задавайте:
export IIKO_APP_ID="ваш_appId"
export IIKO_CLIENT_SECRET="ваш_clientSecret"

export IIKO_DB="iiko.db"            # необязательно, по умолчанию iiko.db
export SYNC_INTERVAL_MIN="15"       # необязательно: повторять каждые N минут; без переменной — один запуск

java -jar target/iiko-sync-1.0.0.jar
```

Синхронизирует:
- `/api/1/nomenclature` — каталог товаров (группы, товары, цены), инкрементально по `revision`;
- `/api/1/stop_lists` — стоп-листы и остатки по терминальным группам;
- `/api/2/menu` и `/api/2/menu/by_id` — внешние меню и цены по ним.

### Авторизация: v1 и v2

С 1 июня 2026 часть ключей iikoCloud переведена на новую схему авторизации. Такие ключи
отвечают на `/api/1/access_token` ошибкой:

```
This API key does not support /api/1/access_token. Please use /api/v2/access_token instead.
```

Для них нужно получить `appId` и `clientSecret` в портале разработчика iiko (отдельно от
`apiLogin`, который выдаётся в iikoWeb) и передать через `IIKO_APP_ID` / `IIKO_CLIENT_SECRET`.

`IikoClient` (используется только в `MODE=sync`) определяет нужную схему автоматически:

- если заданы `IIKO_APP_ID` и `IIKO_CLIENT_SECRET` — сначала пробует `/api/v2/access_token`,
  при неудаче — `/api/1/access_token`;
- если не заданы — сначала `/api/1/access_token`, при неудаче — `/api/v2/access_token`;
- один раз определив рабочий эндпоинт, дальше использует его напрямую.

(Эндпоинт `POST /api/token` из режима `api` всегда обращается к `/api/v2/access_token` напрямую,
без такого перебора — см. выше.)

Токен обновляется автоматически (с запасом, до истечения официального часа жизни) и при
получении HTTP 401 от любого метода API.

### Структура базы

SQLite-файл создаётся автоматически при первом запуске (`Db.java`). Основные таблицы:

- `organizations` — организации, доступные ключу;
- `product_groups`, `products` — номенклатура;
- `stop_list` — снимок стоп-листов/остатков (перезаписывается на каждой синхронизации);
- `external_menus`, `price_categories`, `menu_raw`, `menu_items` — внешние меню;
- `sync_state` — служебное состояние (текущий `revision` номенклатуры по организациям).

### Устойчивость к сбоям

Каждый из трёх шагов синхронизации (номенклатура, стоп-листы, меню) изолирован: ошибка одного
шага не прерывает остальные (см. `SyncService.step`). HTTP-запросы повторяются с backoff при
429/5xx и пере-авторизуются при 401.
