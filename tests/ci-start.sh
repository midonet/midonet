#!/bin/bash

# We assume all gates/nightlies put the necessary packages in $WORKSPACE
# so we know where to find them.
cp midolman*.deb tests/sandbox/override_v2/midolman
cp midonet-cluster*.deb tests/sandbox/override_v2/api
cp python-midonetclient*.deb tests/sandbox/override_v2/api

# Necessary software in the host, we assume packages are already present on
# the corresponding override, in this case the v2 override.
sudo dpkg -i python-midonetclient*.deb

# Install sandbox, pulled as submodule
pushd midonet-sandbox
sudo python setup.py install
popd

# Start sandbox
pushd tests/
sudo sandbox-manage -c sandbox.conf \
					run default_v2 \
					--name=mdts \
                    --override=sandbox/override_v2 \
                    --provision=sandbox/provisioning/bgp-l2gw-provisioning.sh

popd
