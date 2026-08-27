#!/bin/bash
set -e

echo "Starting FUXA HMI..."
docker compose up -d

echo "Waiting for FUXA web UI..."
until curl -sf http://localhost:1881/ > /dev/null 2>&1; do
  sleep 2
done

echo "FUXA HMI started. Access it at http://localhost:1881"
