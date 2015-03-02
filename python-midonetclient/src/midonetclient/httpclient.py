# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import auth_lib


class HttpClient(object):

    def __init__(self, base_uri, username, password, project_id=None):
        self.auth_lib = auth_lib.Auth(base_uri + '/login', username, password,
                                      project_id)

    def delete(self, uri):
        self.auth_lib.do_request(uri, 'DELETE')

    def get(self, uri, media_type, params=None):
        headers = {"Accept": media_type}
        resp, data = self.auth_lib.do_request(uri, 'GET', query=params,
                                              headers=headers)
        return data

    def post(self, uri, media_type, body=None, accept=None):
        if accept is None:
            accept = media_type
        headers = {"Content-Type": media_type, "Accept": accept}
        resp, data = self.auth_lib.do_request(uri, 'POST', body=body,
                                              headers=headers)
        return data

    def put(self, uri, media_type, body=None):
        headers = {"Content-Type": media_type}
        resp, data = self.auth_lib.do_request(uri, 'PUT', body=body,
                                              headers=headers)
        return data
