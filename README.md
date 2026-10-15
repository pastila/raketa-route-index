# Raketa Route Index

Плагин PhpStorm: по URL находит контроллер/обработчик в монорепозитории Raketa — во всех
приложениях, а не только в Symfony. Вставляешь в **Search Everywhere** (Shift+Shift) ссылку
`https://raketa.in/api/profiles/645701/remarks`, `POST api/profiles/1/remarks`,
`agency.raketa.in/v2/services/42` или просто `RemarkController`, Enter — открывается метод.
Результаты есть и во вкладке **All**, и в отдельной вкладке **Routes**.

Там же ищутся **консольные команды** всех консолей: `bus:translate-stations`, строка из крона целиком
(`php api/hotels/cli/sabre sync:api:service --id=1` — путь скрипта выбирает между одноимёнными
командами разных скриптов), кусок имени, класса или описания (`автобусные станции`). Enter открывает
`execute()`/`__invoke()` класса команды.

## ▶ у консольных команд

На `execute()`/`__invoke()` каждой команды из индекса в гутере появляется ▶: **Run** и **Debug** через
каждую консоль, которая её обслуживает (`core/bin/console`, `app/raketa/api/bin/console`,
`api/hotels/cli/sabre`, …). Запуск — временная конфигурация **PHP Script** на интерпретаторе проекта
по умолчанию (`php (Docker)`), рабочий каталог — корень приложения консоли (`core`, `api/hotels`),
так что Debug сам включает прослушивание Xdebug. Конфигурация с тем же именем (`<команда>` или
`<команда> (<консоль>)`, если консолей несколько) переиспользуется как есть — сохраните её
(Save Configuration) и допишите аргументы (`--id=1`), они останутся. Метод ищется по имени, а не по
строке из дампа, поэтому значок не съезжает при правке файла; новая команда получает ▶ после
пересборки индекса.

## Маршрут у контроллера

Обратная сторона поиска: на методе-обработчике каждого маршрута из индекса (`__invoke()` операции Slim,
action Symfony-контроллера) в гутере стоит значок, в подсказке — его маршруты
(`POST /v3/chat/service/{serviceId}`). Клик открывает меню:

- **Копировать URL** — с хостом локального стенда (`https://mobile.raketa.in/v3/chat/service/{serviceId}`),
  и **Копировать путь**; inline-требования плейсхолдеров (`{serviceId:[0-9]+}`) отбрасываются;
- **Найти в feature-файлах** — шаги behat всех `.feature` проекта, чей URL ведёт в этот метод (то же
  сопоставление, что у Ctrl+клика ниже, включая заглушки `АПИ Авиа на curl-запрос`). Одно вхождение
  открывается сразу, несколько — списком.

Метод ищется по имени, а не по строке из дампа; новый контроллер получает значок после пересборки индекса.
Из терминала то же даёт `bin/raketa-routes find PostMessageForServiceHandler` (поиск по тексту handler-а).

## Ctrl+клик в `.feature`

Ссылкой становится только сам URL в шаге, клик по остальному тексту шага по-прежнему ведёт к
определению шага (Behat-плагин).

- `клиент делает [multipart/form-data ]POST запрос на /v1/...` — приложение берётся из ближайшего
  следующего шага `<App> АПИ возвращает http-статус N` в сценарии (`Raketa`, `Авиа`, `Api hotel`,
  `Mobile`…), иначе по пути файла (`api/<service>/features` → сервис, `core` → raketa);
- `АПИ Авиа на curl-запрос GET /admin/...` — заглушка нашего сервиса, приложение по имени
  (`HostToServiceNameMapFactory`); внешние поставщики (`Поставщик АПИ …`, `avia.supplier.ru`) не ссылки;
- `Функциональность: GET /api/...` — приложение из первого шага ответа файла;
- `Дано команда "aeroexpress:booking --serviceId=1" вернёт код 0 …` — ссылка на имени команды, ведёт в её класс.

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

Консольные команды. Консоли — `core/bin/console`, `app/raketa/api/bin/console` и php-скрипты без
расширения в `api/<service>/{bin,cli}`, которые собирают консольное приложение (кроме `warmup-cache` и
шаблона `service-framework`; `api/avia/cli/*.php` — обычные скрипты, они находятся через Go to File).
Каждая запускается как есть с `list --format=json` и `auto_prepend_file`: на shutdown пролог отдаёт
загруженные к этому моменту классы команд (`list` создаёт и lazy-команды), имя сопоставляется с классом
по `#[AsCommand]`/`$defaultName`, иначе по имени в кавычках в файле класса (`configure()`, конструктор).
Одинаковые команды разных консолей (core и raketa, все `profile-sync/cli`) хранятся одной записью со
списком `entries`. Без класса остаются только `make:*` maker-bundle в transfer. `api/static/cli/run`
локально не стартует, пока в его vendor нет `elasticsearch/elasticsearch` (`composer install` в `api/static`).

Хост в запросе поднимает маршруты своего приложения (совпадение по первой метке — стейдж тоже),
метод (`POST …`) — нужный action, `/ru`, `/en` и хвостовой `index.php` отбрасываются, путь без
префикса (`profiles/1/remarks`) ищется как хвост маршрута.

## Обновление индекса

- IDE: **Tools → Rebuild Raketa Route Index** (или Enter на подсказке «Индекс не собран» в Shift+Shift).
- Терминал: `bin/raketa-routes dump ~/Projects/raketa`; поиск без IDE — `bin/raketa-routes find <url|команда>`.
- Контейнер — `raketa-php`, переопределяется `RAKETA_PHP_CONTAINER` или `-Draketa.routes.container`.

## Сборка

```bash
export JAVA_HOME=~/.local/share/JetBrains/Toolbox/apps/phpstorm/jbr
./gradlew buildPlugin          # build/distributions/raketa-route-index-*.zip → Settings → Plugins → ⚙ → Install from Disk
./gradlew test -PraketaRoutes=../raketa/.idea/raketa-routes.json   # + smoke-тест на реальном дампе
```

Собирается против локально установленного PhpStorm (`localIdePath` в `gradle.properties`), IDE не скачивается.
