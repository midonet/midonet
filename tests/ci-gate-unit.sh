#!/bin/bash

./gradlew clean midolman:test nsdb:test midonet-util:test netlink:test\
  midonet-cluster:test1 midonet-cluster:test2 midonet-cluster:test3 \
  midonet-tools:test python-midonetclient:test cobertura -x integration
