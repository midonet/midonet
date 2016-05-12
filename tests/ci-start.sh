#!/bin/bash

SANDBOX_NAME="mdts"
SANDBOX_FLAVOUR="default_v2_neutron+kilo"
OVERRIDE="sandbox/override_v2"
PROVISIONING="sandbox/provisioning/all-provisioning.sh"

while getopts ":f:o:p:h" opt; do
    case $opt in
    f)
        SANDBOX_FLAVOUR=$OPTARG
        ;;
    o)
        OVERRIDE=$OPTARG
        ;;
    p)
        PROVISIONING=$OPTARG
        ;;
    h)
        echo "$0 [-f SANDBOX_FLAVOUR] [-o OVERRIDE_DIRECTORY]" \
             " [-p PROVISIONING_SCRIPT]"
        exit 1
        ;;
    esac
done

# WARNING: this script is meant to be used on the CI, as it pulls images from
# the CI infrastructure (artifactory)

sudo modprobe openvswitch
sudo modprobe 8021q
# install python dependencies (may have changed since image build)
virtualenv venv
source venv/bin/activate
pip install -r tests/mdts.dependencies

# We assume all gates/nightlies put the necessary packages in $WORKSPACE
# so we know where to find them.
mkdir -p tests/$OVERRIDE/packages
cp midolman*.deb tests/$OVERRIDE/packages
cp midonet-tools*.deb tests/$OVERRIDE/packages
cp midonet-cluster*.deb tests/$OVERRIDE/packages
cp python-midonetclient*.deb tests/$OVERRIDE/packages

# Necessary software in the host, midonet-cli installed from sources
pushd python-midonetclient
python setup.py install
popd

# Install sandbox, directly from repo (ignoring submodule)
rm -rf midonet-sandbox
git clone --depth=1 https://github.com/midonet/midonet-sandbox.git
pushd midonet-sandbox
python setup.py install
popd

# Start sandbox
pushd tests/
echo "docker_registry=artifactory.bcn.midokura.com" >> sandbox.conf
echo "docker_insecure_registry=True" >> sandbox.conf
sandbox-manage -c sandbox.conf pull-all $SANDBOX_FLAVOUR
sandbox-manage -c sandbox.conf \
                    run $SANDBOX_FLAVOUR \
                    --name=$SANDBOX_NAME \
                    --override=$OVERRIDE \
                    --provision=$PROVISIONING
popd
