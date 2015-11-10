#
# Copyright 2015 Midokura SARL
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
from mdts.lib.topology_manager import TopologyManager
from mdts.tests.utils.utils import get_neutron_api


class NeutronTopologyManager(TopologyManager):
    """
    This is the topology manager for Neutron based APIs. It contains helper
    methods to manage the lifecycle of a topology (setup and teardown of
    resources).
    """

    '''
    Name mapping between C methods and D methods
    '''
    method_mappings = {
        'create': 'delete',
        'add': 'remove',
        'associate': 'disassociate',
        'connect': 'disconnect',
    }

    def __init__(self):
        super(NeutronTopologyManager, self).__init__()
        self.resources = {}
        self.api = get_neutron_api()

    def create_resource(self, resource):
        # Get the type of the resource just created.
        rtype = resource.keys()[0]

        # Keep the reference to the resource created identified by the name.
        # A dictionary mapping the json response.
        if 'name' in resource[rtype]:
            name = resource[rtype]['name']
            self.set_resource(name, resource)

        delete_method_name = "%s_%s" % (
            self.method_mappings['create'],
            rtype)
        delete = getattr(self.api, delete_method_name)
        self.addCleanup(delete, resource[rtype]['id'])
        return resource
