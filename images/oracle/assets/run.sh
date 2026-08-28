#!/bin/bash
set -e

# Load .env file if it exists
if [ -f "/home/ubuntu/.env" ]; then
  set -a
  source "/home/ubuntu/.env"
  set +a
fi

# Create the bind-mount source dirs up front so they end up owned by ubuntu
mkdir -p ~/oracle/content ~/oracle/logs

echo "Starting Oracle..."
docker compose up -d

echo "Oracle ready. Wrapper MCP endpoint on :${ORACLE_PORT:-8080}/mcp."
