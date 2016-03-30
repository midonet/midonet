# Workaround to test the compat test on the CI
tests/ci-stop.sh

tests/ci-start.sh -f default_v2_neutron+kilo+compat -o override_v2_compat -p provisioning/keystone-provisioning.sh

tests/ci-compat.sh

tests/ci-stop.sh
