<?php

/**
 * Raketa route dumper: HTTP routes of every app and console commands of every console.
 *
 * Runs inside the php container, fed through stdin so nothing is written into the working tree:
 *   docker exec -i raketa-php php -d xdebug.mode=off -- --pretty < dump-routes.php > .idea/raketa-routes.json
 *
 * Every app is booted in its own php process (they have separate autoloaders and clashing
 * classes); each child prints its routes after a marker line. Consoles are run as is with
 * `list --format=json`, see rr_console_prelude_source(). Warnings go to stderr, the JSON index to stdout.
 */

declare(strict_types=1);

const RR_WEB = '/web';
const RR_TIMEOUT = 120;
const RR_MARKER = '@@RAKETA_ROUTES@@';
/** Env a console needs only to list its commands: MAILER_DSN is set on stage/prod only. */
const RR_CONSOLE_ENV = ['api/transfer/bin/console' => ['MAILER_DSN' => 'null://null']];

$pretty = in_array('--pretty', $argv, true);
$only = null;
foreach ($argv as $arg) {
    if (str_starts_with($arg, '--only=')) {
        $only = explode(',', substr($arg, 7));
    }
}

// app id => [hosts, boot code]. Hosts follow .docker/nginx/sites-enabled: `<name>.raketa.in`.
$slimCore = static fn (string $dir, string $factory, string $sentry): string => <<<PHP
    require '/web/core/vendor/autoload.php';
    require '/web/api/{$dir}/vendor/autoload.php';
    rr_dotenv();
    \$f = new {$factory}();
    CoreConfigurator::configure(\$f->makeDatabaseFactory(), microtime(true), '{$sentry}');
    rr_emit(rr_slim(\$f->make()));
    PHP;
$slimStandalone = static fn (string $dir, string $build): string => <<<PHP
    require '/web/api/{$dir}/vendor/autoload.php';
    rr_dotenv();
    rr_emit(array_merge(rr_slim({$build}), rr_health('/web/api/{$dir}')));
    PHP;

$apps = [
    'raketa' => [['raketa.in', 'master.*'] /* stage: matched by the first label */, <<<'PHP'
        require '/web/core/vendor/autoload.php';
        require '/web/app/raketa/api/vendor/autoload.php';
        rr_dotenv();
        CoreConfigurator::configure(new Raketa\Database\DatabaseConfigFactory(), microtime(true), 'raketa-cli');
        $env = getenv('APPLICATION_ENV') === 'production' ? 'prod' : 'dev';
        rr_emit(rr_symfony(new App\Kernel($env, !empty($_SERVER['DEVELOPER_MODE']))));
        PHP],
    'transfer' => [['transfer.raketa.in'], <<<'PHP'
        require '/web/api/transfer/vendor/autoload.php';
        rr_dotenv();
        $kernel = (new Api\Transfer\Infrastructure\KernelKernelFactory())->makeKernel($_SERVER + $_ENV, microtime(true));
        rr_emit(array_merge(rr_symfony($kernel), rr_health('/web/api/transfer')));
        PHP],
    'agency' => [['agency.raketa.in'], $slimCore('agency', 'AgencyApi\AgencyApiAppFactory', 'api-agency')],
    'client' => [['client.raketa.in'], $slimCore('client', 'ClientService\ClientApiAppFactory', 'api-client')],
    'mobile' => [['mobile.raketa.in'], $slimCore('mobile', 'Mobile\MobileApiAppFactory', 'api-mobile')],
    'oauth' => [['oauth.raketa.in'], $slimCore('oauth', 'API\OAuthServer\OauthApiAppFactory', 'api-oauth')],
    'profile' => [['profile.raketa.in'], $slimCore('profile', 'API\Profiles\ProfileApiAppFactory', 'api-profile')],
    'aeroexpress' => [['aeroexpress.raketa.in'], $slimStandalone('aeroexpress', '(new API\Aeroexpress\App\AppBuilder())->buildHttpApp(microtime(true))')],
    'avia' => [['avia.raketa.in'], $slimStandalone('avia', '(new Api\Avia\AppBuilder())->httpApp(microtime(true))')],
    'bus' => [['bus.raketa.in'], $slimStandalone('bus', '(new Api\Bus\Infrastructure\AppBuilder())->buildHttpApp(microtime(true))')],
    'contract' => [['contract.raketa.in'], $slimStandalone('contract', '(new Api\Contract\V1\AppFactory())->make(microtime(true))')],
    'hotels' => [['hotels.raketa.in'], $slimStandalone('hotels', '(new Api\Hotels\AppBuilder())->buildHttpApp(microtime(true))')],
    'profile-sync' => [['profile-sync.raketa.in'], $slimStandalone('profile-sync', '(new Api\Sync\V1\AppFactory())->buildHttpApp(microtime(true))')],
    'queue' => [['queue.raketa.in'], $slimStandalone('queue', '(new Api\Queue\V1\AppFactory())->makeHttpApp(microtime(true))')],
    'rail' => [['rail.raketa.in'], $slimStandalone('rail', '(new API\Rail\App\AppBuilder())->buildHttpApp(microtime(true))')],
    'static-api' => [['static.raketa.in'], $slimStandalone('static', '(new Api\Static\Infrastructure\AppBuilder())->buildHttpApp()')],
];

