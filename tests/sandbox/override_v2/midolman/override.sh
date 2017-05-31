#!/bin/bash

# Include the misc testing repos for quagga 1.1.1
MISC_TESTING_REPO_FILE=/etc/apt/sources.list.d/midonet-misc-testing.list
echo "deb http://builds.midonet.org/misc testing main" > $MISC_TESTING_REPO_FILE

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
# --force-confnew is a necessary because the new midolman package
# being installed may contain changes in the configuration file.
DEBIAN_FRONTEND=noninteractive \
apt-get install -qy --force-yes \
        -o Dpkg::Options::="--force-confnew" \
        midolman/local midonet-tools/local vpp vpp-lib || exit 1

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midolman/midolman-env.sh

MIDOLMAN_ENV_FILE='/etc/midolman/midolman-env.sh'
sudo sed -i 's/\(MAX_HEAP_SIZE=\).*$/\1256M/' $MIDOLMAN_ENV_FILE
MINIONS_ENV_FILE='/etc/midolman/minions-env.sh'
sudo sed -i 's/\(MAX_HEAP_SIZE=\).*$/\1128M/' $MINIONS_ENV_FILE

# FIXME: changes to mn-conf should be passed to the run-midolman.sh script
# e.g. via ENV VAR. But that needs some work on the sandbox image and
# rebuilding it.
# Update ZK hosts in case they were linked to this container
if [[ `env | grep _PORT_2181_TCP_ADDR` ]]; then
    MIDO_ZOOKEEPER_HOSTS="$(env | grep _PORT_2181_TCP_ADDR | sed -e 's/.*_PORT_2181_TCP_ADDR=//g' -e 's/^.*/&:2181/g' | sort -u)"
    MIDO_ZOOKEEPER_HOSTS="$(echo $MIDO_ZOOKEEPER_HOSTS | sed 's/ /,/g')"
fi

sed -i -e 's/zookeeper_hosts = .*$/zookeeper_hosts = '"$MIDO_ZOOKEEPER_HOSTS"'/' /etc/midolman/midolman.conf

# Enable offheap tables for midolman1
# Force quagga 0.99.23 (v2 zebra protocol) on midolman1
if [ $(hostname) = "midolman1" ]; then
    mn-conf set -h local <<EOF
agent.midolman.off_heap_tables=true
EOF
    apt-get install -qy --force-yes quagga=0.99.23.1-0midokura
fi

# Install quagga 1.1.1 (v3 zebra protocol) on midolman2
if [ $(hostname) = "midolman2" ]; then
    apt-get install -qy --force-yes quagga=1.1.1-3
fi

exec /run-midolman.sh
