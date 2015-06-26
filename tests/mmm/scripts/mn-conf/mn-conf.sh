#!/bin/bash
# Global mn-conf script. This script is executed just after
# starting the zookeeper cluster and is supposed to contain
# mn-conf commands to provide global configuration to the
# midonet services (cluster, agent, etc.)

mn-conf set -t default <<EOF
agent.midolman.simulation_threads=2
EOF
