#! /bin/sh

set -e
set -x

docker run midonet-tester-unit
docker run --privileged midonet-tester-integration-netlink
docker run --privileged midonet-tester-integration-midolman
