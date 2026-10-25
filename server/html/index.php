<?php
// auto API v1 — trip/sample ingest from the phone app, trip summaries.
// Routing: Apache FallbackResource sends every request here.
declare(strict_types=1);

const ANALYSIS_VERSION = 1;
const SAMPLE_COLUMNS = [
    // json key => db column
    'seq' => 'seq', 't' => 't', 'lat' => 'lat', 'lon' => 'lon', 'gps_speed' => 'gps_speed',
    'alt' => 'alt', 'accuracy' => 'accuracy', 'speed' => 'speed', 'rpm' => 'rpm', 'maf' => 'maf',
    'throttle' => 'throttle', 'coolant' => 'coolant', 'load' => 'engine_load', 'stft' => 'stft',
    'ltft' => 'ltft', 'fuelsys' => 'fuelsys', 'iat' => 'iat', 'timing' => 'timing',
    'o2_down' => 'o2_down', 'volts' => 'volts', 'fuel_lph' => 'fuel_lph', 'lambda' => 'lambda',
];

$cfg = require __DIR__ . '/../config.php';
header('Content-Type: application/json; charset=utf-8');

function out(int $code, mixed $data): never
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function body(): array
{
    $raw = file_get_contents('php://input') ?: '';
    if (strcasecmp($_SERVER['HTTP_CONTENT_ENCODING'] ?? '', 'gzip') === 0) {
        $raw = gzdecode($raw);
        if ($raw === false) out(400, ['error' => 'bad gzip body']);
    }
    $data = json_decode($raw, true);
    if (!is_array($data)) out(400, ['error' => 'body must be a JSON object']);
    return $data;
}

function device(array $cfg): string
{
    $headers = array_change_key_case(getallheaders(), CASE_LOWER);
    $auth = $headers['authorization'] ?? $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+(\S+)$/', $auth, $m)) out(401, ['error' => 'missing bearer token']);
    $hash = hash('sha256', $m[1]);
    foreach ($cfg['tokens'] as $h => $name) {
        if (hash_equals($h, $hash)) return $name;
    }
    out(401, ['error' => 'invalid token']);
}