$helpers = rr_helpers_source();
$tmpDir = sys_get_temp_dir() . '/raketa-routes-' . getmypid();
@mkdir($tmpDir, 0777, true);

// Start every app at once: booting a kernel takes seconds, the apps don't share state.
$procs = [];
foreach ($apps as $id => [$hosts, $boot]) {
    if ($only !== null && !in_array($id, $only, true)) {
        continue;
    }
    $script = "$tmpDir/$id.php";
    file_put_contents($script, "<?php\n" . $helpers . "\n" . $boot . "\n");
    $procs[$id] = rr_start($tmpDir, $id, [$script]);
}

$routes = [];
$apps_stats = [];
foreach (rr_finish($procs) as $id => [$stdout, $stderr]) {
    $appRoutes = rr_after_marker($stdout);
    if ($appRoutes === null) {
        rr_warn("[$id] failed: " . rr_tail($stderr !== '' ? $stderr : $stdout) . "\n");
        $apps_stats[$id] = 0;
        continue;
    }
    foreach ($appRoutes as $r) {
        $r['app'] = $id;
        $r['requirements'] = (object) ($r['requirements'] ?? []); // json_decode(assoc) turned {} into []
        $r['hosts'] = array_values(array_unique(array_merge($r['hosts'] ?? [], $apps[$id][0])));
        $routes[] = $r;
    }
    $apps_stats[$id] = count($appRoutes);
}

// Console commands: every console entry point runs its own `list --format=json`, a prepended
// script maps the listed names to the command classes loaded by then. After the routes, so the
// raketa kernel isn't compiled into the same di-cache by two processes at once.
$prelude = "$tmpDir/console-prelude.php";
file_put_contents($prelude, rr_console_prelude_source());
$procs = [];
foreach (rr_console_entries() as $entry => $id) {
    if ($only !== null && !in_array($id, $only, true)) {
        continue;
    }
    $procs[$entry] = rr_start($tmpDir, str_replace('/', '_', $entry), ['-d', "auto_prepend_file=$prelude", RR_WEB . "/$entry", 'list', '--format=json'], RR_CONSOLE_ENV[$entry] ?? []);
}
$commands = [];
$entries_stats = [];
foreach (rr_finish($procs) as $entry => [$stdout, $stderr]) {
    $entryCommands = rr_console_commands($stdout);
    if ($entryCommands === null) {
        rr_warn("[$entry] failed: " . rr_tail($stderr !== '' ? $stderr : $stdout) . "\n");
        $entries_stats[$entry] = 0;
        continue;
    }
    // core and raketa consoles share their commands, profile-sync and hotels repeat theirs in
    // every cli script: one record per command, with every entry point that runs it.
    foreach ($entryCommands as $c) {
        $key = $c['name'] . '|' . $c['handler'];
        $commands[$key] ??= ['apps' => [], 'entries' => []] + $c;
        $commands[$key]['apps'] = array_values(array_unique([...$commands[$key]['apps'], rr_console_entries()[$entry]]));
        $commands[$key]['entries'][] = $entry;
    }
    $entries_stats[$entry] = count($entryCommands);
}
$commands = array_values($commands);

foreach ([['fcc', ['fcc.raketa.in']], ['planeta', ['planeta.raketa.in']]] as [$id, $hosts]) {
    if ($only !== null && !in_array($id, $only, true)) {
        continue;
    }
    $legacy = rr_legacy_routes($id, $hosts);
    $apps_stats[$id] = count($legacy);
    array_push($routes, ...$legacy);
}
if ($only === null || in_array('static', $only, true)) {
    $static = rr_static_routes();
    $apps_stats['static'] = count($static);
    array_push($routes, ...$static);
}

rr_declarations($routes, [
    'raketa' => ['app/raketa/api/config', 'core/config'],
    'transfer' => ['api/transfer/config'],
    'static-api' => ['api/static'],
] + array_combine(
    $slim = ['agency', 'client', 'mobile', 'oauth', 'profile', 'aeroexpress', 'avia', 'bus', 'contract', 'hotels', 'profile-sync', 'queue', 'rail'],
    array_map(static fn (string $dir): array => ["api/$dir"], $slim),
));

exec("rm -rf " . escapeshellarg($tmpDir));

echo json_encode(
    ['version' => 2, 'generatedAt' => date('c'), 'apps' => $apps_stats, 'consoles' => $entries_stats, 'routes' => $routes, 'commands' => $commands],
    JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE | ($pretty ? JSON_PRETTY_PRINT : 0),
), "\n";

/** STDERR is not defined when the script itself comes from stdin. */
function rr_warn(string $message): void
{
    static $err = null;
    $err ??= fopen('php://stderr', 'w');
    fwrite($err, $message);
}

