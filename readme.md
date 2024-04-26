# readme

## run vault
`docker compose up`

## put a secret in vault 
`vault kv put secret/vault-demo db-password=tiger `

## get a secret from vault
`vault kv get secret/db`