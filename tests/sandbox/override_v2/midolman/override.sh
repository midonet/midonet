#!/bin/bash

# Installs newest package (lexicographycally) in override
LATEST=$(ls /override/midolman*deb | tail -n1)
dpkg -i --force-confnew $LATEST

exec /run-midolman.sh
