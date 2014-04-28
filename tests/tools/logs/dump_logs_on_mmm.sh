#!/bin/bash

TOPDIR=$(cd $(dirname $0) && pwd)

[ $(whoami) = "root" ] || {
    echo "Aborted. You must be root"
    exit 1
}

marker_begin=$1
marker_end=$2

echo =====================
echo = System Level Logs =
echo =====================

$TOPDIR/dump_system_level_logs.sh


echo ===================
echo = Node level logs =
echo ===================

BASE_NS_INDEX=8
NUM_MMs=3
for i in $(seq $NUM_MMs); do
    export MIDOLMAN_LOG_DIR=/var/log/midolman.$i/
    nsindex=$((BASE_NS_INDEX - 1 + i))
    nsname=ns`printf "%03d" $nsindex`
    ip netns exec $nsname $TOPDIR/dump_node_level_logs.sh "$marker_begin" "$marker_end"
done

exit 0 # make sure that nose plugin doesn't die
