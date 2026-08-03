#!/usr/bin/env bash
set -euo pipefail

echo "tenant-demo tests use isolated SQLite in-memory databases"
npm run test:node
