#!/bin/bash

set -x

ns=perft-ns
dpif=perft-if
nsif=perft-eth

ip netns add $ns                                        # make namespace
ip link add name $dpif type veth peer name $nsif        # create veth pair
ip link set $dpif up                                    # set dpport up
ip link set $nsif netns $ns                             # move veth to ns
ip netns exec $ns ip link set $nsif up                  # set verth mirror up
ip netns exec $ns ip address add 100.0.10.2/24 dev $nsif   # set ip of veth mirror
ip netns exec $ns ifconfig lo up                        # set lo mirror up
