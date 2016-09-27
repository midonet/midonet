#!/bin/bash

SANDBOX_NAME="mdts"
SANDBOX_FLAVOUR="default_v2_neutron+mitaka"
OVERRIDE="sandbox/override_v2"
PROVISIONING="sandbox/provisioning/all-provisioning.sh"
JENKINS_VERSION="v1"

while getopts ":f:o:p:hu" opt; do
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
    u)
        JENKINS_VERSION="v2"
        ;;
    esac
done

# echo network address to make it easy to find the jenkins machine
# the job is working on
ip address

# WARNING: this script is meant to be used on the CI, as it pulls images from
# the CI infrastructure (artifactory)

sudo modprobe openvswitch
sudo modprobe 8021q

# python SSH library (paramiko) dependencies on SSL
# TODO: remove once Jenkins slave image has been regenerated including it
sudo apt-get install --no-install-recommends -y libssl-dev libffi-dev

# Upgrade docker daemon to latest version and configure to use v2 registry in artifactory
if [ "$JENKINS_VERSION" == "v1" ]; then
    sudo apt-get install -qy -o Dpkg::Options::="--force-confnew" --only-upgrade docker-engine=1.12.1-0~trusty
    sudo sed -i 's/^#DOCKER_OPTS=.*/DOCKER_OPTS="--insecure-registry artifactory-v2.bcn.midokura.com"/' /etc/default/docker
    sudo service docker restart
fi

# create virtualenv for sandbox and mdts
sudo pip install --upgrade pip setuptools virtualenv
virtualenv venv
. venv/bin/activate

# We assume all gates/nightlies put the necessary packages in $WORKSPACE
# so we know where to find them.
mkdir -p tests/$OVERRIDE/packages
cp midolman*.deb tests/$OVERRIDE/packages
cp midonet-tools*.deb tests/$OVERRIDE/packages
cp midonet-cluster*.deb tests/$OVERRIDE/packages
cp python-midonetclient*.deb tests/$OVERRIDE/packages

# Necessary software in the host, midonet-cli installed from sources
cd python-midonetclient
python setup.py install
cd -

# Install sandbox, directly from repo (ignoring submodule)
rm -rf midonet-sandbox
git clone --depth=1 https://github.com/midonet/midonet-sandbox.git
cd midonet-sandbox
python setup.py install
cd -

# install this early due to https://datastax-oss.atlassian.net/browse/PYTHON-656
pip install Cython==0.24.1

# Install mdts deps, on top of sandbox deps
pip install -r tests/mdts.dependencies

# Start sandbox
cd tests/
echo "docker_registry=artifactory-v2.bcn.midokura.com" >> sandbox.conf
echo "docker_insecure_registry=True" >> sandbox.conf
sandbox-manage -c sandbox.conf pull-all $SANDBOX_FLAVOUR
sandbox-manage -c sandbox.conf \
                    run $SANDBOX_FLAVOUR \
                    --name=$SANDBOX_NAME \
                    --override=$OVERRIDE \
                    --provision=$PROVISIONING
cd -
