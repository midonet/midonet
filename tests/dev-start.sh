#!/usr/bin/env bash

# Setup basic requirements
./setup_test_server

pushd ..

# Install sandbox
git clone --recursive http://github.com/midonet/midonet-sandbox

# Install protobufs
wget https://github.com/google/protobuf/releases/download/v2.6.1/protobuf-2.6.1.tar.gz
tar -xzf protobuf-2.6.1.tar.gz
cd protobuf-2.6.1
./configure
make
sudo make install
sudo ldconfig
cd -
rm -rf protobuf-2.6.1
rm protobuf-2.6.1.tar.gz

# Build MidoNet packages
git submodule update --init --recursive
./gradlew clean
./gradlew -x test debian
find . -name "*.deb" | xargs sudo dpkg -i

# Install virtual env and activate
virtualenv venv
source venv/bin/activate

# Install dependent libraries
pushd tests && pip install -r mdts.dependencies && popd
pushd midonet-sandbox && python setup.py install && popd
pushd python-midonetclient && python setup.py install && popd

popd

# Build sandbox
sandbox-manage -c sandbox.conf build-all default_v2

pushd .. && copy_to_override.sh override_v2 && popd

# Run sandbox
sandbox-manage -c sandbox.conf run default_v2 --name=mdts \
  --override=sandbox/override_v2 \
  --provision=sandbox/provisioning/all-provisioning.sh

