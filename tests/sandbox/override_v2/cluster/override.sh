#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST_CLIENT=$(ls override/python-midonetclient*deb | tail -n1)
LATEST_CLUSTER=$(ls override/midonet-cluster*deb | tail -n1)
LATEST_TOOLS=$(ls override/midonet-tools*deb | tail -n1)
dpkg -r midonet-cluster
dpkg -r midonet-tools
dpkg -r python-midonetclient
dpkg -i --force-confnew --force-confmiss $LATEST_CLIENT $LATEST_CLUSTER $LATEST_TOOLS

# Run cluster
exec /run-midonetcluster.sh
