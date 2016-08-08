#
# Copyright 2016 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This file is a handy way of setting attributes to test functions so it can
# be filtered by nose test tool using logical expressions.
#
# NOTE: this only works in nose, not in nose2, and the filename has to be
#       test*.py, and lexicographically first, so it is loaded before the
#       tests it is marking. Hence the current name of test-attr.py instead of
#       test_attr.py (since in ASCII '-' is placed before '_')

import test_bgp
import test_bridge
import test_chains
import test_conn_tracking
import test_delete_port
import test_ipfrag
import test_l2gw
import test_l4state
import test_load_balancer
import test_midolman_and_interfaces
import test_nat_router
import test_router
import test_vxlangw

test_bgp.test_icmp_failback.gate=1
test_bgp.test_icmp_failback.slow=1
test_bgp.test_mn_1172.slow=1
test_bgp.test_snat.gate=1
test_bgp.test_snat.slow=1
test_chains.test_dst_mac_masking.gate=1
test_chains.test_filter_ipv6.gate=1
test_chains.test_src_mac_masking.gate=1
test_conn_tracking.test_connection_tracking_by_network_addres.gate=1
test_conn_tracking.test_connection_tracking_with_drop_by_dl.gate=1
test_conn_tracking.test_filtering_by_dl.gate=1
test_conn_tracking.test_filtering_by_network_address.gate=1
test_ipfrag.test_icmp_bridge.gate=1
test_ipfrag.test_icmp_router.gate=1
test_ipfrag.test_udp_bridge.gate=1
test_ipfrag.test_udp_router.gate=1
test_l2gw.test_failback_on_generic_failure_with_icmp_from_mn.slow=1
test_l2gw.test_failback_on_generic_failure_with_icmp_to_mn.slow=1
test_l2gw.test_failback_on_ifdown_with_icmp_from_mn.slow=1
test_l2gw.test_failback_on_ifdown_with_icmp_to_mn.slow=1
test_l2gw.test_failover_on_generic_failure_with_icmp_to_mn.slow=1
test_l2gw.test_failover_on_ifdown_with_icmp_to_mn.slow=1
test_l2gw.test_icmp_from_mn.gate=1
test_load_balancer.test_multi_member_loadbalancing.gate=1
test_load_balancer.test_multi_member_loadbalancing.flaky=1
test_load_balancer.test_disabling_topology_loadbalancing.flaky=1 # MI-618
test_load_balancer.test_haproxy_failback.flaky=1 # MI-751
test_load_balancer.test_health_monitoring_backend_failback.flaky=1 # MI-751
test_midolman_and_interfaces.test_host_status.gate=1
test_midolman_and_interfaces.test_new_interface_becomes_visible.gate=1
test_nat_router.test_dnat.gate=1
test_nat_router.test_dnat_for_udp.gate=1
test_nat_router.test_floating_ip.gate=1
test_nat_router.test_snat.gate=1
test_nat_router.test_snat_for_udp.gate=1
test_vxlangw.test_to_multi_vtep_multi_tz.gate=1
test_vxlangw.test_to_multi_vtep_multi_tz.flaky=1
test_vxlangw.test_to_multi_vtep_single_tz.gate=1
test_vxlangw.test_to_multi_vtep_single_tz.flaky=1
test_vxlangw.test_to_single_vtep_multi_bridge.gate=1
test_vxlangw.test_to_single_vtep_multi_bridge.flaky=1
test_vxlangw.test_to_single_vtep_single_bridge.gate=1
test_vxlangw.test_to_single_vtep_single_bridge.flaky=1
