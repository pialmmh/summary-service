#!/usr/bin/env bash
# summary-service's OWN throwaway PostgreSQL 16 (and, with "kafka", Kafka 3.9) for the PostgreSQL tests.
# Everything listens on 127.0.0.1 only; PostgreSQL uses trust authentication, so no password exists anywhere.
# Never touches a container that is not named ss-*.
#
#   tools/lab/pg-lab.sh up        # PostgreSQL 16 on 127.0.0.1:7643, the switch database and the roles
#   tools/lab/pg-lab.sh mysql     # MySQL 5.7.44 on 127.0.0.1:7633 (empty root password) for the MySQL ITs
#   tools/lab/pg-lab.sh kafka     # Kafka 3.9 on 127.0.0.1:7692 (the ping and the doorbell)
#   tools/lab/pg-lab.sh down      # remove the three containers
#
# The database is made as ad-is-a-call §3 and prime-context's postgres-tenancy.md §3 say: database "routesphere" owned by
# prime_context; the roles ad_sphere, billing_core, summary_service (LOGIN); prime_context a member of the two that create
# tables. The tier schemas and their grants are made by the tests themselves (PgLab.provisionTier), statement for statement
# what prime-context's SchemaSharing runs.
set -euo pipefail

PG=ss-pg16-lab;      PG_PORT=7643
MY=ss-mysql57-lab;   MY_PORT=7633
KA=ss-kafka-lab;     KA_PORT=7692; KA_CTRL=7693

running() { docker ps --format '{{.Names}}' | grep -qx "$1"; }
exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$1"; }

pg_up() {
  if ! exists "$PG"; then
    docker run -d --name "$PG" -p 127.0.0.1:${PG_PORT}:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine -c wal_level=logical >/dev/null
  elif ! running "$PG"; then docker start "$PG" >/dev/null; fi
  for _ in $(seq 1 60); do docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
  docker exec -i "$PG" psql -U postgres -q -v ON_ERROR_STOP=1 <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'prime_context')   THEN CREATE ROLE prime_context LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ad_sphere')       THEN CREATE ROLE ad_sphere LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billing_core')    THEN CREATE ROLE billing_core LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'summary_service') THEN CREATE ROLE summary_service LOGIN; END IF;
END $$;
GRANT billing_core, summary_service TO prime_context;
SELECT 'CREATE DATABASE routesphere OWNER prime_context' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'routesphere') \gexec
SQL
  echo "postgres: 127.0.0.1:${PG_PORT} database routesphere (roles prime_context, ad_sphere, billing_core, summary_service; trust)"
}

mysql_up() {
  if ! exists "$MY"; then
    docker run -d --name "$MY" -p 127.0.0.1:${MY_PORT}:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:5.7.44 \
      --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null
  elif ! running "$MY"; then docker start "$MY" >/dev/null; fi
  for _ in $(seq 1 90); do docker exec "$MY" mysql -uroot -e 'select 1' >/dev/null 2>&1 && break; sleep 2; done
  echo "mysql: 127.0.0.1:${MY_PORT} root, empty password"
}

kafka_up() {
  if ! exists "$KA"; then
    docker run -d --name "$KA" -p 127.0.0.1:${KA_PORT}:${KA_PORT} -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
      -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:${KA_PORT},CONTROLLER://localhost:${KA_CTRL} \
      -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:${KA_PORT} -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
      -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
      -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:${KA_CTRL} -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
      -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 apache/kafka:3.9.0 >/dev/null
  elif ! running "$KA"; then docker start "$KA" >/dev/null; fi
  for _ in $(seq 1 60); do
    docker exec "$KA" /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:${KA_PORT} --list >/dev/null 2>&1 && break; sleep 1
  done
  echo "kafka: 127.0.0.1:${KA_PORT} PLAINTEXT"
}

down() {
  for c in "$PG" "$MY" "$KA"; do exists "$c" && docker rm -f "$c" >/dev/null && echo "removed $c"; done
  true
}

case "${1:-}" in
  up)    pg_up ;;
  mysql) mysql_up ;;
  kafka) kafka_up ;;
  all)   pg_up; mysql_up; kafka_up ;;
  down)  down ;;
  *)     echo "usage: $0 up | mysql | kafka | all | down"; exit 2 ;;
esac
