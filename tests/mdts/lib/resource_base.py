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
Base class for virtual topology resource data such as bridges and routers.
"""

from webob.exc import HTTPServiceUnavailable
import time


def retryloop(attempts, delay):
    """ decorator used to retry in case of service unavailable.
    TODO: The retry time should be in the HTTPServiceUnavailable exception.
          This requires changes in python-midonetclient.
    """
    def internal_wrapper(func):
        def retry(*args, **kwargs):
            for i in range(attempts - 1):
                try:
                    return func(*args, **kwargs)
                except HTTPServiceUnavailable:
                    time.sleep(delay)
            # One last try....
            return func(*args, **kwargs)
        return retry
    return internal_wrapper


class ResourceBase(object):

    def __init__(self, api, context, data):
        """Base class for MidoNet resources in the topology

        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager

        Args:
            api: MidoNet API client object
            context: Virtual Topology Manager as a virtual topology context
                     for this topology.
            data: topology data that represents this resource and below
                  in the hierarchy
        """
        self._api = api
        self._context = context
        self._data = data
        self._mn_resource = None

    @retryloop(5, 1)
    def create_resource(self):
        self._mn_resource.create()

    def get_mn_resource(self):
        """ Returns the MidoNet resource that corresponds to this"""
        return self._mn_resource

    def _get_children_by_key(self, key):
        return self._data.get(key, [])

    def _get_tenant_id(self):
        return self._context.get_tenant_id()

    def _get_name(self):
        if self._data.get('name'):
            return self._data.get('name')
        else:
            return 'anonymous'
