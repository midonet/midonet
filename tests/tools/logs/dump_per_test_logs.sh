#!/usr/bin/env bash

TOPDIR=$(cd $(dirname $0)/../../ && pwd)
TEST_CONFIG=$TOPDIR/testrc

[ -f $TEST_CONFIG ] || {
    echo "$TEST_CONFIG not found. Exitting..."
    exit 1
}

. $TEST_CONFIG

echo ==============
echo per test logs
echo ==============

echo ======
echo zkdump
echo ======

zkdump -z $ZK_NODES -d -p

DPCTL_TIMEOUT_IN_SEC=10
index=1
for mm_ns in $MIDOLMAN_NETNSs; do
    echo ==========
    echo midolman.$((index++))
    echo ==========

    SHOW_DP_CMDLINE="mm-dpctl --timeout $DPCTL_TIMEOUT_IN_SEC --show-dp midonet"
    echo
    echo == DP status: $SHOW_DP_CMDLINE
    echo

    ip netns exec $mm_ns $SHOW_DP_CMDLINE


    DUMP_DP_CMDLINE="mm-dpctl --timeout $DPCTL_TIMEOUT_IN_SEC --dump-dp midonet"
    echo
    echo == flow dump: $DUMP_DP_CMDLINE
    echo

    ip netns exec $mm_ns $DUMP_DP_CMDLINE
    echo
done

exit 0 # make sure that nose plugin doesn't die