/** Starts `php <args>` in the background with cwd in $tmpDir: apps drop logs (queries_num.log, api_avia/) into it. */
function rr_start(string $tmpDir, string $name, array $args, array $env = []): array
{
    $out = "$tmpDir/$name.out";
    $err = "$tmpDir/$name.err";
    $cmd = [PHP_BINARY, '-d', 'xdebug.mode=off', '-d', 'memory_limit=2G', '-d', 'display_errors=stderr', ...$args];
    $proc = proc_open($cmd, [0 => ['file', '/dev/null', 'r'], 1 => ['file', $out, 'w'], 2 => ['file', $err, 'w']], $pipes, $tmpDir, $env === [] ? null : getenv() + $env);
    return [$proc, $out, $err, microtime(true)];
}

/** @return array<string, array{0: string, 1: string}> stdout and stderr of every process */
function rr_finish(array $procs): array
{
    $result = [];
    foreach ($procs as $id => [$proc, $out, $err, $started]) {
        while (proc_get_status($proc)['running']) {
            if (microtime(true) - $started > RR_TIMEOUT) {
                proc_terminate($proc, 9);
                rr_warn("[$id] timeout after " . RR_TIMEOUT . "s\n");
                break;
            }
            usleep(50_000);
        }
        proc_close($proc);
        $result[$id] = [(string) @file_get_contents($out), trim((string) @file_get_contents($err))];
    }
    return $result;
}

/** Children may print while booting; their JSON follows the last marker line. */
function rr_after_marker(string $stdout): ?array
{
    $pos = strrpos($stdout, RR_MARKER);
    $data = $pos === false ? null : json_decode(trim(substr($stdout, $pos + strlen(RR_MARKER))), true);
    return is_array($data) ? $data : null;
}

function rr_tail(string $text): string
{
    $lines = array_slice(array_filter(explode("\n", $text), 'strlen'), -5);
    return mb_substr(implode(' | ', $lines), 0, 1500);
}

/**
 * fcc and planeta are file-based: lc::parseRequestFile() maps the URL straight to
 * public/<path>, `dir/` → `dir/index.php`; `*.inc.php` are never served.
 */
function rr_legacy_routes(string $app, array $hosts): array
{
    $root = RR_WEB . "/app/$app/public";
    $routes = [];
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS));
    foreach ($it as $file) {
        $path = substr($file->getPathname(), strlen($root));
        if (!str_ends_with($path, '.php') || str_ends_with($path, '.inc.php')) {
            continue;
        }
        $url = str_ends_with($path, '/index.php') ? substr($path, 0, -strlen('index.php')) : $path;
        $routes[] = rr_route($app, $hosts, [], $url, "app/$app/public$path");
    }
    return $routes;
}

/** `/static/` on raketa, fcc and planeta hosts — .docker/nginx/sites-includes/static.conf */
function rr_static_routes(): array
{
    $root = RR_WEB . '/app/static/public';
    $hosts = ['raketa.in', 'fcc.raketa.in', 'planeta.raketa.in'];
    $routes = [];
    $rules = [
        '/static/photo/ac/{rest:.+}' => 'photo/acase.php',
        '/static/photo/tp/{rest:.+}' => 'photo/travelport.php',
        '/static/photo/{rest:.+}' => 'photo/index.php',
        '/static/document/{rest:.+}' => 'document/index.php',
        '/static/kcaptcha/{rest:.+}' => 'kcaptcha/index.php',
    ];
    foreach ($rules as $url => $file) {
        if (is_file("$root/$file")) {
            $routes[] = rr_route('static', $hosts, ['GET'], $url, "app/static/public/$file");
        }
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS));
    foreach ($it as $file) {
        $path = substr($file->getPathname(), strlen($root));
        if (str_ends_with($path, '.php') && !str_ends_with($path, '.inc.php')) {
            $routes[] = rr_route('static', $hosts, [], '/static' . $path, "app/static/public$path");
        }
    }
    return $routes;
}

/**
 * Where each route is declared, as `declaration: {file, line}`: a `#[Route]` attribute above the handler
 * method, else the line of the routing files of its app (`$group->post('/x', Handler::class)`,
 * `->add('name', '/x')->controller('Handler::get')`, yaml `controller:`) that names the handler class,
 * preferring one with the route's path tail or name nearby. File-based apps (fcc, planeta, static) have none.
 *
 * @param array<string, list<string>> $roots app id => dirs with its routing files, relative to the repo
 */
