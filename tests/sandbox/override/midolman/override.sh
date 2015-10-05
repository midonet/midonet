#!/bin/sh

apt-get install python-setproctitle
# Installs newest package (lexicographycally) in override
LATEST=$(ls /override/midolman*deb | tail -n1)
dpkg -i $LATEST

exec ./run-midolman.sh
