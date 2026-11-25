#!/usr/bin/env bash
# Installs the task manager on a fresh Ubuntu or Debian VM. Run as root from the repository folder:
#   sudo ./deploy/setup.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then echo "Run this with sudo." >&2; exit 1; fi
cd "$(dirname "$0")/.."

apt-get update
apt-get install -y openjdk-21-jdk-headless

install -d /opt/task-manager
rm -rf /opt/task-manager/classes /opt/task-manager/web
mkdir -p /opt/task-manager/classes
javac -d /opt/task-manager/classes $(find server/src -name '*.java')
cp -r web /opt/task-manager/web
install -m 644 deploy/task-manager.service /etc/systemd/system/task-manager.service

systemctl daemon-reload
systemctl enable --now task-manager
systemctl --no-pager --lines=5 status task-manager || true

cat <<MSG

The app is running on 127.0.0.1:8080 only. To serve it over HTTPS:
  1. Install Caddy (https://caddyserver.com/docs/install)
  2. Put deploy/Caddyfile at /etc/caddy/Caddyfile with your domain filled in
  3. systemctl reload caddy
MSG
