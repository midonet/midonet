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

import logging
from mdts.lib.topology_manager import TopologyManager
from mdts.services import service
from mdts.tests.utils.utils import await_port_active
from mdts.tests.utils.utils import get_midonet_api
import sys

LOG = logging.getLogger(__name__)


class BindingManager(TopologyManager):

    def __init__(self, ptm, vtm):

        # Note that this ctor doesn't conform to the super's signature
        # calling super just to get a ref to self._api. perhaps
        # needs to be cleaned up.

        data = {'bogus_data': 'dummy'}
        super(BindingManager, self).__init__(
            None, data)

        self._ptm = ptm
        self._vtm = vtm
        self._port_if_map = {}
        self._host_id_map = {}
        self._interfaces = []

    def set_binding_data(self, data):
        self._data = data

    def get_binding_data(self):
        return self._data

    def bind(self, filename=None):
        self._ptm.build()
        self._vtm.build()
        # Get a new api ref to workaround previous zk failures
        self._api = get_midonet_api()

        bindings = self._data['bindings']
        for b in bindings:
            binding = b['binding']

            host_id = binding['host_id']
            iface_id = binding['interface_id']
            device_name = binding['device_name']
            port_id = binding['port_id']

            device_port = self._vtm.get_device_port(device_name, port_id)
            mn_vport = device_port._mn_resource
            if mn_vport.get_type() == 'InteriorRouter' or \
               mn_vport.get_type() == 'InteriorBridge':
                LOG.error("Cannot bind interior port")
                sys.exit(-1)  # TODO: make this fancier

            # FIXME: some hosts are specified by midonet host_id while others
            # are referenced by hostname. Need a coherent mechanism
            # Cleanup everything not related to bindings from here
            if 'host_id' in binding:
                host_id = binding['host_id']
                # FIXME:
                # Clean up yamls or remove them completely, this is so ugly
                _host = filter(
                    lambda x: x['host']['id'] == host_id,
                    self._ptm._hosts)[0]['host']
            elif 'hostname' in binding:
                hostname = binding['hostname']
                _host = filter(
                    lambda x: x['host']['hostname'] == hostname,
                    self._ptm._hosts)[0]['host']
            else:
                raise RuntimeError("Hosts in the binding should have a"
                                   "host_id or a hostname property")

            _interface = filter(
                lambda x: x['interface']['id'] == iface_id,
                _host['interfaces']
            )[0]['interface']

            mn_vport_id = mn_vport.get_id()
            host = service.get_container_by_hostname('midolman%s' % host_id)

            if _interface['type'] == 'netns':
                iface = host.create_vmguest(**_interface)
            elif _interface['type'] == 'trunk':
                iface = host.create_trunk(**_interface)
            else:  # provided
                iface = host.create_provided(**_interface)

            self._port_if_map[(device_name, port_id)] = iface

            iface.vport_id = mn_vport_id
            self._interfaces.append((iface, host))
            iface.clear_arp(sync=True)

            iface.vport_id = mn_vport_id
            host.bind_port(iface, mn_vport_id)
            await_port_active(mn_vport_id)

    def unbind(self):

        for iface, host in self._interfaces:
            # Remove binding
            host.unbind_port(iface)
            iface.destroy()

        # Destroy the virtual topology
        self._vtm.destroy()
        self._ptm.destroy()

        self._interfaces = []
        self._port_if_map = {}

    def get_iface_for_port(self, device_name, port_id):
        return self._port_if_map[(device_name, port_id)]

    def get_iface(self, host_id, iface_id):
        return self._ptm.get_interface(host_id, iface_id)
