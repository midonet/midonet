#!/bin/bash
# Global mn-conf script. This script is executed just after
# starting the zookeeper cluster and is supposed to contain
# mn-conf commands to provide global configuration to the
# midonet services (cluster, agent, etc.)
# e.g.:
# mn-conf set -t default <<EOF
# zookeeper.zookeeper_hosts="10.0.0.2:2181,10.0.0.3:2181,10.0.0.4:2181"
# cassandra.replication_factor=3
# cassandra.cluster=midonet
# agent.logger.root=DEBUG
# ...
# EOF