function rr_declarations(array &$routes, array $roots): void
{
    $refs = [];
    foreach ($roots as $app => $dirs) {
        $refs[$app] = rr_class_refs($dirs);
    }
    $sources = [];
    foreach ($routes as &$route) {
        $refsOfApp = $refs[$route['app']] ?? null;
        if ($refsOfApp === null || !str_contains($route['handler'], '::')) {
            continue;
        }
        [$class, $method] = explode('::', $route['handler'], 2);
        if ($route['file'] !== null && $route['line'] > 0) {
            $sources[$route['file']] ??= @file(RR_WEB . '/' . $route['file'], FILE_IGNORE_NEW_LINES) ?: [];
            $line = rr_attribute_line($sources[$route['file']], $route['line'], $route);
            if ($line !== null) {
                $route['declaration'] = ['file' => $route['file'], 'line' => $line];
                continue;
            }
        }
        $short = strtolower(substr(strrchr('\\' . $class, '\\'), 1));
        $best = null;
        // Refs resolved to this very class; by the short name only when the file's imports didn't tell
        $candidates = $refsOfApp['refs'][strtolower($class)] ?? $refsOfApp['short'][$short] ?? [];
        foreach ($candidates as [$file, $i]) {
            if ($file === $route['file']) {
                continue;
            }
            $lines = $refsOfApp['files'][$file];
            $score = preg_match('/\b' . preg_quote($method, '/') . '\b/', $lines[$i]) ? 1 : 0;
            $at = $i;
            // ->add('name', '/path') goes a line or two above ->controller(); a multi-line ->post( puts the path above the class
            for ($j = $i; $j >= max(0, $i - 4); $j--) {
                // the previous route, or the group this one is in — its prefix is a path tail too
                if ($j < $i && preg_match('/::class|::\w+[\'"]|;\s*$|\{\s*$|->group\(/', $lines[$j])) {
                    break;
                }
                $hit = rr_literal_score($lines[$j], $route);
                if ($hit > 0 && $hit + 10 > $score) {
                    [$score, $at] = [$hit + 10, $j];
                }
            }
            if ($best === null || $score > $best[0]) {
                $best = [$score, $file, $at];
            }
        }
        if ($best !== null) {
            $route['declaration'] = ['file' => $best[1], 'line' => $best[2] + 1];
        }
    }
}

/**
 * @return array{files: array<string, list<string>>, refs: array<string, list<array{0: string, 1: int}>>, short: array<string, list<array{0: string, 1: int}>>}
 *     lines of every php/yaml file under $dirs; where each class is referenced (`Handler::class`,
 *     `[Handler::class, 'get']`, `'Ns\Handler::method'`, Slim `'Ns\Handler:method'`, yaml `controller: Ns\Handler::method`)
 *     by its lowercased FQN, resolved through the file's namespace and `use` imports (aliases included); `short` —
 *     the same refs by the short name, the fallback when nothing resolves to the class
 */
function rr_class_refs(array $dirs): array
{
    $files = [];
    $refs = [];
    $short = [];
    foreach ($dirs as $dir) {
        if (!is_dir(RR_WEB . "/$dir")) {
            continue;
        }
        $it = new RecursiveIteratorIterator(new RecursiveCallbackFilterIterator(
            new RecursiveDirectoryIterator(RR_WEB . "/$dir", FilesystemIterator::SKIP_DOTS),
            static fn (SplFileInfo $f): bool => !$f->isDir()
                || !in_array($f->getFilename(), ['vendor', 'node_modules', 'features', 'tests', 'var', 'storage', 'cache', 'docs', 'Generated'], true),
        ));
        foreach ($it as $f) {
            if (!preg_match('/\.(php|ya?ml)$/', $f->getFilename())) {
                continue;
            }
            $text = (string) @file_get_contents($f->getPathname());
            if (!preg_match('/::class|::\w+(?:[\'"]|\s*$)|:\w+[\'"]/m', $text)) {
                continue;
            }
            $rel = substr($f->getPathname(), strlen(RR_WEB) + 1);
            $files[$rel] = explode("\n", $text);
            $namespace = preg_match('/^namespace\s+([\w\\\\]+)/m', $text, $m) ? $m[1] . '\\' : '';
            $imports = [];
            preg_match_all('/^use\s+\\\\?([\w\\\\]+)(?:\s+as\s+(\w+))?\s*;/mi', $text, $uses, PREG_SET_ORDER);
            foreach ($uses as $use) {
                $imports[strtolower($use[2] ?? '' ?: substr(strrchr('\\' . $use[1], '\\'), 1))] = $use[1];
            }
            foreach ($files[$rel] as $i => $line) {
                if (preg_match('/^\s*(use|namespace)\s/', $line)) {
                    continue;
                }
                preg_match_all('/(\\\\?[A-Za-z_]\w*(?:\\\\[A-Za-z_]\w*)*)(::class\b|::\w+(?=[\'"]|\s*$)|:\w+(?=[\'"]))/', $line, $m, PREG_SET_ORDER);
                foreach ($m as [, $name, $suffix]) {
                    $parts = explode('\\', ltrim($name, '\\'));
                    if ($suffix !== '::class' || str_starts_with($name, '\\')) {
                        $fqn = ltrim($name, '\\'); // strings and yaml hold full names
                    } elseif (isset($imports[strtolower($parts[0])])) {
                        $fqn = implode('\\', [$imports[strtolower($parts[0])], ...array_slice($parts, 1)]);
                    } else {
                        $fqn = $namespace . $name;
                    }
                    $refs[strtolower($fqn)][] = [$rel, $i];
                    $short[strtolower(end($parts))][] = [$rel, $i];
                }
            }
        }
    }
    return ['files' => $files, 'refs' => $refs, 'short' => $short];
}

