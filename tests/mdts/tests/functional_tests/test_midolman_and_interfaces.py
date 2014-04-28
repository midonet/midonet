"""
Tests if Midolman agent / interface status update
"""

from hamcrest import assert_that
from hamcrest import none
from hamcrest import not_none
from nose.plugins.attrib import attr

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.tests.utils.utils import get_midonet_api
from mdts.tests.utils.utils import start_midolman_agents
from mdts.tests.utils.utils import stop_midolman_agents
from mdts.tests.utils.utils import check_all_midolman_hosts

import time


# Only a physical topology containing a single interface.
PTM = PhysicalTopologyManager(
        '../topologies/mmm_physical_test_midolman_and_interfaces.yaml')


# We don't build a Physical Topology Manager in the setup. Instead we create
# a new interface inside 'test_new_interface_becomes_visible'.
def teardown():
    time.sleep(2)
    PTM.destroy()


@attr(version="v1.2.0", slow=False)
def test_host_status():
    """
    Title: Test host status update

    Scenario:
    When: The test starts up,
    Then: check if all Midolman agents are alive,
    Then: stops all Midolman agents,
    Then: check if all Midolman agents are now dead,
    Then: restarts all Midolman agetns,
    And: check again if all Midolman agents are alive,
    """
    midonet_api = get_midonet_api()
    check_all_midolman_hosts(midonet_api, alive=True)

    stop_midolman_agents()
    time.sleep(5)
    check_all_midolman_hosts(midonet_api, alive=False)

    start_midolman_agents()
    time.sleep(30)
    check_all_midolman_hosts(midonet_api, alive=True)


def get_interface(midonet_api, host_name, interface_name):
    """Returns an interface with the given name.

    Args:
        midonet_api: A MidonetApi instance
        host_name: A MidoNet host name.
        interface_name: An interface name.

        Returns:
            An interface if one is found with the specified host, otherwise
            None.
    """
    host = None
    for h in midonet_api.get_hosts():
        if h.get_id() == host_name: host = h
    # No matching host found. Return None.
    if not host: return None

    interface = None
    for i in host.get_interfaces():
        if i.get_name() == interface_name:
            interface = i
            break

    return interface


@attr(version="v1.2.0", slow=False)
def test_new_interface_becomes_visible():
    """
    Title: Test new interface becomes visible

    Scenario:
    When: On start up, a Midolman sees no interface,
    Then: adds a new interface,
    And: Midolman detects a new interface.
    """
    midonet_api = get_midonet_api()
    new_interface = get_interface(
            midonet_api, '00000000-0000-0000-0000-000000000001', 'interface_01')
    # Test that no interface with name 'interface_01' exists.
    assert_that(new_interface, none(), 'interface interface_01')

    # Create a new interface 'interface_01'.
    PTM.build()
    time.sleep(5)

    new_interface = get_interface(
            midonet_api, '00000000-0000-0000-0000-000000000001', 'interface_01')
    # Test that the created interface is visible.
    assert_that(new_interface, not_none(), 'interface interface_01.')
