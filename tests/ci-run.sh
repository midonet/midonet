pushd tests/mdts/tests/functional_tests

./run_tests.sh -r $WORKSPACE/tests -l logs \
    -t test_vpnaas.py:test_ping_between_three_sites \
    -t test_vpnaas.py:test_non_vpn_subnet \
    -t test_vpnaas.py:test_security_groups \
    -t test_vpnaas.py:test_ipsec_site_connection_deletion \
    -t test_vpnaas.py:test_container_migration \
    -t test_vpnaas.py:test_container_restored_on_agent_restart \
    -t test_vpnaas.py:test_container_maintained_on_cluster_restart \
    -t test_bgp.py:test_multisession_icmp_with_redundancy \
    -t test_bgp.py:test_multisession_icmp_failback \
    -t test_bgp.py:test_snat \
    -t test_chains.py \
    -t test_l2gw.py:test_icmp_from_mn \
    -t test_ipfrag.py \
    -t test_conn_tracking.py \
    -t test_midolman_and_interfaces.py \
    -t test_mirroring.py \
    -t test_nat_router.py \
    -t test_tracing.py \
    -t test_vxlangw.py
popd
