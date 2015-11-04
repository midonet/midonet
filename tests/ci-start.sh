#!/bin/bash

sudo modprobe openvswitch
sudo modprobe 8021q
# install python dependencies (may have changed since image build)
sudo pip install -r tests/mdts.dependencies

# We assume all gates/nightlies put the necessary packages in $WORKSPACE
# so we know where to find them.
cp midolman*.deb tests/sandbox/override_v2/midolman
cp midonet-tools*.deb tests/sandbox/override_v2/midolman
cp midonet-cluster*.deb tests/sandbox/override_v2/cluster
cp midonet-tools*.deb tests/sandbox/override_v2/cluster
cp python-midonetclient*.deb tests/sandbox/override_v2/cluster

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
                    --provision=sandbox/provisioning/all-provisioning.sh

popd
