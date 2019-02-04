#! /bin/sh

set -e

./build.sh
# NOTE(yamamoto): org.midonet.odp.OvsTest at least needs openvswitch.
# Ignore errors as it might not be critical.
sudo modprobe openvswitch || :
./run_tests.sh
