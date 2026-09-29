# iiko-sync

Java-приложение, которое синхронизирует данные из iikoCloud API в локальную базу SQLite:

- `/api/1/nomenclature` — каталог товаров (группы, товары, цены), инкрементально по `revision`;
- `/api/1/stop_lists` — стоп-листы и остатки по терминальным группам;
- `/api/2/menu` и `/api/2/menu/by_id` — внешние меню и цены по ним.

## Сборка

```bash
mvn -q package
```

Получится `target/iiko-sync-1.0.0.jar` (со всеми зависимостями, shaded).

## Запуск

```bash
export IIKO_API_LOGIN="ваш_apiLogin"

# Только для ключей новой схемы авторизации (см. ниже) — иначе не задавайте:
export IIKO_APP_ID="ваш_appId"
export IIKO_CLIENT_SECRET="ваш_clientSecret"

export IIKO_DB="iiko.db"            # необязательно, по умолчанию iiko.db
export SYNC_INTERVAL_MIN="15"       # необязательно: повторять каждые N минут; без переменной — один запуск

java -jar target/iiko-sync-1.0.0.jar
```

## Авторизация: v1 и v2

С 1 июня 2026 часть ключей iikoCloud переведена на новую схему авторизации. Такие ключи
отвечают на `/api/1/access_token` ошибкой:

```
This API key does not support /api/1/access_token. Please use /api/v2/access_token instead.
```

Для них нужно получить `appId` и `clientSecret` в портале разработчика iiko (отдельно от
`apiLogin`, который выдаётся в iikoWeb) и передать через `IIKO_APP_ID` / `IIKO_CLIENT_SECRET`.

`IikoClient` определяет нужную схему автоматически:

- если заданы `IIKO_APP_ID` и `IIKO_CLIENT_SECRET` — сначала пробует `/api/v2/access_token`,
  при неудаче — `/api/1/access_token`;
- если не заданы — сначала `/api/1/access_token`, при неудаче — `/api/v2/access_token`
  (без appId/clientSecret это обычно завершится ошибкой iiko с понятной подсказкой, что нужно
  получить `appId`/`clientSecret` для этого ключа);
- один раз определив рабочий эндпоинт, дальше использует его напрямую, не пробуя оба на
  каждое обновление токена.

Токен обновляется автоматически (с запасом, до истечения официального часа жизни) и при
получении HTTP 401 от любого метода API.

## Структура базы

SQLite-файл создаётся автоматически при первом запуске (`Db.java`). Основные таблицы:

- `organizations` — организации, доступные ключу;
- `product_groups`, `products` — номенклатура;
- `stop_list` — снимок стоп-листов/остатков (перезаписывается на каждой синхронизации);
- `external_menus`, `price_categories`, `menu_raw`, `menu_items` — внешние меню;
- `sync_state` — служебное состояние (текущий `revision` номенклатуры по организациям).

## Устойчивость к сбоям

Каждый из трёх шагов синхронизации (номенклатура, стоп-листы, меню) изолирован: ошибка одного
шага не прерывает остальные (см. `SyncService.step`). HTTP-запросы повторяются с backoff при
429/5xx и пере-авторизуются при 401.
