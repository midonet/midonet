#!/bin/bash

./gradlew clean midolman:test nsdb:test midonet-util:test netlink:test \
  midonet-cluster:test midonet-tools:test python-midonetclient:test \
  cobertura -x integration -x midonet-cluster:test
