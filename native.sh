#!/usr/bin/env bash
rm -rf target
./mvnw -Pnative -DskipTests native:compile  && ./target/vault 
