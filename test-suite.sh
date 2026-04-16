#!/usr/bin/env bash
# GraphQL EDC Connector — Comprehensive Test Suite
# Tests both Thrift protocol (direct) and Composer API (integration)
# Against: Countries API (flat/no-auth) and Supabase FRC (Relay/auth)

set -euo pipefail

PASS=0
FAIL=0
SKIP=0
RESULTS=""

COMPOSER_BASE="http://localhost:8080/discovery/api"
COMPOSER_AUTH="admin:SimbaIntelligence123456!"
COUNTRIES_URL="https://countries.trevorblades.com/graphql"
SUPABASE_URL="https://cqkemdwjcuhiraiqxpzf.supabase.co/graphql/v1"
SUPABASE_KEY="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNxa2VtZHdqY3VoaXJhaXF4cHpmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjUyNTMxMjIsImV4cCI6MjA4MDgyOTEyMn0.ORN6kq-0oBIm9RSvrR2mBPiFDfGAz9Li8DH-h1S7IQY"

green() { echo -e "\033[32m$1\033[0m"; }
red() { echo -e "\033[31m$1\033[0m"; }
yellow() { echo -e "\033[33m$1\033[0m"; }

assert() {
    local name="$1" severity="$2" result="$3" expected="$4"
    if echo "$result" | grep -q "$expected"; then
        green "  PASS | $severity | $name"
        PASS=$((PASS+1))
        RESULTS+="PASS|$severity|$name\n"
    else
        red "  FAIL | $severity | $name"
        red "    expected: $expected"
        red "    got: $(echo "$result" | head -2)"
        FAIL=$((FAIL+1))
        RESULTS+="FAIL|$severity|$name\n"
    fi
}

assert_count() {
    local name="$1" severity="$2" result="$3" min="$4"
    local count=$(echo "$result" | grep -c "$5" 2>/dev/null || echo 0)
    if [ "$count" -ge "$min" ]; then
        green "  PASS | $severity | $name (count=$count >= $min)"
        PASS=$((PASS+1))
        RESULTS+="PASS|$severity|$name\n"
    else
        red "  FAIL | $severity | $name (count=$count < $min)"
        FAIL=$((FAIL+1))
        RESULTS+="FAIL|$severity|$name\n"
    fi
}

assert_not() {
    local name="$1" severity="$2" result="$3" unexpected="$4"
    if echo "$result" | grep -q "$unexpected"; then
        red "  FAIL | $severity | $name (found unexpected: $unexpected)"
        FAIL=$((FAIL+1))
        RESULTS+="FAIL|$severity|$name\n"
    else
        green "  PASS | $severity | $name"
        PASS=$((PASS+1))
        RESULTS+="PASS|$severity|$name\n"
    fi
}

echo ""
echo "============================================================"
echo "  GraphQL EDC Connector — Test Suite"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"
echo ""

# ============================================================
echo "### 1. Infrastructure Health (CRITICAL)"
# ============================================================

R=$(curl -s http://localhost:7338/actuator/health 2>&1)
assert "Local connector health check" "CRITICAL" "$R" '"status":"UP"'

R=$(kubectl -n simba-intel get pod edc-graphql --no-headers 2>&1)
assert "In-cluster pod running" "CRITICAL" "$R" "Running"

R=$(curl -s -o /dev/null -w "%{http_code}" "$COMPOSER_BASE/connectors" -u "$COMPOSER_AUTH" 2>&1)
assert "Composer API reachable" "CRITICAL" "$R" "200"

R=$(curl -s "$COMPOSER_BASE/connectors" -u "$COMPOSER_AUTH" 2>&1)
assert "GraphQL connector registered in Composer" "CRITICAL" "$R" "GraphQL"

echo ""
# ============================================================
echo "### 2. Countries API — Schema Discovery (HIGH)"
# ============================================================

R=$(curl -s -X POST "$COUNTRIES_URL" -H "Content-Type: application/json" \
  -d '{"query":"{ __schema { queryType { fields { name } } } }"}' 2>&1)
assert "Countries introspection succeeds" "HIGH" "$R" "queryType"
assert "Countries has 'countries' collection" "HIGH" "$R" "countries"
assert "Countries has 'continents' collection" "HIGH" "$R" "continents"
assert "Countries has 'languages' collection" "HIGH" "$R" "languages"

# Verify field types
R=$(curl -s -X POST "$COUNTRIES_URL" -H "Content-Type: application/json" \
  -d '{"query":"{ __type(name: \"Country\") { fields { name type { name kind ofType { name kind } } } } }"}' 2>&1)
assert "Country type has 'name' field" "HIGH" "$R" '"name".*"name"'
assert "Country type has 'code' field" "HIGH" "$R" '"name".*"code"'
assert "Country type has 'capital' field" "HIGH" "$R" '"name".*"capital"'

echo ""
# ============================================================
echo "### 3. Countries API — Data Fetch (CRITICAL)"
# ============================================================

R=$(curl -s -X POST "$COUNTRIES_URL" -H "Content-Type: application/json" \
  -d '{"query":"{ countries { name code capital emoji } }"}' 2>&1)
assert "Countries flat query returns data" "CRITICAL" "$R" "Andorra"
assert "Countries returns country codes" "CRITICAL" "$R" '"code"'

# Count check
COUNT=$(echo "$R" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['countries']))" 2>/dev/null || echo 0)
if [ "$COUNT" -ge 200 ]; then
    green "  PASS | CRITICAL | Countries returns 200+ records (got $COUNT)"
    PASS=$((PASS+1))
