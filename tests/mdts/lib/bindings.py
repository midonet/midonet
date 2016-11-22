# Copyright 2014 Midokura SARL
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

import fixtures
from fixtures import callmany
from mdts.lib.topology_manager import TopologyManager
from mdts.services import service
from mdts.tests.utils.utils import await_port_active
import uuid  # noqa

import logging

LOG = logging.getLogger(__name__)


class BindingType:
    API = 1
    MMCTL = 2


class BindingManager(fixtures.Fixture):

    def __init__(self, ptm=None, vtm=None):

        self._ptm = ptm
        if self._ptm is None:
            # Set a default topology manager
            self._ptm = TopologyManager()

        self._vtm = vtm
        self._cleanups = callmany.CallMany()
        self._mappings = {}

    def set_binding_data(self, data):
        self._data = data

    def get_binding_data(self):
        return self._data

    def _add_hosts_to_tunnel_zone(self):
        _tzone_name = self._ptm.get_default_tunnel_zone_name()
        agents = service.get_all_containers('midolman')
        for agent in agents:
            self._ptm.add_host_to_tunnel_zone(
                agent.get_hostname(), _tzone_name)

    def _update_addresses(self, iface, vport):
        if isinstance(vport, dict) and 'port' in vport and len(vport['port']['fixed_ips']) > 0:
            # This is a neutron port, discover which ip should be used
            n_subnet_id = vport['port']['fixed_ips'][0]['subnet_id']
            base_port = {'port': dict()}
            if 'ipv4_addr' in iface:
                # ip specified in the binding, override that from the vport
                # and update the neutron db
                base_port['port']['fixed_ips'] = [
                    {'subnet_id': n_subnet_id,
                     'ip_address': iface['ipv4_addr'][0].split('/')[0]}
                ]
                if 'hw_addr' in iface:
                    base_port['port']['mac_address'] = iface['hw_addr']

                self._vtm.api.update_port(vport['port']['id'], base_port)
            else:
                # ip from neutron dhcp, get the correct subnet cidr for
                # setting the actual interface ip
                subnet = self._vtm.get_resource(n_subnet_id)
                iface['hw_addr'] = vport['port']['mac_address']

                ipv4_addr = vport['port']['fixed_ips'][0]['ip_address']
                ipv4_cidr = subnet['subnet']['cidr'].split('/')[1]
                iface['ipv4_addr'] = [ipv4_addr + '/' + ipv4_cidr]
        # If not a neutron port, both ip and hw addr should be specified
        return dict(iface)

    def bind(self):
        # Schedule deletion of virtual and physical topologies
        self.addCleanup(self._ptm.destroy)
        self._ptm.build(self._data)
        self.addCleanup(self._vtm.destroy)
        self._vtm.build(self._data)
        self._add_hosts_to_tunnel_zone()
        for binding in self._data['bindings']:
            vport = self._vtm.get_resource(binding['vport'])
            bind_iface = binding['interface']
            if isinstance(bind_iface, dict):
                # We are specifying the vms inside the binding
                iface_def = self._update_addresses(bind_iface['definition'], vport)
                iface_type = bind_iface['type']
                hostname = bind_iface['hostname']
                host = service.get_container_by_hostname(hostname)
                iface = getattr(host, "create_%s" % iface_type)(**iface_def)
                self.addCleanup(getattr(host, "destroy_%s" % iface_type), iface)
            else:
                # It's a vm already created and saved as a resource
                iface = self._ptm.get_resource(binding['interface'])

            vport_id = self._get_port_id(vport)

            # Do the actual binding
            binding = iface.compute_host.bind_port(iface, vport_id)
            self.addCleanup(iface.compute_host.unbind_port, iface)
            self._mappings[vport_id] = iface
            await_port_active(vport_id)

    def unbind(self):
        self.cleanUp()
        self._cleanups = callmany.CallMany()
        self._mappings = {}

    def get_interface_on_vport(self, vport_name):
        """
        Given a virtual port (Neutron or Midonet), it returs the interface
        associated to this port.
        :param vport: A neutron or midonet virtual port object
        :rtype: mdts.services.interface.Interface
        """
        vport = self._vtm.get_resource(vport_name)
        return self._mappings[self._get_port_id(vport)]

    def _get_port_id(self, vport):
        """
        Helper method to get the virtual port id depending on the type of object
        :param vport: A neutron or midonet virtual port object
        :rtype: uuid
        """
        return vport['port']['id'] if isinstance(vport, dict) else vport.get_id()