/**
 * The `#[Route]` attribute of this route above the handler method (Symfony attribute controllers): the one
 * with its name, else with its path tail, else the only one.
 */
function rr_attribute_line(array $lines, int $methodLine, array $route): ?int
{
    $blocks = [];
    for ($i = $methodLine - 2; $i >= 0; $i--) {
        $text = rtrim($lines[$i] ?? '');
        if ($text === '' || preg_match('/[;{}]$/', $text)) {
            break;
        }
        if (preg_match('/#\[(?:[\w\\\\]*\\\\)?Route\b/', $text)) {
            $blocks[] = $i;
        }
    }
    if ($blocks === []) {
        return null;
    }
    $best = null;
    foreach ($blocks as $start) {
        $score = 0;
        for ($i = $start; $i < $methodLine - 1; $i++) {
            if ($i > $start && preg_match('/#\[/', $lines[$i])) {
                break;
            }
            $score = max($score, rr_literal_score($lines[$i], $route));
        }
        if ($best === null || $score > $best[0]) {
            $best = [$score, $start];
        }
    }
    return $best[1] + 1;
}

/**
 * How well the quoted strings of a line point at the route: its name or a name suffix (prefixed collections),
 * or a path tail, longer is better; 0 when nothing fits.
 */
function rr_literal_score(string $line, array $route): int
{
    preg_match_all('/[\'"]([^\'"]+)[\'"]/', $line, $m);
    $literals = $m[1];
    if (preg_match('/^([\w.-]+):\s*$/', $line, $key)) {
        $literals[] = $key[1]; // yaml route key
    }
    $name = (string) ($route['name'] ?? '');
    $path = $route['path'];
    $score = 0;
    foreach ($literals as $literal) {
        if ($name !== '' && strlen($literal) >= 4 && str_ends_with($name, $literal)) {
            $score = max($score, 1000 + strlen($literal));
        }
        // Slim optional segments `/x[/{id}]` are expanded in the index
        $tail = str_replace(['[', ']'], '', $literal);
        if (str_starts_with($tail, '/') && strlen($tail) > 1 && str_ends_with($path, rtrim($tail, '/'))) {
            $score = max($score, 100 + strlen($tail));
        }
    }
    return $score;
}

function rr_route(string $app, array $hosts, array $methods, string $path, string $file): array
{
    return [
        'app' => $app,
        'hosts' => $hosts,
        'methods' => $methods,
        'path' => $path,
        'requirements' => (object) [],
        'handler' => $file,
        'file' => $file,
        'line' => 1,
        'name' => null,
    ];
}

/**
 * Console entry points: `core/bin/console`, `app/raketa/api/bin/console` and the extension-less
 * php scripts in `api/<service>/{bin,cli}` that build a console application. Not `warmup-cache`
 * (it warms up as soon as it starts) and not the service-framework template.
 *
 * @return array<string, string> entry path relative to the repo => app id
 */
function rr_console_entries(): array
{
    static $entries = null;
    if ($entries !== null) {
        return $entries;
    }
    $entries = [];
    $files = array_merge(
        glob(RR_WEB . '/core/bin/console') ?: [],
        glob(RR_WEB . '/app/*/api/bin/console') ?: [],
        glob(RR_WEB . '/api/*/bin/*') ?: [],
        glob(RR_WEB . '/api/*/cli/*') ?: [],
    );
    foreach ($files as $file) {
        $entry = substr($file, strlen(RR_WEB) + 1);
        $name = basename($file);
        if (!is_file($file) || str_contains($name, '.') || $name === 'warmup-cache' || str_starts_with($entry, 'api/service-framework/')) {
            continue;
        }
        $source = (string) file_get_contents($file);
        if (!str_starts_with($source, '#!') || !preg_match('/buildCommandApp|commandApp\(|makeCommandApp|new Application\(/i', $source)) {
            continue;
        }
        $entries[$entry] = match (true) {
            $entry === 'core/bin/console' => 'core',
            str_starts_with($entry, 'app/') => explode('/', $entry)[1],
            str_starts_with($entry, 'api/static/') => 'static-api',
            default => explode('/', $entry)[1],
        };
    }
    return $entries;
}

/**
 * Prepended (auto_prepend_file) to a console entry run as `list --format=json`. Symfony writes
 * straight to php://stdout, past any output buffer, so on shutdown the prelude only adds the
 * command classes loaded by then (`list` instantiates even lazy commands) after the marker;
 * rr_console_commands() matches them with the listed names.
 */
