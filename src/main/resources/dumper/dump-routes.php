<?php

/**
 * Raketa route dumper.
 *
 * Runs inside the php container, fed through stdin so nothing is written into the working tree:
 *   docker exec -i raketa-php php -d xdebug.mode=off -- --pretty < dump-routes.php > .idea/raketa-routes.json
 *
 * Every app is booted in its own php process (they have separate autoloaders and clashing
 * classes); each child prints its routes after a marker line. Warnings go to stderr, the JSON
 * index to stdout.
 */

declare(strict_types=1);

const RR_WEB = '/web';
const RR_TIMEOUT = 120;

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
    $out = "$tmpDir/$id.out";
    $err = "$tmpDir/$id.err";
    $cmd = [PHP_BINARY, '-d', 'xdebug.mode=off', '-d', 'memory_limit=2G', '-d', 'display_errors=stderr', $script];
    $proc = proc_open($cmd, [0 => ['file', '/dev/null', 'r'], 1 => ['file', $out, 'w'], 2 => ['file', $err, 'w']], $pipes, $tmpDir); // cwd: apps drop logs (queries_num.log, api_avia/) into it
    $procs[$id] = [$proc, $hosts, $out, $err, microtime(true)];
}

$routes = [];
$apps_stats = [];
foreach ($procs as $id => [$proc, $hosts, $out, $err, $started]) {
    while (proc_get_status($proc)['running']) {
        if (microtime(true) - $started > RR_TIMEOUT) {
            proc_terminate($proc, 9);
            rr_warn("[$id] timeout after " . RR_TIMEOUT . "s\n");
            break;
        }
        usleep(50_000);
    }
    proc_close($proc);
    $stdout = (string) @file_get_contents($out);
    $stderr = trim((string) @file_get_contents($err));
    $pos = strrpos($stdout, "@@RAKETA_ROUTES@@");
    $appRoutes = $pos === false ? null : json_decode(trim(substr($stdout, $pos + 17)), true);
    if (!is_array($appRoutes)) {
        rr_warn("[$id] failed: " . rr_tail($stderr !== '' ? $stderr : $stdout) . "\n");
        $apps_stats[$id] = 0;
        continue;
    }
    foreach ($appRoutes as $r) {
        $r['app'] = $id;
        $r['requirements'] = (object) ($r['requirements'] ?? []); // json_decode(assoc) turned {} into []
        $r['hosts'] = array_values(array_unique(array_merge($r['hosts'] ?? [], $hosts)));
        $routes[] = $r;
    }
    $apps_stats[$id] = count($appRoutes);
}

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

exec("rm -rf " . escapeshellarg($tmpDir));

echo json_encode(
    ['version' => 1, 'generatedAt' => date('c'), 'apps' => $apps_stats, 'routes' => $routes],
    JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE | ($pretty ? JSON_PRETTY_PRINT : 0),
), "\n";

/** STDERR is not defined when the script itself comes from stdin. */
function rr_warn(string $message): void
{
    static $err = null;
    $err ??= fopen('php://stderr', 'w');
    fwrite($err, $message);
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
