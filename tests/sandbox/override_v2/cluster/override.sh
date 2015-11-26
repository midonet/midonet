#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST_CLIENT=$(ls override/python-midonetclient*deb | tail -n1)
LATEST_CLUSTER=$(ls override/midonet-cluster*deb | tail -n1)
LATEST_TOOLS=$(ls override/midonet-tools*deb | tail -n1)
dpkg -r midonet-cluster
dpkg -r midonet-tools
dpkg -r python-midonetclient
dpkg -i --force-confnew --force-confmiss $LATEST_CLIENT $LATEST_CLUSTER $LATEST_TOOLS

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midonet-cluster/midonet-cluster-env.sh

# Run cluster
exec /run-midonetcluster.sh
