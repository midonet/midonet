#!/usr/bin/env bash

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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

