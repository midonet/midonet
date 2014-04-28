#!/bin/bash

# this script is used to insert marker string
# to midolman.log.
#

[ $# -eq 1 ] || {
    echo Usage: $0 marker
    exit 1
}

marker=$1

for d in /var/log/midolman*; do
    echo $marker >> $d/midolman.log
done
