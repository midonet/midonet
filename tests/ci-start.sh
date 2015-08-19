#!/bin/bash

# We assume all gates/nightlies put the necessary packages in $WORKSPACE
# so we know where to find them.

# Install all packages
sudo dpkg -i *.deb

pushd tests/mmm
./init
./boot
popd
