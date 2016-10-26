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
Virtual topology data class for a port group port.
"""
from mdts.lib.resource_base import ResourceBase

class PortGroupPort(ResourceBase):

    def __init__(self, api, context, parent_port_group, data):
        """ Initializes a port group port.

        Args:
            api: MidoNet API client object
            context: Virtual Topology Manager as a context for this topology
            parent_port_group: Parent PortGroup object this object belongs.
            data: topology data that represents this resource and below
                  in the hierarchy
        """
        super(PortGroupPort, self).__init__(api, context, data)
        self._parent_port_group = parent_port_group

    def build(self):
        mn_parent_resource = self._parent_port_group._mn_resource
        mn_port_group_port = mn_parent_resource.add_port_group_port()
        self._mn_resource = mn_port_group_port

        [device_name, port_id] = self._data.get('port')
        device_port = self._context.get_device_port(device_name, port_id)
        if not device_port:
            raise Exception("No device port found for the port group port.")
        mn_port_group_port.port_id(device_port._mn_resource.get_id())
        mn_port_group_port.create()

    def destroy(self):
        """ Destroys virtual topology resources for this Port Group Port. """
        self._mn_resource.delete()