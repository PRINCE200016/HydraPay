#!/usr/bin/env bash
# HydraPay Load Test Shell Launcher
# Usage: ./scripts/load_test.sh [TOTAL_REQUESTS] [CONCURRENT_THREADS] [URL]

REQUESTS=${1:-10000}
THREADS=${2:-50}
URL=${3:-"http://localhost:8080/api/v1/transfers"}

echo "Firing $REQUESTS transfers to $URL with $THREADS threads..."
python3 scripts/load_test.py --url "$URL" --requests "$REQUESTS" --threads "$THREADS"
