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

""" Base class for physical / virtual topology resource manager. """

import fixtures
from fixtures import callmany
import uuid
import yaml

from mdts.services import service
from mdts.tests.utils import utils


class TopologyManager(fixtures.Fixture):

    def __init__(self, filename=None, data=None, midonet_api=None):
        super(TopologyManager, self).__init__()
        # deprecate
        self._data = self._get_data(filename, data)
        self._midonet_api_host = service.get_container_by_hostname('cluster1')

        # New model
        self._midonet_api = utils.get_midonet_api()
        self._cleanups = callmany.CallMany()
        self._resources = {}

    #Deprecate
    def _deserialize(self, filename):
        with open(filename) as f:
            raw_data = f.read()
            return yaml.load(raw_data)

    #Deprecate
    def _get_data(self, filename, data):
        if filename:
            return self._deserialize(filename)
        else:
            return data

    def set_resource(self, name, resource):
        if name in self._resources:
            RuntimeError("Resource %s previously registered on the "
                         "topology manager. Should be unique." % name)
        self._resources[name] = resource

    def get_resource(self, name):
        return self._resources[name]

    def build(self):
        # Do nothing by default
        pass

    def destroy(self):
        self.cleanUp()
        self._cleanups = callmany.CallMany()
        self._resources = {}

    def add_host_to_tunnel_zone(self, hostname, tz_name, add_clean_up=True):
        def get_or_create_tz(tz_name):
            tzones = self._midonet_api.get_tunnel_zones()
            for tz in tzones:
                if tz_name in tz.get_name():
                    return tz
            tz = self._midonet_api.add_vxlan_tunnel_zone()
            tz.name('%s-%s' % (tz_name, str(uuid.uuid4())[:4]))
            tz.create()
            self.addCleanup(tz.delete)
            return tz

        tz = get_or_create_tz(tz_name)
        host = service.get_container_by_hostname(hostname)
        tz_host = tz.add_tunnel_zone_host()
        tz_host.ip_address(host.get_ip_address())
        tz_host.host_id(host.get_midonet_host_id())
        tz_host.create()
        if add_clean_up:
            self.addCleanup(tz_host.delete)