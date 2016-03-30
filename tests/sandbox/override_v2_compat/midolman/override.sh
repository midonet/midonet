#!/bin/bash

# Install the latest packages from the local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE

MIDONET_REPO_FILE=/etc/apt/sources.list.d/midonet.list
echo "deb http://192.168.30.4/artifactory/midonet-5-deb stable main" > $MIDONET_REPO_FILE

apt-get update

# Failfast if we cannot update the packages locally
apt-get install -qy --force-yes midolman=2:5.0.0 \
                                midonet-tools=2:5.0.0 || exit 1

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midolman/midolman-env.sh

exec /run-midolman.sh
