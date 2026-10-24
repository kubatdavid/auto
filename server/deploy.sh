#!/bin/sh
# Deploy the PHP API to the Pi. Config (/var/www/auto/config.php) and the vhost are managed by hand.
set -e
HOST=${HOST:-172.17.251.12}
cd "$(dirname "$0")"
scp -q html/index.php "$HOST:/tmp/auto-index.php"
ssh "$HOST" 'sudo install -o www-data -g www-data -m 644 /tmp/auto-index.php /var/www/auto/html/index.php \
  && rm /tmp/auto-index.php && php -l /var/www/auto/html/index.php'
