# HydraPay API Documentation

## Base URL
`http://localhost:8080/api/v1`

---

## Headers
| Header | Type | Description | Required |
| :--- | :--- | :--- | :--- |
| `Content-Type` | `string` | `application/json` | Yes |
| `X-Idempotency-Key` | `string` | Unique client request identifier (UUID or custom key) | Recommended |

---

## Endpoints

### 1. Execute Fund Transfer
`POST /api/v1/transfers`

#### Request Body
```json
{
  "idempotencyKey": "idk_99281a_88392",
  "sourceAccountId": "00000000-0000-0000-0000-000000000001",
  "destinationAccountId": "00000000-0000-0000-0000-000000000002",
  "amount": 250.50,
  "currency": "USD",
  "description": "Merchant Settlement Payout"
}
```

#### Successful Response (`200 OK`)
```json
{
  "transactionId": "9f8a7d6c-1122-3344-5566-778899aabbcc",
  "idempotencyKey": "idk_99281a_88392",
  "sourceAccountId": "00000000-0000-0000-0000-000000000001",
  "destinationAccountId": "00000000-0000-0000-0000-000000000002",
  "amount": 250.50,
  "currency": "USD",
  "status": "SETTLED",
  "sourceBalanceAfter": 9999749.50,
  "destinationBalanceAfter": 250250.50,
  "timestamp": "2026-08-09T14:15:00.000Z",
  "cachedResponse": false
}
```

#### Error Responses
- `422 Unprocessable Entity`: Insufficient Funds
```json
{
  "error": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds in account ACC-4004-USER-ALICE. Balance: 15.00, Required: 250.50",
  "timestamp": "2026-08-09T14:15:00.000Z"
}
```
- `409 Conflict`: Idempotency Key Lock Conflict
```json
{
  "error": "IDEMPOTENCY_CONFLICT",
  "message": "Concurrent request in progress for idempotency key: idk_99281a_88392",
  "timestamp": "2026-08-09T14:15:00.000Z"
}
```

---

### 2. List All Accounts
`GET /api/v1/accounts`

#### Successful Response (`200 OK`)
```json
[
  {
    "id": "00000000-0000-0000-0000-000000000001",
    "accountNumber": "ACC-1001-TREASURY",
    "accountHolderName": "HydraPay Central Liquidity Pool",
    "currency": "USD",
    "balance": 10000000.00,
    "status": "ACTIVE",
    "version": 0,
    "createdAt": "2026-08-09T14:00:00Z"
  }
]
```

---

### 3. Get System Operational Metrics
`GET /api/v1/stats`

#### Successful Response (`200 OK`)
```json
{
  "currentTps": 3842,
  "idempotencyHitRate": 99.8,
  "outboxLagMs": 0,
  "successRatePercent": 100.0,
  "totalTransactions": 14209,
  "pendingOutboxEvents": 0
}
```

---

## cURL Example
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: idk_demo_88192" \
  -d '{
    "idempotencyKey": "idk_demo_88192",
    "sourceAccountId": "00000000-0000-0000-0000-000000000001",
    "destinationAccountId": "00000000-0000-0000-0000-000000000002",
    "amount": 500.00,
    "currency": "USD",
    "description": "API Test Transfer"
  }'
```
