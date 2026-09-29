#!/usr/bin/env bash
set -euo pipefail
npm run test:node
# Storage tests run on the isolated Cloudflare test Worker: npm run test:remote
