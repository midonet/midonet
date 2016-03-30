# Workaround to test the compat test on the CI
tests/ci-stop.sh

sudo docker rm $(docker ps -a -q)

tests/ci-start.sh -f default_v2_neutron+kilo+compat -o sandbox/override_v2_compat -p sandbox/provisioning/keystone-provisioning.sh

sleep 5m

tests/ci-compat.sh

tests/ci-stop.sh
