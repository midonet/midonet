#!/bin/bash

ns=perft-ns
dpif=perft-if
nsif=perft-eth

ip netns exec $ns mz $nsif \
  -A 100.0.10.2 -B 100.0.10.240 \
  -b 10:00:00:00:00:01 -t udp 'sp=1-10000,dp=1-10000' -c0
