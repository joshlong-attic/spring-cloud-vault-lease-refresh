#!/bin/bash
set -e -o pipefail

vault secrets enable -path='database' database

vault write database/config/payments \
	plugin_name=postgresql-database-plugin \
	connection_url='postgresql://{{username}}:{{password}}@postgres:5432/payments' \
	allowed_roles="payments-app" \
	username="postgres" \
	password="postgres-admin-password"

vault write database/roles/payments-app \
	db_name=payments \
	creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; \
		GRANT ALL PRIVILEGES ON payments TO \"{{name}}\";" \
	default_ttl="1m" \
	max_ttl="2m"
