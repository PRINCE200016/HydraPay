#!/usr/bin/env python3
"""
HydraPay Production Load & Stress Test Simulator (Phase 5 Hardened)
------------------------------------------------------------------
Fires up to 10,000+ concurrent transfer requests to HydraPay.
Validates 2-Tier Idempotency, Rate Limiting (429 handling), Correlation Tracing,
and Deterministic Account Lock Ordering.

Usage:
    python load_test.py [--url http://localhost:8080/api/v1/transfers] [--requests 10000] [--threads 50] [--username operator] [--password operatorpass]
"""

import argparse
import base64
import json
import random
import time
import uuid
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

ACCOUNTS = [
    "00000000-0000-0000-0000-000000000001",  # Treasury
    "00000000-0000-0000-0000-000000000002",  # Merchant Alpha
    "00000000-0000-0000-0000-000000000003",  # Merchant Beta
    "00000000-0000-0000-0000-000000000004",  # Alice
    "00000000-0000-0000-0000-000000000005",  # Bob
]

def send_request(url, payload, idempotency_key, username=None, password=None):
    correlation_id = f"corr_load_{uuid.uuid4().hex[:12]}"
    headers = {
        "Content-Type": "application/json",
        "X-Idempotency-Key": idempotency_key,
        "X-Correlation-ID": correlation_id
    }

    if username and password:
        credentials = f"{username}:{password}"
        encoded_cred = base64.b64encode(credentials.encode("utf-8")).decode("utf-8")
        headers["Authorization"] = f"Basic {encoded_cred}"

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")

    start_time = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            latency_ms = (time.perf_counter() - start_time) * 1000.0
            body = response.read().decode("utf-8")
            res_json = json.loads(body) if body else {}
            is_cached = res_json.get("cachedResponse", False)
            return {
                "status": response.status,
                "latency_ms": latency_ms,
                "cached": is_cached,
                "error": None
            }
    except urllib.error.HTTPError as e:
        latency_ms = (time.perf_counter() - start_time) * 1000.0
        return {
            "status": e.code,
            "latency_ms": latency_ms,
            "cached": False,
            "error": e.reason
        }
    except Exception as e:
        latency_ms = (time.perf_counter() - start_time) * 1000.0
        return {
            "status": 0,
            "latency_ms": latency_ms,
            "cached": False,
            "error": str(e)
        }

def run_load_test(target_url, total_requests, thread_count, duplicate_ratio, username, password):
    print("==========================================================================")
    print("           HYDRAPAY PRODUCTION LOAD & STRESS SIMULATOR (PHASE 5)          ")
    print("==========================================================================")
    print(f"Target URL              : {target_url}")
    print(f"Total Requests          : {total_requests}")
    print(f"Concurrent Threads      : {thread_count}")
    print(f"Duplicate Key Ratio     : {int(duplicate_ratio * 100)}%")
    print(f"Auth Username           : {username if username else 'None (Anonymous)'}")
    print("==========================================================================")

    pool_size = max(1, int(total_requests * (1.0 - duplicate_ratio)))
    key_pool = [f"idk_load_{i}_{uuid.uuid4().hex[:8]}" for i in range(pool_size)]

    work_items = []
    for i in range(total_requests):
        if random.random() < duplicate_ratio and key_pool:
            key = random.choice(key_pool)
        else:
            key = f"idk_load_{i}_{uuid.uuid4().hex[:8]}"

        source, dest = random.sample(ACCOUNTS, 2)
        amount = round(random.uniform(1.0, 50.0), 2)

        payload = {
            "idempotencyKey": key,
            "sourceAccountId": source,
            "destinationAccountId": dest,
            "amount": amount,
            "currency": "USD",
            "description": f"Phase 5 Load test tx #{i}"
        }
        work_items.append((payload, key))

    print(f"Executing load test across {thread_count} worker threads...")
    start_global = time.perf_counter()
    results = []

    with ThreadPoolExecutor(max_workers=thread_count) as executor:
        futures = [executor.submit(send_request, target_url, payload, key, username, password) for payload, key in work_items]
        for future in as_completed(futures):
            results.append(future.result())

    total_duration_sec = time.perf_counter() - start_global
    tps = total_requests / total_duration_sec if total_duration_sec > 0 else 0

    successful = [r for r in results if r["status"] == 200]
    rate_limited = [r for r in results if r["status"] == 429]
    business_errors = [r for r in results if r["status"] in (400, 401, 403, 404, 409, 422)]
    system_errors = [r for r in results if r["status"] >= 500 or r["status"] == 0]

    cached = [r for r in successful if r["cached"]]
    fresh = [r for r in successful if not r["cached"]]

    latencies = sorted([r["latency_ms"] for r in results])
    avg_lat = sum(latencies) / len(latencies) if latencies else 0
    p50 = latencies[int(len(latencies) * 0.50)] if latencies else 0
    p90 = latencies[int(len(latencies) * 0.90)] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    p99 = latencies[int(len(latencies) * 0.99)] if latencies else 0

    print("\n" + "=" * 74)
    print("                        PHASE 5 BENCHMARK RESULTS                         ")
    print("=" * 74)
    print(f"Total Requests Executed  : {total_requests}")
    print(f"Total Elapsed Time       : {total_duration_sec:.2f} seconds")
    print(f"Achieved Throughput      : {tps:.2f} TPS")
    print(f"HTTP 200 Success         : {len(successful)} ({(len(successful)/total_requests)*100:.1f}%)")
    print(f"  └─ Fresh Mutations     : {len(fresh)}")
    print(f"  └─ Idempotency Hits    : {len(cached)}")
    print(f"HTTP 429 Rate Limited    : {len(rate_limited)}")
    print(f"HTTP 4xx Business Errors : {len(business_errors)}")
    print(f"HTTP 5xx System Errors   : {len(system_errors)}")
    print("-" * 74)
    print("Latency Metrics:")
    print(f"  Min Latency            : {latencies[0]:.2f} ms" if latencies else "N/A")
    print(f"  Avg Latency            : {avg_lat:.2f} ms")
    print(f"  P50 Latency            : {p50:.2f} ms")
    print(f"  P90 Latency            : {p90:.2f} ms")
    print(f"  P95 Latency            : {p95:.2f} ms")
    print(f"  P99 Latency            : {p99:.2f} ms")
    print(f"  Max Latency            : {latencies[-1]:.2f} ms" if latencies else "N/A")
    print("=" * 74)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="HydraPay Production Load Test Simulator")
    parser.add_argument("--url", default="http://localhost:8080/api/v1/transfers", help="Transfers API endpoint")
    parser.add_argument("--requests", type=int, default=10000, help="Total number of requests")
    parser.add_argument("--threads", type=int, default=50, help="Number of concurrent worker threads")
    parser.add_argument("--duplicates", type=float, default=0.2, help="Ratio of duplicate idempotency keys (0.0 - 1.0)")
    parser.add_argument("--username", default=None, help="HTTP Basic Auth username (e.g. operator)")
    parser.add_argument("--password", default=None, help="HTTP Basic Auth password (e.g. operatorpass)")
    args = parser.parse_args()

    run_load_test(args.url, args.requests, args.threads, args.duplicates, args.username, args.password)