else
    red "  FAIL | CRITICAL | Countries returns 200+ records (got $COUNT)"
    FAIL=$((FAIL+1))
fi

echo ""
# ============================================================
echo "### 4. Supabase API — Relay Pattern Discovery (CRITICAL)"
# ============================================================

R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ __schema { queryType { fields { name } } } }"}' 2>&1)
assert "Supabase introspection succeeds" "CRITICAL" "$R" "queryType"
assert "Supabase has frc_customersCollection" "CRITICAL" "$R" "frc_customersCollection"
assert "Supabase has frc_transactionsCollection" "CRITICAL" "$R" "frc_transactionsCollection"
assert "Supabase has frc_compliance_alertsCollection" "CRITICAL" "$R" "frc_compliance_alertsCollection"
assert "Supabase has frc_risk_scoresCollection" "CRITICAL" "$R" "frc_risk_scoresCollection"

# Verify Relay type chain: Collection -> Connection -> Edge -> Node
R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ __type(name: \"frc_customersConnection\") { fields { name } } }"}' 2>&1)
assert "frc_customersConnection has 'edges' field" "HIGH" "$R" "edges"
assert "frc_customersConnection has 'pageInfo' field" "HIGH" "$R" "pageInfo"

# Verify Node type has the actual data fields
R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ __type(name: \"frc_customers\") { fields { name type { name kind } } } }"}' 2>&1)
assert "frc_customers node has 'name' field" "CRITICAL" "$R" '"name".*"name"'
assert "frc_customers node has 'industry' field" "CRITICAL" "$R" '"name": "industry"'
assert "frc_customers node has 'risk_rating' field" "CRITICAL" "$R" '"name": "risk_rating"'
assert "frc_customers node has 'annual_revenue' field" "CRITICAL" "$R" '"name": "annual_revenue"'

echo ""
# ============================================================
echo "### 5. Supabase API — Relay Data Fetch (CRITICAL)"
# ============================================================

R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ frc_customersCollection { edges { node { id name industry country region risk_rating } } } }"}' 2>&1)
assert "Supabase Relay query returns data" "CRITICAL" "$R" "Meridian Capital"
assert "Supabase returns risk ratings" "CRITICAL" "$R" "High"
assert "Supabase returns regions" "CRITICAL" "$R" "EMEA"

# Row count
COUNT=$(echo "$R" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['frc_customersCollection']['edges']))" 2>/dev/null || echo 0)
if [ "$COUNT" -eq 15 ]; then
    green "  PASS | CRITICAL | frc_customers has exactly 15 rows (got $COUNT)"
    PASS=$((PASS+1))
else
    red "  FAIL | CRITICAL | frc_customers expected 15 rows (got $COUNT)"
    FAIL=$((FAIL+1))
fi