function db(array $cfg): PDO
{
    return new PDO($cfg['dsn'], $cfg['user'], $cfg['pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
}

function trip_exists(PDO $db, string $uuid): bool
{
    $q = $db->prepare('SELECT 1 FROM trip WHERE uuid = ?');
    $q->execute([$uuid]);
    return (bool)$q->fetchColumn();
}

function put_trip(PDO $db, string $uuid, string $device): never
{
    $b = body();
    if (!is_numeric($b['started_at'] ?? null)) out(400, ['error' => 'started_at (unix seconds) required']);
    $q = $db->prepare(
        'INSERT INTO trip (uuid, device, started_at, ended_at) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE ended_at = COALESCE(VALUES(ended_at), ended_at)'
    );
    $q->execute([$uuid, $device, (float)$b['started_at'], isset($b['ended_at']) ? (float)$b['ended_at'] : null]);
    out(200, ['ok' => true]);
}

function post_samples(PDO $db, string $uuid): never
{
    if (!trip_exists($db, $uuid)) out(404, ['error' => 'unknown trip, PUT it first']);
    $samples = body()['samples'] ?? null;
    if (!is_array($samples) || count($samples) > 5000) out(400, ['error' => 'samples: array of at most 5000']);
    $cols = array_values(SAMPLE_COLUMNS);
    $inserted = 0;
    foreach (array_chunk($samples, 500) as $chunk) {
        $rows = [];
        $args = [];
        foreach ($chunk as $s) {
            if (!is_array($s) || !is_int($s['seq'] ?? null) || !is_numeric($s['t'] ?? null)) {
                out(400, ['error' => 'each sample needs integer seq and numeric t']);
            }
            $rows[] = '(?' . str_repeat(', ?', count($cols)) . ')';
            $args[] = $uuid;
            foreach (SAMPLE_COLUMNS as $key => $_) {
                $v = $s[$key] ?? null;
                $args[] = is_numeric($v) ? $v : null;
            }
        }
        // INSERT IGNORE: re-sent samples (same trip_uuid + seq) are skipped -> uploads are idempotent
        $sql = 'INSERT IGNORE INTO sample (trip_uuid, ' . implode(', ', $cols) . ') VALUES ' . implode(', ', $rows);
        $q = $db->prepare($sql);
        $q->execute($args);
        $inserted += $q->rowCount();
    }
    out(200, ['ok' => true, 'received' => count($samples), 'inserted' => $inserted]);
}

function post_dtc(PDO $db, string $uuid): never
{
    if (!trip_exists($db, $uuid)) out(404, ['error' => 'unknown trip, PUT it first']);
    $b = body();
    if (!is_numeric($b['t'] ?? null)) out(400, ['error' => 't required']);
    $q = $db->prepare('REPLACE INTO dtc_snapshot (trip_uuid, t, mil, stored, pending, freeze) VALUES (?, ?, ?, ?, ?, ?)');
    $q->execute([
        $uuid, (float)$b['t'], isset($b['mil']) ? (int)(bool)$b['mil'] : null,
        json_encode($b['stored'] ?? []), json_encode($b['pending'] ?? []), json_encode($b['freeze'] ?? new stdClass()),
    ]);
    out(200, ['ok' => true]);
}

/** Basic per-trip summary; the full diagnostics engine comes in phase 3. */
function summarize(PDO $db, string $uuid): array
{
    $q = $db->prepare('SELECT t, speed, rpm, coolant, fuel_lph, volts FROM sample WHERE trip_uuid = ? ORDER BY seq');
    $q->execute([$uuid]);
    $dist = $fuel = $run = 0.0;
    $prev = null;
    $cMax = null;
    $cFirst = null;
    $vMax = 0;
    $n = 0;
    $fuelRows = 0;
    while ($r = $q->fetch()) {
        $n++;
        if ($r['fuel_lph'] !== null) $fuelRows++;
        if ($r['coolant'] !== null) {
            $cFirst ??= (int)$r['coolant'];
            $cMax = max($cMax ?? -99, (int)$r['coolant']);
        }
        if ($prev !== null) {
            $dt = (float)$r['t'] - (float)$prev['t'];
            if ($dt > 0 && $dt <= 10 && (int)$r['rpm'] > 300) {
                $run += $dt;
                $dist += (float)$r['speed'] * $dt / 3600;
                $fuel += (float)$r['fuel_lph'] * $dt / 3600;
                $vMax = max($vMax, (int)$r['speed']);
            }
        }
        $prev = $r;
    }
    return [
        'samples' => $n, 'running_s' => round($run), 'distance_km' => round($dist, 2),
        'fuel_l' => $fuelRows ? round($fuel, 3) : null,  // older logs have no fuel data
        'l_per_100km' => $fuelRows && $dist > 0.5 ? round($fuel / $dist * 100, 2) : null,
        'coolant_start' => $cFirst, 'coolant_max' => $cMax, 'speed_max' => $vMax,
    ];
}

function finish_trip(PDO $db, string $uuid): never
{
    if (!trip_exists($db, $uuid)) out(404, ['error' => 'unknown trip']);
    $b = body();
    $s = summarize($db, $uuid);
    $q = $db->prepare(
        'UPDATE trip SET ended_at = COALESCE(?, ended_at), duration_s = COALESCE(?, ended_at) - started_at,
         distance_km = ?, fuel_l = ?, summary = ?, analysis_version = ? WHERE uuid = ?'
    );
    $ended = isset($b['ended_at']) ? (float)$b['ended_at'] : null;
    $q->execute([$ended, $ended, $s['distance_km'], $s['fuel_l'], json_encode($s), ANALYSIS_VERSION, $uuid]);
    out(200, ['ok' => true, 'summary' => $s]);
}

function list_trips(PDO $db): never
{
    $limit = max(1, min(1000, (int)($_GET['limit'] ?? 100)));
    $q = $db->prepare('SELECT uuid, device, started_at, ended_at, duration_s, distance_km, fuel_l, summary
                       FROM trip ORDER BY started_at DESC LIMIT ' . $limit);
    $q->execute();
    $trips = array_map(function ($t) {
        $t['summary'] = $t['summary'] ? json_decode($t['summary'], true) : null;
        return $t;
    }, $q->fetchAll());
    out(200, ['trips' => $trips]);
}

function post_logs(PDO $db, string $device): never
{
    $lines = body()['lines'] ?? null;
    if (!is_array($lines) || count($lines) > 2000) out(400, ['error' => 'lines: array of at most 2000']);
    $q = $db->prepare('INSERT IGNORE INTO app_log (device, client_id, t, level, msg) VALUES (?, ?, ?, ?, ?)');
    foreach ($lines as $l) {
        if (!is_array($l) || !is_int($l['id'] ?? null) || !is_numeric($l['t'] ?? null)) out(400, ['error' => 'each line needs integer id and numeric t']);
        $q->execute([$device, $l['id'], (float)$l['t'], substr((string)($l['level'] ?? 'I'), 0, 1), mb_substr((string)($l['msg'] ?? ''), 0, 4000)]);
    }
    out(200, ['ok' => true, 'received' => count($lines)]);
}

function web_logs(PDO $db): never
{
    $limit = max(1, min(5000, (int)($_GET['limit'] ?? 500)));
    $q = $db->prepare('SELECT device, t, level, msg FROM app_log ORDER BY t DESC, client_id DESC LIMIT ' . $limit);
    $q->execute();
    out(200, ['lines' => array_map(fn($r) => ['device' => $r['device'], 't' => (float)$r['t'], 'level' => $r['level'], 'msg' => $r['msg']], $q->fetchAll())]);
}

// ---------------------------------------------------------------- internal web UI
// Served only for auto.force.local (Caddy restricts that host to LAN/VPN; the public host
// forwards /api/v1/* only). Read-only, no token.

function web_trip(PDO $db, string $uuid): never
{
    $q = $db->prepare('SELECT uuid, device, started_at, ended_at, duration_s, distance_km, fuel_l, summary FROM trip WHERE uuid = ?');
    $q->execute([$uuid]);
    $trip = $q->fetch() ?: out(404, ['error' => 'unknown trip']);
    $trip['summary'] = $trip['summary'] ? json_decode($trip['summary'], true) : null;

    $q = $db->prepare('SELECT * FROM sample WHERE trip_uuid = ? ORDER BY seq');
    $q->execute([$uuid]);
    $samples = [];
    $t0 = null;
    while ($r = $q->fetch()) {
        $row = [];
        foreach (SAMPLE_COLUMNS as $key => $col) {
            $row[$key] = $r[$col] === null ? null : (float)$r[$col];
        }
        $t0 ??= $row['t'];
        $row['elapsed'] = round($row['t'] - $t0, 2);
        $samples[] = $row;
    }

    $q = $db->prepare('SELECT t, mil, stored, pending, freeze FROM dtc_snapshot WHERE trip_uuid = ? ORDER BY t DESC LIMIT 1');
    $q->execute([$uuid]);
    $codes = null;
    if ($d = $q->fetch()) {
        $codes = [
            'read_at' => (float)$d['t'], 'mil' => $d['mil'] === null ? null : (bool)$d['mil'],
            'stored' => json_decode($d['stored'] ?? '[]', true), 'pending' => json_decode($d['pending'] ?? '[]', true),
            'freeze' => json_decode($d['freeze'] ?? '{}', true),
        ];
    }
    out(200, ['trip' => $trip, 'samples' => $samples, 'codes' => $codes]);
}

function web(array $cfg, string $path, string $method): never
{
    if ($method !== 'GET') out(405, ['error' => 'read-only']);
    if ($path === '' || $path === '/index.html') {
        header('Content-Type: text/html; charset=utf-8');
        readfile(__DIR__ . '/../web.html');
        exit;
    }
    try {
        if ($path === '/web/api/config') out(200, ['mapy_key' => $cfg['mapy_key']]);
        $db = db($cfg);
        if ($path === '/web/api/trips') list_trips($db);
        if ($path === '/web/api/logs') web_logs($db);
        if (preg_match('#^/web/api/trips/([0-9a-f-]{36})$#', $path, $m)) web_trip($db, $m[1]);
    } catch (PDOException $e) {
        error_log('auto web: ' . $e->getMessage());
        out(500, ['error' => 'database error']);
    }
    out(404, ['error' => 'not found']);
}

// ---------------------------------------------------------------- routing
$path = rtrim(parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH) ?: '/', '/');
$method = $_SERVER['REQUEST_METHOD'];

$host = strtolower(explode(':', $_SERVER['HTTP_HOST'] ?? '')[0]);
if ($host === 'auto.force.local' && !str_starts_with($path, '/api/v1/')) web($cfg, $path, $method);
if (!str_starts_with($path, '/api/v1/')) out(404, ['error' => 'not found']);
$device = device($cfg);

try {
    if ($method === 'GET' && $path === '/api/v1/ping') out(200, ['ok' => true, 'device' => $device, 'time' => microtime(true)]);
    if ($method === 'GET' && $path === '/api/v1/config') out(200, ['mapy_key' => $cfg['mapy_key']]);
    $db = db($cfg);
    if ($method === 'GET' && $path === '/api/v1/trips') list_trips($db);
    if ($method === 'POST' && $path === '/api/v1/logs') post_logs($db, $device);
    if (preg_match('#^/api/v1/trips/([0-9a-f-]{36})(/[a-z]+)?$#', $path, $m)) {
        [$uuid, $sub] = [$m[1], $m[2] ?? ''];
        match ("$method $sub") {
            'PUT ' => put_trip($db, $uuid, $device),
            'POST /samples' => post_samples($db, $uuid),
            'POST /dtc' => post_dtc($db, $uuid),
            'POST /finish' => finish_trip($db, $uuid),
            default => out(405, ['error' => 'method not allowed']),
        };
    }
    out(404, ['error' => 'not found']);
} catch (PDOException $e) {
    error_log('auto api: ' . $e->getMessage());
    out(500, ['error' => 'database error']);
}