function rr_console_prelude_source(): string
{
    return <<<'PHP'
        <?php
        register_shutdown_function(static function (): void {
            $base = Symfony\Component\Console\Command\Command::class;
            $attribute = Symfony\Component\Console\Attribute\AsCommand::class;
            $rel = static function (string|false $file): ?string {
                $real = $file === false ? null : (realpath($file) ?: $file);
                return $real !== null && str_starts_with($real, '/web/') ? substr($real, 5) : $real;
            };
            $classes = [];
            foreach (get_declared_classes() as $class) {
                $rc = new ReflectionClass($class);
                $attributes = class_exists($attribute, false) ? $rc->getAttributes($attribute) : [];
                // Invokable commands (Symfony 7.3+) don't extend Command, the attribute marks them.
                if ($rc->isAbstract() || $class === Symfony\Component\Console\Command\LazyCommand::class || (!$attributes && !$rc->isSubclassOf($base))) {
                    continue;
                }
                $names = [];
                foreach ($attributes as $a) {
                    $names = array_merge($names, explode('|', (string) $a->newInstance()->name));
                }
                if (!$names && method_exists($class, 'getDefaultName')) {
                    try {
                        $names[] = (string) @$class::getDefaultName();
                    } catch (Throwable) {
                    }
                }
                // __invoke()/execute() of the class itself, else the class line.
                [$handler, $file, $line] = [$class, $rel($rc->getFileName()), (int) $rc->getStartLine()];
                foreach (['__invoke', 'execute'] as $method) {
                    if ($rc->hasMethod($method) && $rc->getMethod($method)->getDeclaringClass()->getName() === $class) {
                        $rm = $rc->getMethod($method);
                        [$handler, $file, $line] = ["$class::$method", $rel($rm->getFileName()), (int) $rm->getStartLine()];
                        break;
                    }
                }
                $classes[] = ['names' => array_values(array_filter($names, 'strlen')), 'handler' => $handler, 'file' => $file, 'line' => $line];
            }
            echo "\n@@RAKETA_ROUTES@@", json_encode($classes, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE), "\n";
        });
        PHP;
}

/**
 * Names from the `list --format=json` output, each with its class: by #[AsCommand] /
 * $defaultName, else the class whose file has the name quoted — names set in configure() or
 * the constructor.
 */
function rr_console_commands(string $stdout): ?array
{
    $classes = rr_after_marker($stdout);
    $pos = strrpos($stdout, RR_MARKER);
    $listed = $pos !== false && preg_match('/\{"(?:application|commands)"/', $stdout, $m, PREG_OFFSET_CAPTURE)
        ? json_decode(substr($stdout, $m[0][1], $pos - $m[0][1]), true)
        : null;
    if ($classes === null || !is_array($listed['commands'] ?? null)) {
        return null;
    }
    $byName = [];
    $unnamed = [];
    foreach ($classes as $class) {
        foreach ($class['names'] as $name) {
            $byName[$name] ??= $class;
        }
        if ($class['names'] === [] && $class['file'] !== null) {
            $unnamed[] = $class + ['source' => (string) @file_get_contents(RR_WEB . '/' . $class['file'])];
        }
    }
    $commands = [];
    foreach ($listed['commands'] as $c) {
        $name = $c['name'];
        if (in_array($name, ['_complete', 'completion', 'help', 'list'], true)) {
            continue;
        }
        $class = $byName[$name] ?? null;
        foreach ($c['aliases'] ?? [] as $alias) {
            $class ??= $byName[$alias] ?? null;
        }
        if ($class === null) {
            $quoted = '/[\'"]' . preg_quote($name, '/') . '[\'"]/';
            foreach ($unnamed as $candidate) {
                if (preg_match($quoted, $candidate['source'])) {
                    $class = $candidate;
                    break;
                }
            }
        }
        $commands[] = [
            'name' => $name,
            'aliases' => array_values($c['aliases'] ?? []),
            'description' => (string) ($c['description'] ?? ''),
            'hidden' => (bool) ($c['hidden'] ?? false),
            'handler' => $class['handler'] ?? null,
            'file' => $class['file'] ?? null,
            'line' => $class['line'] ?? 0,
        ];
    }
    return $commands;
}

/**
 * Functions available to every per-app child process. Kept as source text because the child
 * is a fresh php process started from a temp file.
 */