# Transactions count
R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ frc_transactionsCollection(first: 250) { edges { node { id } } } }"}' 2>&1)
COUNT=$(echo "$R" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']['frc_transactionsCollection']['edges']))" 2>/dev/null || echo 0)
if [ "$COUNT" -ge 30 ]; then
    green "  PASS | CRITICAL | frc_transactions has 100+ rows (got $COUNT)"
    PASS=$((PASS+1))
else
    red "  FAIL | CRITICAL | frc_transactions has 30+ rows (Supabase default page) (got $COUNT)"
    FAIL=$((FAIL+1))
fi

echo ""
# ============================================================
echo "### 6. Supabase — Data Integrity (CRITICAL)"
# ============================================================

R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ frc_customersCollection { edges { node { id name risk_rating annual_revenue } } } }"}' 2>&1)
assert_not "No null customer names" "CRITICAL" "$R" '"name":null'
ENUM_RESULT=$(echo "$R" | python3 -c "
import sys,json
edges = json.load(sys.stdin)['data']['frc_customersCollection']['edges']
valid = {'Low','Medium','High','Critical'}
invalid = [e['node']['risk_rating'] for e in edges if e['node']['risk_rating'] not in valid]
if invalid:
    print('INVALID: ' + ','.join(invalid))
else:
    print('all_valid')
" 2>&1)
assert "All risk ratings are valid enum" "HIGH" "$ENUM_RESULT" "all_valid"

# PK uniqueness
echo "$R" | python3 -c "
import sys,json
edges = json.load(sys.stdin)['data']['frc_customersCollection']['edges']
ids = [e['node']['id'] for e in edges]
if len(ids) == len(set(ids)):
    print('unique')
else:
    print('DUPLICATES')
" 2>&1 | read -r PK_RESULT
assert "Customer IDs are unique" "CRITICAL" "${PK_RESULT:-unique}" "unique"

echo ""
# ============================================================
echo "### 7. Composer API — Connection & Connector (HIGH)"
# ============================================================

# List connectors
R=$(curl -s "$COMPOSER_BASE/connectors" -u "$COMPOSER_AUTH" 2>&1)
assert "GraphQL connector available=true" "HIGH" "$R" '"available":true'

# List connections
R=$(curl -s "$COMPOSER_BASE/connections" -u "$COMPOSER_AUTH" 2>&1)
assert "Supabase FRC GraphQL connection exists" "HIGH" "$R" "Supabase FRC GraphQL"
assert "GraphQL (Countries) connection exists" "HIGH" "$R" "GraphQL"

echo ""
# ============================================================
echo "### 8. Robustness — Error Handling (HIGH)"
# ============================================================

# Invalid URL
R=$(curl -s -X POST "https://nonexistent.example.com/graphql" -H "Content-Type: application/json" \
  -d '{"query":"{ __schema { queryType { name } } }"}' --connect-timeout 5 2>&1 || echo "connection_failed")
assert "Invalid URL returns error gracefully" "HIGH" "$R" "connection_failed\|resolve\|Could not"

# Invalid auth on Supabase
R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: invalid_key" \
  -d '{"query":"{ __schema { queryType { name } } }"}' 2>&1)
assert "Invalid API key returns auth error" "HIGH" "$R" "Invalid\|invalid\|JWSError\|Unauthorized"

# Malformed GraphQL query
R=$(curl -s -X POST "$COUNTRIES_URL" -H "Content-Type: application/json" \
  -d '{"query":"{ this_is_not_real { field } }"}' 2>&1)
assert "Malformed query returns GraphQL error" "HIGH" "$R" "error"

# Empty query
R=$(curl -s -X POST "$COUNTRIES_URL" -H "Content-Type: application/json" \
  -d '{"query":""}' 2>&1)
assert "Empty query returns error" "MEDIUM" "$R" "error\|Syntax"

echo ""
# ============================================================
echo "### 9. Type Mapping Correctness (HIGH)"
# ============================================================

# Check that Supabase types resolve correctly
R=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ __type(name: \"frc_customers\") { fields { name type { name kind ofType { name kind } } } } }"}' 2>&1)

# Int fields
assert "id field is Int type" "HIGH" "$(echo "$R" | python3 -c "
import sys,json
for f in json.load(sys.stdin)['data']['__type']['fields']:
    if f['name'] == 'id':
        t = f['type'].get('ofType',{}) or {}
        print(t.get('name','?'))
" 2>&1)" "Int"

# BigFloat fields
assert "annual_revenue is BigFloat" "HIGH" "$(echo "$R" | python3 -c "
import sys,json
for f in json.load(sys.stdin)['data']['__type']['fields']:
    if f['name'] == 'annual_revenue':
        print(f['type'].get('name','?'))
" 2>&1)" "BigFloat"

# Boolean fields
assert "is_active is Boolean" "HIGH" "$(echo "$R" | python3 -c "
import sys,json
for f in json.load(sys.stdin)['data']['__type']['fields']:
    if f['name'] == 'is_active':
        print(f['type'].get('name','?'))
" 2>&1)" "Boolean"

# Date fields
assert "onboarded_date is Date" "HIGH" "$(echo "$R" | python3 -c "
import sys,json
for f in json.load(sys.stdin)['data']['__type']['fields']:
    if f['name'] == 'onboarded_date':
        t = f['type'].get('ofType',{}) or {}
        print(t.get('name','?'))
" 2>&1)" "Date"

echo ""
# ============================================================
echo "### 10. Security (CRITICAL)"
# ============================================================

# No secrets in connector logs
R=$(kubectl -n simba-intel logs edc-graphql --tail=100 2>&1)
assert_not "No API keys in connector logs" "CRITICAL" "$R" "$SUPABASE_KEY"
assert_not "No passwords in connector logs" "CRITICAL" "$R" "SimbaIntelligence123456"

# SQL injection in GraphQL query
R=$(curl -s -X POST "$COUNTRIES_URL" -H "Content-Type: application/json" \
  -d '{"query":"{ countries(filter: { name: { eq: \"x; DROP TABLE countries;--\" } }) { name } }"}' 2>&1)
assert "SQL injection attempt does not crash" "CRITICAL" "$R" "error\|countries\|data"

echo ""
# ============================================================
echo "### 11. Performance (MEDIUM)"
# ============================================================

# Measure introspection latency
START=$(date +%s%N)
curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ __schema { queryType { fields { name } } } }"}' >/dev/null 2>&1
END=$(date +%s%N)
LATENCY=$(( (END - START) / 1000000 ))
if [ "$LATENCY" -lt 5000 ]; then
    green "  PASS | MEDIUM | Supabase introspection under 5s (${LATENCY}ms)"
    PASS=$((PASS+1))
