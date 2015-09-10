#! /bin/sh

# This script removes all dhcp ports.
# Expected to be used for clean-up after a migration to
# MidoNet metadata proxy.
# See docs/openstack-metadata-usage.md for more information.

# Note: GNU variant of xargs is assumed
neutron port-list -c id -c device_owner|awk '$4 == "network:dhcp" {print $2}'|xargs -n1 --no-run-if-empty neutron port-delete
