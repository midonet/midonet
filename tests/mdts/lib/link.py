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
A class for representing peer port link in virtual topology resource.
"""
from mdts.lib.mdts_exception import MdtsException
from mdts.lib.resource_base import ResourceBase


class PeerDevicePortNotFoundException(MdtsException):
    """Exception raised when a peer device port is not found."""
    pass


class Link(ResourceBase):
    def __init__(self, api, context, data):
        self._peer_a_name = None
        self._peer_b_name = None
        self._peer_a_port_id = None
        self._peer_b_port_id = None
        super(Link, self).__init__(api, context, data)

        for attr, peer_info in self._data.items():
            if attr == 'peer_A':
                if len(peer_info) != 2:
                    raise Exception("Invalid Link specification")
                self._peer_a_name = peer_info[0]
                self._peer_a_port_id = peer_info[1]
            elif attr == 'peer_B':
                if len(peer_info) != 2:
                    raise Exception("Invalid Link specification")
                self._peer_b_name = peer_info[0]
                self._peer_b_port_id = peer_info[1]
            else:
                raise Exception("Unrecognizable Link attribute: %s" % attr)

    def __str__(self):
        return 'device port link: %s:%s -- %s:%s' % (self._peer_a_name,
                                                     self._peer_a_port_id,
                                                     self._peer_b_name,
                                                     self._peer_b_port_id)

    def build(self):
        """Links peer port A and B."""
        port_A = self._context.get_device_port(self._peer_a_name,
                                               self._peer_a_port_id)
        if not port_A:
            raise PeerDevicePortNotFoundException(
                    'No corresponding peer device port found for port name %s, '
                    'ID %s.' % (self._peer_a_name, self._peer_a_port_id))

        port_B = self._context.get_device_port(self._peer_b_name,
                                               self._peer_b_port_id)
        if not port_B:
            raise PeerDevicePortNotFoundException(
                    'No corresponding peer device port found for port name %s, '
                    'ID %s.' % (self._peer_b_name, self._peer_b_port_id))
        port_A.link(port_B)

    def destroy(self):
        """Cleans up virtual topology data for link.

        No need to do anything because the clean-up should be done by the
        destroy method of the corresponding bridge / router port.
        """
        pass

    def get_peer_a_name(self):
        return self._peer_a_name

    def set_peer_a_name(self, name):
        self._peer_a_name = name

    def get_peer_a_port_id(self):
        return self._peer_a_port_id

    def set_peer_a_port_id(self, port_id):
        self._peer_a_port_id = port_id

    def get_peer_b_name(self):
        return self._peer_b_name

    def set_peer_b_name(self, name):
        self._peer_b_name = name

    def get_peer_b_port_id(self):
        return self._peer_b_port_id

    def set_peer_b_port_id(self, port_id):
        self._peer_b_port_id = port_id
