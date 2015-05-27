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

from mdts.lib.topology_manager import TopologyManager

import logging
import sys

from time import sleep

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

    def bind(self, filename=None, data=None):

        self._data = self._get_data(filename, data)
        bindings = self._data['bindings']
        for b in bindings:
            binding = b['binding']

            host_id = binding['host_id']
            iface_id = binding['interface_id']
            device_name = binding['device_name']
            port_id = binding['port_id']

            self._port_if_map[(device_name, port_id)] = (host_id, iface_id)

            device_port = self._vtm.get_device_port(device_name, port_id)
            mn_vport = device_port._mn_resource
            if mn_vport.get_type() == 'InteriorRouter' or \
               mn_vport.get_type() == 'InteriorBridge':
                LOG.error("Cannot bind interior port")
                sys.exit(-1) # TODO: make this fancier

            mn_vport_id = mn_vport.get_id()
            iface = self._ptm.get_interface(host_id, iface_id)
            iface.clear_arp(sync=True)
            iface_name = iface.interface['ifname']
            mn_host_id = iface.host['mn_host_id']
            iface.vport_id = mn_vport_id

            self._api.get_host(mn_host_id).add_host_interface_port()\
                                       .port_id(mn_vport_id)\
                                       .interface_name(iface_name).create()
            self.await_port_active(mn_vport_id)

    def unbind(self):

        bindings = self._data['bindings']
        for b in bindings:
            binding = b['binding']

            host_id = binding['host_id']
            iface_id = binding['interface_id']

            iface = self._ptm.get_interface(host_id, iface_id)
            iface_name = iface.interface['ifname']
            mn_host_id = iface.host['mn_host_id']

            for hip in self._api.get_host(mn_host_id).get_ports():
                if hip.get_interface_name() == iface_name:
                    hip.delete()
                    iface.vport_id = None

        self._port_if_map = {}

    def get_iface_for_port(self, device_name, port_id):
        (host_id, iface_id) = self._port_if_map[(device_name, port_id)]
        return self._ptm.get_interface(host_id, iface_id)

    def get_iface(self, host_id, iface_id):
        return self._ptm.get_interface(host_id, iface_id)

    def await_port_active(self, vport_id):
        timeout = 60
        while not self._api.get_port(vport_id).get_active():
            sleep(1)
            timeout -= 1
            if timeout == 0:
                raise Exception("Port did not become active.")