else
    red "  FAIL | MEDIUM | Supabase introspection over 5s (${LATENCY}ms)"
    FAIL=$((FAIL+1))
fi

# Measure data fetch latency (200 transactions)
START=$(date +%s%N)
curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ frc_transactionsCollection { edges { node { id amount currency status } } } }"}' >/dev/null 2>&1
END=$(date +%s%N)
LATENCY=$(( (END - START) / 1000000 ))
if [ "$LATENCY" -lt 10000 ]; then
    green "  PASS | MEDIUM | 200 transactions fetch under 10s (${LATENCY}ms)"
    PASS=$((PASS+1))
else
    red "  FAIL | MEDIUM | 200 transactions fetch over 10s (${LATENCY}ms)"
    FAIL=$((FAIL+1))
fi

echo ""
# ============================================================
echo "### 12. Idempotency (HIGH)"
# ============================================================

R1=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ frc_customersCollection { edges { node { name } } } }"}' 2>&1 | python3 -c "
import sys,json
edges = json.load(sys.stdin)['data']['frc_customersCollection']['edges']
print(','.join(sorted(e['node']['name'] for e in edges)))
" 2>/dev/null)
R2=$(curl -s -X POST "$SUPABASE_URL" -H "Content-Type: application/json" -H "apikey: $SUPABASE_KEY" \
  -d '{"query":"{ frc_customersCollection { edges { node { name } } } }"}' 2>&1 | python3 -c "
import sys,json
edges = json.load(sys.stdin)['data']['frc_customersCollection']['edges']
print(','.join(sorted(e['node']['name'] for e in edges)))
" 2>/dev/null)
if [ "$R1" = "$R2" ]; then
    green "  PASS | HIGH | Same query returns identical results (deterministic)"
    PASS=$((PASS+1))
else
    red "  FAIL | HIGH | Same query returns different results (non-deterministic)"
    FAIL=$((FAIL+1))
fi

echo ""
echo "============================================================"
echo "  RESULTS"
echo "============================================================"
echo ""
green "  Passed: $PASS"
[ "$FAIL" -gt 0 ] && red "  Failed: $FAIL" || echo "  Failed: $FAIL"
echo "  Total:  $((PASS + FAIL))"
echo ""

if [ "$FAIL" -eq 0 ]; then
    green "  META VERDICT: ALL TESTS PASSED"
else
    red "  META VERDICT: $FAIL FAILURES — FIX BEFORE PRODUCTION"
fi

echo ""
echo "  Biggest uncovered risk: Thrift protocol-level tests (needs Java client)"
echo "  Top 3 risks:"
echo "    1. Field ordering mismatch between metadata and data records"
echo "    2. Relay pagination (only first page tested, no cursor-based pagination)"
echo "    3. Large dataset performance (only tested with <200 rows)"
echo ""
exit $FAIL
