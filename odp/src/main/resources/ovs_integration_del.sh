#!/bin/bash

set -x

ns=ovstest-ns

dpifa=ovstest-foo
nsifa=ovstest-foo-e

dpifb=ovstest-bar
nsifb=ovstest-bar-e

dpifc=ovstest-baz
nsifc=ovstest-baz-e

ip netns exec $ns ip link set lo down
ip netns exec $ns ip link set $nsifa down
ip netns exec $ns ip link set $nsifb down
ip netns exec $ns ip link set $nsifc down
ip link delete $dpifa
ip link delete $dpifb
ip link delete $dpifc
ip netns delete $ns
