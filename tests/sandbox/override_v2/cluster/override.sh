#!/bin/bash

# Install the latest pagackes from the local repository
LOCAL_REPO_FILE=/etc/apt/sources.list.d/midonet-local.list
echo "deb file:/packages /" > $LOCAL_REPO_FILE
apt-get update -o Dir::Etc::sourcelist=$LOCAL_REPO_FILE

# Failfast if we cannot update the packages locally
apt-get install -qy --force-yes midonet-cluster/local \
                                midonet-tools/local \
                                python-midonetclient/local || exit 1

# Make sure we can access the remote management interface from outside the container
HOST_NAME=`hostname`
RESOLVED_HOST_NAME=`getent hosts $HOST_NAME`
IPADDRESS=${RESOLVED_HOST_NAME%% *}
sed -i "\$a JVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$IPADDRESS\"" /etc/midonet-cluster/midonet-cluster-env.sh

MIDOENT_CLUSTER_ENV_FILE='/etc/midonet-cluster/midonet-cluster-env.sh'
sed -i 's/\(MAX_HEAP_SIZE=\).*$/\1128M/' $MIDOENT_CLUSTER_ENV_FILE
sed -i 's/\(HEAP_NEWSIZE=\).*$/\164M/' $MIDOENT_CLUSTER_ENV_FILE


# Default mido_zookeeper_key
if [ -z "$MIDO_ZOOKEEPER_ROOT_KEY" ]; then
    MIDO_ZOOKEEPER_ROOT_KEY=/midonet/v1
fi

# Update ZK hosts in case they were linked to this container
if [[ `env | grep _PORT_2181_TCP_ADDR` ]]; then
    MIDO_ZOOKEEPER_HOSTS="$(env | grep _PORT_2181_TCP_ADDR | sed -e 's/.*_PORT_2181_TCP_ADDR=//g' -e 's/^.*/&:2181/g' | sort -u)"
    MIDO_ZOOKEEPER_HOSTS="$(echo $MIDO_ZOOKEEPER_HOSTS | sed 's/ /,/g')"
fi

if [ -z "$MIDO_ZOOKEEPER_HOSTS" ]; then
    echo "No Zookeeper hosts specified neither by ENV VAR nor by linked containers"
    exit 1
fi

echo "Configuring cluster using MIDO_ZOOKEEPER_HOSTS: $MIDO_ZOOKEEPER_HOSTS"
echo "Configuring cluster using MIDO_ZOOKEEPER_ROOT_KEY: $MIDO_ZOOKEEPER_ROOT_KEY"

sed -i -e 's/zookeeper_hosts = .*$/zookeeper_hosts = '"$MIDO_ZOOKEEPER_HOSTS"'/' /etc/midonet/midonet.conf
sed -i -e 's/root_key = .*$/root_key = '"$(echo $MIDO_ZOOKEEPER_ROOT_KEY|sed 's/\//\\\//g')"'/' /etc/midonet/midonet.conf

cat << EOF > /root/.midonetrc
[zookeeper]
zookeeper_hosts = $MIDO_ZOOKEEPER_HOSTS
root_key = $MIDO_ZOOKEEPER_ROOT_KEY
EOF

mn-conf set -t default <<EOF
cluster.containers.scheduler_timeout="20s"
cluster.loggers.org.midonet.cluster.root=DEBUG
cluster.loggers.com.sun.jersey=INFO
cluster.topology_cache.enabled=false
EOF

# Run cluster
exec /run-midonetcluster.sh
