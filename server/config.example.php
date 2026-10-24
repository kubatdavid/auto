<?php
// Copy to /var/www/auto/config.php (outside the document root) and fill in.
return [
    'dsn'  => 'mysql:host=localhost;dbname=auto;charset=utf8mb4',
    'user' => 'auto',
    'pass' => 'CHANGE_ME',
    // sha256 hex of each device token => device name
    'tokens' => [
        // hash('sha256', '<token>') => 'pixel9',
    ],
    'mapy_key' => '',  // handed to authenticated devices via GET /api/v1/config
];
