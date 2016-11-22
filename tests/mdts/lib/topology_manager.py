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
        """
        Store the mapping between a resource and its name.
        :param name: string
        :param resource: object
        :rtype: None
        """
        if name in self._resources:
            RuntimeError("Resource %s previously registered on the "
                         "topology manager. Should be unique." % name)
        self._resources[name] = resource

    def get_resource(self, name):
        """
        :param name: string
        :rtype: object or None if name was not registered previously
        """
        return self._resources.get(name)

    def build(self, binding_data=None):
        """
        Builds the topology specified in this method, either physical or
        virtual. If the topology elements should be removed during teardown,
        you MUST schedule the delete using self.addCleanup(delete_method, args).
        :rtype: None
        """
        # Do nothing by default
        pass

    def destroy(self):
        """
        Clean up of the topology scheduled for deletion during the test
        lifecycle (build method or inside the test). The topology will be
        cleaned up if an exception is raised during a test.
        :return:
        """
        self.cleanUp()
        self._cleanups = callmany.CallMany()
        self._resources = {}

    def get_default_tunnel_zone_name(self):
        return 'tzone-%s' % str(uuid.uuid4())[:4]

    def add_host_to_tunnel_zone(self, hostname, tz_name, add_clean_up=True):
        """

        :param hostname: string
        :param tz_name: string
        :param add_clean_up: boolean
        :rtype: None
        """
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
        hostid = host.get_midonet_host_id()
        tz_hostids = [tz_host.get_host_id() for tz_host in tz.get_hosts()]
        # Make sure not to add an existing host in the tz
        if hostid not in tz_hostids:
            tz_host = tz.add_tunnel_zone_host()
            tz_host.ip_address(host.get_ip_address())
            tz_host.host_id(host.get_midonet_host_id())
            tz_host.create()
            if add_clean_up:
                self.addCleanup(tz_host.delete)
