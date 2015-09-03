#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST=$(ls /override/midolman*deb | tail -n1)
LATEST_TOOLS=$(ls /override/midonet-tools*deb | tail -n1)
dpkg -i --force-confnew $LATEST_TOOLS $LATEST

exec /run-midolman.sh
