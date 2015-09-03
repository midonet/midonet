#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST_CLIENT=$(ls override/python-midonetclient*deb | tail -n1)
LATEST_CLUSTER=$(ls override/midonet-cluster*deb | tail -n1)
LATEST_TOOLS=$(ls override/midonet-tools*deb | tail -n1)
dpkg -r midonet-cluster
dpkg -r midonet-tools
dpkg -r python-midonetclient
dpkg -i --force-confnew $LATEST_CLIENT $LATEST_CLUSTER $LATEST_TOOLS

# TODO: remove this once the midonet-cluster points to port 8080 by default
# Update midonet-cli to point to cluster
sed -i 's/api_url = .*$/api_url = http:\/\/localhost:8181\/midonet-api/' /root/.midonetrc

# Run cluster
exec /run-midonetcluster.sh
