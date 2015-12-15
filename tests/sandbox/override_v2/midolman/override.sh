#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST=$(ls /override/midolman*deb | tail -n1)
LATEST_TOOLS=$(ls /override/midonet-tools*deb | tail -n1)
dpkg -r midolman
dpkg -r midonet-tools
dpkg -i --force-confnew $LATEST_TOOLS $LATEST

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midolman/midolman-env.sh

exec /run-midolman.sh
