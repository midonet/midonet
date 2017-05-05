#!/bin/bash

# Install the latest packages from the local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
apt-get  update

# We need to create the vpp init script because the vpp package
# will fail otherwise if the upstart process is not running.
# This is a test specific configuration as the package would
# install under normal circumstances (upstart running).
touch /etc/init.d/vpp

# Failfast if we cannot update the packages locally
DEBIAN_FRONTEND=noninteractive \
apt-get install -qy --force-yes midolman/local \
                                midonet-tools/local vpp vpp-lib || exit 1

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midolman/midolman-env.sh

MIDOLMAN_ENV_FILE='/etc/midolman/midolman-env.sh'
sudo sed -i 's/\(MAX_HEAP_SIZE=\).*$/\1256M/' $MIDOLMAN_ENV_FILE
MINIONS_ENV_FILE='/etc/midolman/minions-env.sh'
sudo sed -i 's/\(MAX_HEAP_SIZE=\).*$/\1128M/' $MINIONS_ENV_FILE

# Enable offheap tables for midolman1
if [ $(hostname) = "midolman1" ]; then
    mn-conf set -h local <<EOF
agent.midolman.off_heap_tables=true
EOF
fi

exec /run-midolman.sh
