#!/bin/bash

set -x

ns=ovstest-ns

dpifa=ovstest-foo
nsifa=ovstest-foo-e

dpifb=ovstest-bar
nsifb=ovstest-bar-e

dpifc=ovstest-baz
nsifc=ovstest-baz-e

ip netns add $ns

ip link add name $dpifa type veth peer name $nsifa
ip link add name $dpifb type veth peer name $nsifb
ip link add name $dpifc type veth peer name $nsifc

ip link set $dpifa up
ip link set $dpifb up
ip link set $dpifc up

ip link set $nsifa netns $ns
ip link set $nsifb netns $ns
ip link set $nsifc netns $ns

ip netns exec $ns ip link set $nsifa up
ip netns exec $ns ip link set $nsifb up
ip netns exec $ns ip link set $nsifc up

ip netns exec $ns ip address add 100.0.10.2/24 dev $nsifa
ip netns exec $ns ip address add 100.0.10.3/24 dev $nsifb
ip netns exec $ns ip address add 100.0.10.4/24 dev $nsifc

ip netns exec $ns ifconfig lo up
