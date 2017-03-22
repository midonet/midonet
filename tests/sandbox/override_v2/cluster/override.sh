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

mn-conf set -t default <<EOF
cluster.containers.scheduler_timeout="20s"
cluster.loggers.org.midonet.cluster.root=DEBUG
cluster.loggers.com.sun.jersey=INFO
EOF

# Run cluster
exec /run-midonetcluster.sh
