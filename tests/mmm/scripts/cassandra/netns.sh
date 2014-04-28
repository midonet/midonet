#! /bin/sh

netns=$1; shift

exec ip netns exec $netns unshare -m sh mountns.sh $*
