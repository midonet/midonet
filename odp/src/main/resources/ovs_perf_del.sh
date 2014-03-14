#!/bin/bash

set -x

ns=perft-ns
dpif=perft-if
nsif=perft-eth

ip netns exec $ns ip link set lo down
ip netns exec $ns ip link set $nsif down
ip link delete $dpif
ip netns delete $ns
