#!/bin/bash
set -e
set -u
set -x

# The oracle wrapper runs as a Docker container (see assets/docker-compose.yml) — nothing
# installed bare-metal.
sudo systemctl start docker
until sudo docker info >/dev/null 2>&1; do sleep 2; done
sudo chmod 666 /var/run/docker.sock

cp /tmp/assets/docker-compose.yml ~/docker-compose.yml
docker compose pull --quiet

cp /tmp/assets/run.sh ~/run.sh
chmod +x ~/run.sh
