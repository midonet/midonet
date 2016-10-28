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

"""
Virtual topology class for a port group.
"""
from mdts.lib.port_group_port import PortGroupPort
from mdts.lib.resource_base import ResourceBase


class PortGroup(ResourceBase):
    bool_val = {'true': True, 'false': False}

    def __init__(self, api, context, data):
        """ Initializes a port group.

        Args:
            api: MidoNet API client object
            context: Virtual Topology Manager as a context for this topology
            data: topology data that represents this resource and below
                  in the hierarchy
        """
        super(PortGroup, self).__init__(api, context, data)
        self._ports = []

    def build(self):
        self._mn_resource = self._api.add_port_group()
        self._mn_resource.name(self._data.get('name'))
        self._mn_resource.stateful(self._data.get('stateful', 'false'))
        self._mn_resource.tenant_id(self._get_tenant_id())
        self._mn_resource.create()

        for port in self._data.get('ports') or []:
            port_group_port = self.add_port_group_port(port)
            self._ports.append(port_group_port)

    def get_id(self):
        """ Returns the resource ID specified in the topology data. """
        return self._data.get('id')

    def is_stateful(self):
        """ Returns whether the port group is stateful. """
        return self.bool_val.get(self._data.get('stateful', 'false'))

    def get_ports(self):
        """ Returns a list of ports in this port group. """
        return self._ports

    def add_port_group_port(self, port_group_port_data):
        """ Generate and add a new port group port to this port group.

        Args:
            port_group_port_data: topology data for port group port.
        Returns:
            PortGroupPort object for the port group port.
        """
        port_group_port = PortGroupPort(self._api, self._context,
                                        self, port_group_port_data)
        port_group_port.build()
        return port_group_port

    def destroy(self):
        """ Destroys virtual topology resources for this Port Group. """
        for port_group_port in self._ports:
            port_group_port.destroy()
        self._mn_resource.delete()