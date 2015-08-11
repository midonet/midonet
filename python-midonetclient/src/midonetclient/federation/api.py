# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import logging

from midonetclient import application
from midonetclient import auth_lib
from midonetclient import exc

LOG = logging.getLogger(__name__)


class FederationApi(object):

    def __init__(self, base_uri, username, password, project_id=None):
        self.base_uri = base_uri.rstrip('/')
        self.project_id = project_id
        self.app = None
        self.auth = auth_lib.Auth(self.base_uri + '/login', username, password,
                                  project_id)

    def _ensure_application(self):
        if self.app is None:
            self.app = application.Application(None, {'uri': self.base_uri},
                                               self.auth)
            try:
                self.app.get()
            except exc.MidoApiConnectionRefused:
                self.app = None
                raise
        return self.app
