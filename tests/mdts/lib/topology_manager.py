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

from midonetclient.api import MidonetApi

import yaml


class TopologyManager(object):

    def __init__(self, filename=None, data=None, midonet_api=None):
        self._data = self._get_data(filename, data)
        if not midonet_api:
            midonet_api = MidonetApi(
                'http://127.0.0.1:8080/midonet-api','admin','*')
        self._api = midonet_api

    def _deserialize(self, filename):
        with open(filename) as f:
            raw_data = f.read()
            return yaml.load(raw_data)

    def _get_data(self, filename, data):
        if not filename and not data:
            raise AssertionError(
                "One of the filename or data should be provided")

        if filename:
            return self._deserialize(filename)
        else:
            return data