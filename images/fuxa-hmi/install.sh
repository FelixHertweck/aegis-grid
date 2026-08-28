#!/bin/bash
set -e
set -u
set -x

# Ensure Docker daemon is running before pulling images
sudo systemctl start docker
until sudo docker info >/dev/null 2>&1; do sleep 2; done
# The docker group change from setup.sh is not reflected in this SSH session,
# so grant socket access explicitly for the duration of the install.
sudo chmod 666 /var/run/docker.sock

# Copy bundled files into the home directory
cp /tmp/assets/docker-compose.yml ~/docker-compose.yml
cp /tmp/assets/mysettings.json ~/mysettings.json

# Pre-pull the FUXA image. --quiet drops the progress bars, but Compose still
# prints "Pulling"/"Pulled" status lines to stderr; silence those too. A real
# pull failure still aborts the build via `set -e`.
docker compose pull --quiet 2>/dev/null

# Install the run wrapper
cp /tmp/assets/run.sh ~/run.sh
chmod +x ~/run.sh
