# Raketa Route Index

Плагин PhpStorm: по URL находит контроллер/обработчик в монорепозитории Raketa — во всех
приложениях, а не только в Symfony. Вставляешь в **Search Everywhere** (Shift+Shift) ссылку
`https://raketa.in/api/profiles/645701/remarks`, `POST api/profiles/1/remarks`,
`agency.raketa.in/v2/services/42` или просто `RemarkController`, Enter — открывается метод.
Результаты есть и во вкладке **All**, и в отдельной вкладке **Routes**.

## Ctrl+клик в `.feature`

Ссылкой становится только сам URL в шаге, клик по остальному тексту шага по-прежнему ведёт к
определению шага (Behat-плагин).

- `клиент делает [multipart/form-data ]POST запрос на /v1/...` — приложение берётся из ближайшего
  следующего шага `<App> АПИ возвращает http-статус N` в сценарии (`Raketa`, `Авиа`, `Api hotel`,
  `Mobile`…), иначе по пути файла (`api/<service>/features` → сервис, `core` → raketa);
- `АПИ Авиа на curl-запрос GET /admin/...` — заглушка нашего сервиса, приложение по имени
  (`HostToServiceNameMapFactory`); внешние поставщики (`Поставщик АПИ …`, `avia.supplier.ru`) не ссылки;
- `Функциональность: GET /api/...` — приложение из первого шага ответа файла.

`~lastServiceId~`, `<owner_type>` (Структура сценария) и `{profileId}` совпадают с любым сегментом.
На полном прогоне по всем feature-файлам репозитория находится обработчик для 99,7% URL; остаток —
негативные тесты (`/id`, `abc`, `/notExistPage`).

## Откуда берутся маршруты

`src/main/resources/dumper/dump-routes.php` подаётся в `php` контейнера через stdin
(`docker exec -i raketa-php php -d xdebug.mode=off`) — в рабочую копию raketa ничего не пишется,
кроме `.idea/raketa-routes.json` (`.idea/` в `.gitignore`). Каждое приложение поднимается в
отдельном процессе с cwd во временном каталоге контейнера (приложения сорят логами в cwd).

| Приложение | Хост | Как собираются маршруты |
|---|---|---|
| `raketa` | raketa.in | Symfony `router` (`app/raketa/api` + attribute-контроллеры `core/config/routes/*.yaml`) |
| `transfer` | transfer.* | Symfony `router` |
| agency, client, mobile, oauth, profile | `<name>.*` | Slim `RouteCollector` из `*AppFactory::make()`; замыкания `api/profile` разворачиваются в `...Operation::class` |
| aeroexpress, avia, bus, contract, hotels, profile-sync, queue, rail, static-api | `<name>.*` | Slim из AppBuilder (у avia/hotels достаётся рефлексией из обёртки) + `GET /health` из middleware |
| fcc, planeta | `<name>.*` | файловые: `public/**/*.php` без `*.inc.php`, `dir/index.php` → `dir/` |
| static | `/static/` | правила `static.conf` + файлы `app/static/public` |

Хост в запросе поднимает маршруты своего приложения (совпадение по первой метке — стейдж тоже),
метод (`POST …`) — нужный action, `/ru`, `/en` и хвостовой `index.php` отбрасываются, путь без
префикса (`profiles/1/remarks`) ищется как хвост маршрута.

## Обновление индекса

- IDE: **Tools → Rebuild Raketa Route Index** (или Enter на подсказке «Индекс не собран» в Shift+Shift).
- Терминал: `bin/raketa-routes dump ~/Projects/raketa`; поиск без IDE — `bin/raketa-routes find <url>`.
- Контейнер — `raketa-php`, переопределяется `RAKETA_PHP_CONTAINER` или `-Draketa.routes.container`.

## Сборка

```bash
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/phpstorm/jbr
./gradlew buildPlugin          # build/distributions/raketa-route-index-*.zip → Settings → Plugins → ⚙ → Install from Disk
./gradlew test -PraketaRoutes=../raketa/.idea/raketa-routes.json   # + smoke-тест на реальном дампе
```

Собирается против локально установленного PhpStorm (`localIdePath` в `gradle.properties`), IDE не скачивается.
