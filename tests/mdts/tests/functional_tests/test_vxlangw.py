from nose.plugins.attrib import attr
from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.tests.utils.asserts import *
from mdts.tests.utils import *

from hamcrest import *
from nose.tools import nottest

import logging
import subprocess

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_vxlangw.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_vxlangw.yaml')
BM = BindingManager(PTM, VTM)

bindings1 = {
    'description': 'on single MM',
    'bindings': [
        {'binding':
             {'device_name': 'bridge-000-001', 'port_id': 1,
              'host_id': 1, 'interface_id': 1}}
        ]
    }

# TODO(tomohiko) Move those to the virtual topology data file.

vtep_management_ip = '10.0.0.101' # The emulator's MGMT ip
vtep_management_port = '6632' # The emulator's MGMT port
port_name = 'in6' # Preconfigured in the VTEP emulator
vlan_id = 0 # Preconfigured in the VTEP emulator

# Hosts that can talk to the VTEP should be added to this tunnel zone, the
# membership IP determines the src ip that the host will set in the vxlan
# tunnelled packets, as well as the IPs injected in the VTEP as flooding proxy.
vtep_tz_name = 'vteptz'

# The device connected to the emulator. Make sure that the
# mmm_physical_test_vxlangw defines a VM plugged to the host that belongs to
# the same range
vm_on_vtep = '10.0.2.26'

# The VTEP itself must be able to communicate directly with this IP, which is
# Midolmans host. This can be tricky. In the MidoCloud, this involves:
# - Both VMs holding the VTEP and the MMM box should belong to a network 10.0.0.0/24
# - Configure the neutron network so that it only assigns IPs between .99 and .102, or
#   some range that will not overlap with any namespace inside MMM
# - Assuming eth1 is the interface on the MMM box with the 10.0.0.x IP:
#   - Remove any route that sends 10.0.0.0/24 towards the
#     physical ifc. This is so the kernel gives that traffic to MMM's br0
#   - Remove he ip from the interface (ifconfig eth1 0)
#   - Add eth1 to the br0 bridge (brctl addif br0 eth1)
#   - Ask a MidoCloud operator to remove the rules from this port that DROP
#     traffic that doesn't have the VM's src IP and MAC
#
# At this point, the MMM host (whose IP we're setting below) will send straight
# to the VTEP from its own IP, as if it was a real host in the 10.0.0.0/24
# network
_host_ip = '10.0.0.8'

def setup():
    PTM.build()
    VTM.build()

    # Sets up a VTEP and add a binding.
    set_up_vtep()


def teardown():
    time.sleep(2)
    # Need to manually delete all VTEPs and their bindings if any.
    # TODO(tomohiko) Remove once the wrapper classes are implemented.
    vteps = VTM._api.get_vteps()
    for vtep in vteps:
        LOG.debug('Clean up a VTEP at: %s', vtep.get_management_ip())
        for binding in vtep.get_bindings():
            binding.delete()
            LOG.debug('Deleted a VTEP binding: %s, %s, %s',
                      binding.get_port_name(),
                      binding.get_vlan_id(),
                      binding.get_network_id())
        vtep.delete()
        LOG.debug('Deleted a VTEP at %s' % vtep.get_management_ip())

    tzs = VTM._api.get_tunnel_zones()
    tz = [t for t in tzs if t.get_name() == vtep_tz_name]
    tz[0].delete()

    time.sleep(2)
    PTM.destroy()
    VTM.destroy()


@nottest
@bindings(bindings1)
def test_ping_host_on_vtep():
    '''Tests if a VM can ping an IP address behind a VTEP.'''
    sender = BM.get_iface_for_port('bridge-000-001', 1)

    pcap_filter = 'src host %s and icmp' % vm_on_vtep

    # Ping an IP address on the physical VTEP from a VM on a virtual bridge.
    f1 = sender.ping_ipv4_addr(vm_on_vtep)
    f2 = async_assert_that(sender, receives(pcap_filter, within_sec(5)))
    wait_on_futures([f1, f2])


def set_up_vtep():
    '''Helper function to set up a VTEP and a binding.

    Part of this setup should be declared in the virtual topology data,
    and be taken care of by VirtualTopologyManager, but the VTEP and
    VTEP binding wrappers for MDTS haven't been implemented yet, so they
    need to be set up by calling Python MidoNet Client directly here.

    Creating a VTEP involves:
    - Ensuring that a tunnel zone exists for VTEPs (type vtep)
    - Creating a new VTEP entity, using the above VTEP

    TODO(tomohiko) Implement MDTS wrapper for VTEP and VTEP binding.
    '''
    LOG.debug('Setting up a VxLAN GW.')
    api = VTM._api

    host_1 = None
    for h in PTM._hosts:
        host = h['host']
        if host.get('id') == 1: host_1 = host

    # TODO: support this via topology, see MN-2623
    LOG.debug('Creating a new VTEP tunnel zone')
    tz = api.add_vtep_tunnel_zone()
    tz.name(vtep_tz_name)
    tz.type('vtep')
    tz = tz.create()

    LOG.debug('Adding host 1 to the VTEP tunnel zone %s %s', tz, host_1)
    tzh = tz.add_tunnel_zone_host()
    tzh.ip_address(_host_ip)
    tzh.host_id(host_1.get('mn_host_id'))
    tzh = tzh.create()

    vtep = api.add_vtep()\
             .name('My VTEP')\
             .management_ip(vtep_management_ip)\
             .management_port(vtep_management_port)\
             .tunnel_zone_id(tz.get_id())\
             .create()

    LOG.debug('Created a VTEP at %s' % vtep_management_ip)

    # Add a new VTEP binding.
    # Look up a bridge with which to bind the VTEP.
    bridge = VTM.get_bridge('bridge-000-001')
    bridge_id = bridge._mn_resource.get_id()
    vtep.add_binding()\
        .port_name(port_name)\
        .vlan_id(vlan_id)\
        .network_id(bridge_id)\
        .create()

    LOG.debug('Added a binding to bridge %s', bridge_id)
