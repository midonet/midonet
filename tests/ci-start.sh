#!/bin/bash
set -ex

# WARNING: this script is meant to be used on the CI, as it pulls images from
# the CI infrastructure (artifactory)

sudo modprobe openvswitch
sudo modprobe 8021q

# install python dependencies (may have changed since image build)
sudo pip install -r tests/mdts.dependencies

# Install sandbox
sudo rm -rf midonet-sandbox
git clone https://github.com/midonet/midonet-sandbox.git
pushd midonet-sandbox
sudo python setup.py install
popd

# We assume all gates/nightlies put the necessary packages in $WORKSPACE
# so we know where to find them.
mkdir -p tests/sandbox/override_v1/packages
cp midolman*.deb tests/sandbox/override_v1/packages
cp midonet-api*.deb tests/sandbox/override_v1/packages
cp python-midonetclient*.deb tests/sandbox/override_v1/packages

# Necessary software in the host, we assume packages are already present on
# the corresponding override, in this case the v1 override.
sudo dpkg -i python-midonetclient*.deb

# Start sandbox
pushd tests/
SANDBOX_FLAVOUR="default_v1.9"
sudo sandbox-manage -c sandbox.conf pull-all $SANDBOX_FLAVOUR
sudo sandbox-manage -c sandbox.conf \
                    run $SANDBOX_FLAVOUR \
                    --name=mdts \
                    --override=sandbox/override_v1 \
                    --provision=sandbox/provisioning/all-provisioning.sh

popd