function rr_helpers_source(): string
{
    return <<<'PHP'
        error_reporting(E_ALL & ~E_DEPRECATED & ~E_USER_DEPRECATED & ~E_NOTICE & ~E_WARNING);
        set_time_limit(0);

        // Pretend to be an HTTP request: HTTP app builders read request fields while wiring loggers.
        $_SERVER += [
            'REQUEST_METHOD' => 'GET',
            'REQUEST_URI' => '/',
            'SCRIPT_NAME' => '/index.php',
            'HTTP_HOST' => 'raketa.in',
            'SERVER_NAME' => 'raketa.in',
            'SERVER_PROTOCOL' => 'HTTP/1.1',
            'REMOTE_ADDR' => '127.0.0.1',
            'HTTP_USER_AGENT' => 'raketa-route-dumper',
            'HTTP_X_REQUEST_ID' => str_repeat('0', 32),
            'REQUEST_ID' => str_repeat('0', 32), // api/avia checks for 32 chars
        ];

        function rr_emit(array $routes): void
        {
            // Apps may echo while booting; the parent reads what follows the last marker.
            echo "\n@@RAKETA_ROUTES@@", json_encode($routes, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE), "\n";
            exit(0);
        }

        function rr_dotenv(): void
        {
            // Some services read config through getenv(), so the unsafe (putenv) loader.
            if (class_exists(Dotenv\Dotenv::class)) {
                Dotenv\Dotenv::createUnsafeImmutable('/web')->safeLoad();
            } elseif (class_exists(Symfony\Component\Dotenv\Dotenv::class)) {
                (new Symfony\Component\Dotenv\Dotenv())->usePutenv(true)->load('/web/.env');
            }
        }

        function rr_rel(?string $file): ?string
        {
            if ($file === null || $file === '') {
                return null;
            }
            $real = realpath($file) ?: $file;
            return str_starts_with($real, '/web/') ? substr($real, 5) : $real;
        }

        /** @return array{0: string, 1: ?string, 2: int} handler, file, line */
        function rr_locate(mixed $callable): array
        {
            try {
                if ($callable instanceof Closure) {
                    return rr_closure($callable);
                }
                if (is_array($callable) && count($callable) === 2) {
                    $class = is_object($callable[0]) ? get_class($callable[0]) : (string) $callable[0];
                    return rr_method($class, (string) $callable[1]);
                }
                if (is_object($callable)) {
                    return rr_method(get_class($callable), '__invoke');
                }
                if (is_string($callable)) {
                    if (str_contains($callable, '::')) {
                        [$class, $method] = explode('::', $callable, 2);
                        return rr_method($class, $method);
                    }
                    // Slim "Class:method" and Symfony "service:method"
                    if (preg_match('/^([\w\\\\]+):(\w+)$/', $callable, $m)) {
                        return rr_method($m[1], $m[2]);
                    }
                    return rr_method($callable, '__invoke');
                }
            } catch (Throwable) {
            }
            return [is_string($callable) ? $callable : get_debug_type($callable), null, 0];
        }

        function rr_method(string $class, string $method): array
        {
            if (!class_exists($class) && !interface_exists($class)) {
                return ["$class::$method", null, 0];
            }
            $rc = new ReflectionClass($class);
            if ($rc->hasMethod($method)) {
                $rm = $rc->getMethod($method);
                // Inherited handle()/__invoke() — the concrete class says more than the base one.
                if ($rm->getDeclaringClass()->getName() !== $rc->getName()) {
                    return [$rc->getName() . '::' . $method, rr_rel($rc->getFileName() ?: null), (int) $rc->getStartLine()];
                }
                return [$rc->getName() . '::' . $method, rr_rel($rm->getFileName() ?: null), (int) $rm->getStartLine()];
            }
            return [$rc->getName(), rr_rel($rc->getFileName() ?: null), (int) $rc->getStartLine()];
        }

        /**
         * Closure routes (api/profile, catch-alls) hide their handler; take the first
         * `Something::class` in the closure body that is a real class.
         */
        function rr_closure(Closure $closure): array
        {
            $rf = new ReflectionFunction($closure);
            $file = $rf->getFileName() ?: null;
            if ($file === null || !is_file($file)) {
                return ['Closure', null, 0];
            }
            $lines = file($file);
            $body = implode('', array_slice($lines, $rf->getStartLine() - 1, $rf->getEndLine() - $rf->getStartLine() + 1));
            if (preg_match_all('/([\\\\\w]+)::class/', $body, $m)) {
                $source = implode('', $lines);
                foreach ($m[1] as $name) {
                    $fqn = rr_resolve_name($name, $source);
                    if (class_exists($fqn) && !is_subclass_of($fqn, Psr\Http\Server\MiddlewareInterface::class)) {
                        $rc = new ReflectionClass($fqn);
                        foreach (['__invoke', 'handle', 'execute', 'run'] as $method) {
                            if ($rc->hasMethod($method)) {
                                return rr_method($fqn, $method);
                            }
                        }
                        return [$rc->getName(), rr_rel($rc->getFileName() ?: null), (int) $rc->getStartLine()];
                    }
                }
            }
            return ['Closure@' . basename($file) . ':' . $rf->getStartLine(), rr_rel($file), $rf->getStartLine()];
        }

        function rr_resolve_name(string $name, string $source): string
        {
            if (str_starts_with($name, '\\')) {
                return ltrim($name, '\\');
            }
            $first = explode('\\', $name)[0];
            if (preg_match_all('/^use\s+([\w\\\\]+)(?:\s+as\s+(\w+))?\s*;/m', $source, $uses, PREG_SET_ORDER)) {
                foreach ($uses as $u) {
                    $alias = $u[2] ?? '' ?: substr(strrchr('\\' . $u[1], '\\'), 1);
                    if ($alias === $first) {
                        return $u[1] . substr($name, strlen($first));
                    }
                }
            }
            $ns = preg_match('/^namespace\s+([\w\\\\]+)\s*;/m', $source, $n) ? $n[1] . '\\' : '';
            return $ns . $name;
        }

        /** FastRoute optional tails: `/a[/{b}[/{c}]]` → `/a`, `/a/{b}`, `/a/{b}/{c}` */
        function rr_expand_optional(string $pattern): array
        {
            $depth = 0;
            for ($i = 0, $n = strlen($pattern); $i < $n; $i++) {
                $c = $pattern[$i];
                if ($c === '{') {
                    $depth++;
                } elseif ($c === '}') {
                    $depth--;
                } elseif ($c === '[' && $depth === 0) {
                    $base = substr($pattern, 0, $i);
                    $end = strrpos($pattern, ']');
                    $inner = substr($pattern, $i + 1, $end - $i - 1);
                    $out = [$base];
                    foreach (rr_expand_optional($inner) as $tail) {
                        $out[] = $base . $tail;
                    }
                    return $out;
                }
            }
            return [$pattern];
        }

        function rr_find_slim(object $app): ?Slim\App
        {
            if ($app instanceof Slim\App) {
                return $app;
            }
            foreach ((new ReflectionObject($app))->getProperties() as $p) {
                $p->setAccessible(true);
                if ($p->isInitialized($app) && ($v = $p->getValue($app)) instanceof Slim\App) {
                    return $v;
                }
            }
            return null;
        }

        function rr_slim(object $app): array
        {
            $slim = rr_find_slim($app) ?? throw new RuntimeException('No Slim\App inside ' . get_class($app));
            $routes = [];
            foreach ($slim->getRouteCollector()->getRoutes() as $route) {
                $callable = $route->getCallable();
                $pattern = $route->getPattern();
                [$handler, $file, $line] = rr_locate($callable);
                // Catch-all 404/OPTIONS closures (`/{routes:.+}`) are noise.
                if (str_starts_with($handler, 'Closure@') && preg_match('/\{\w+:\.[+*]\}/', $pattern)) {
                    continue;
                }
                $methods = array_values(array_diff($route->getMethods(), ['OPTIONS'])) ?: $route->getMethods();
                foreach (rr_expand_optional($pattern) as $path) {
                    $routes[] = [
                        'methods' => $methods,
                        'path' => $path === '' ? '/' : $path,
                        'requirements' => (object) [],
                        'handler' => $handler,
                        'file' => $file,
                        'line' => $line,
                        'name' => $route->getName(),
                        'hosts' => [],
                    ];
                }
            }
            return $routes;
        }

        /**
         * `GET /health` is answered by a HealthCheck middleware/subscriber in the standalone services,
         * not by a route. The class may not be loaded yet, so look for its file.
         */
        function rr_health(string $serviceDir): array
        {
            $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($serviceDir, FilesystemIterator::SKIP_DOTS));
            foreach ($it as $file) {
                $path = $file->getPathname();
                if (str_contains($path, '/vendor/') || !preg_match('/HealthCheck\w*(Middleware|Subscriber)\.php$/', $path)) {
                    continue;
                }
                $line = 1;
                foreach (file($path) as $i => $text) {
                    if (preg_match('/function (process|__invoke|onKernelRequest)\b/', $text)) {
                        $line = $i + 1;
                        break;
                    }
                }
                $class = basename($path, '.php');
                return [['methods' => ['GET'], 'path' => '/health', 'requirements' => (object) [], 'handler' => $class, 'file' => rr_rel($path), 'line' => $line, 'name' => 'health', 'hosts' => []]];
            }
            return [];
        }

        function rr_symfony(Symfony\Component\HttpKernel\KernelInterface $kernel): array
        {
            $kernel->boot();
            $router = $kernel->getContainer()->get('router');
            $routes = [];
            foreach ($router->getRouteCollection()->all() as $name => $route) {
                $controller = $route->getDefault('_controller');
                if ($controller === null) {
                    continue;
                }
                [$handler, $file, $line] = rr_locate($controller);
                foreach (rr_symfony_paths($route) as $path) {
                    $routes[] = [
                        'methods' => $route->getMethods(),
                        'path' => $path,
                        'requirements' => (object) array_diff_key($route->getRequirements(), ['_locale' => 1]),
                        'handler' => $handler,
                        'file' => $file,
                        'line' => $line,
                        'name' => $name,
                        'hosts' => $route->getHost() !== '' ? [$route->getHost()] : [],
                    ];
                }
            }
            return $routes;
        }

        /** Trailing placeholders with a default (`{supplierId?}`) are optional: `/a/{b}` also serves `/a`. */
        function rr_symfony_paths(Symfony\Component\Routing\Route $route): array
        {
            $paths = [$path = $route->getPath()];
            while (preg_match('#^(.*)/\{(\w+)\}$#', $path, $m) && $route->hasDefault($m[2])) {
                $paths[] = $path = $m[1] === '' ? '/' : $m[1];
            }
            return $paths;
        }
        PHP;
}
