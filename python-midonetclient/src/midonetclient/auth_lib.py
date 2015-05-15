# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2013 Midokura PTE LTD.
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


import base64
import logging
import threading

from webob import exc

from midonetclient import api_lib


LOG = logging.getLogger(__name__)


_token = None
_sem = threading.Semaphore(1)


class Auth:

    def __init__(self, uri, username, password, project_id=None):
        self.uri = uri
        self.username = username
        self.password = password
        self.project_id = project_id

    def login(self):
        '''Login a user

        Make sure only one thread tries to login.  The other threads would
        block until the first thread succeeds in setting the global _token
        variable.
        '''
        global _sem
        global _token

        if _sem.acquire(blocking=False):
            try:
                auth = base64.b64encode(self.username + ':' + self.password)
                headers = {'Authorization': 'Basic ' + auth}

                if self.project_id is not None:
                    headers['X-Auth-Project'] = self.project_id

                LOG.info("Logging in to MidoNet API server")
                resp, _body = api_lib.do_request(self.uri, 'POST', body={},
                                                 headers=headers)
                set_cookie = resp['set-cookie']
                session, sep, exp = set_cookie.partition(";")
                session_key, sep, _token = session.partition("=")
            finally:
                _sem.release()
        else:
            LOG.debug("Waiting for token to get reset")
            # Wait for another thread to set the token and release
            _sem.acquire(blocking=True)
            _sem.release()

    def get_token(self, force=False):
        '''Return the currently set token.

        Login the user if the global _token variable is not set.
        '''
        global _token
        if _token is None or force:
            self.login()
        return _token

    def set_header_token(self, header, force=False):
        '''Sets the HTTP header with auth token
        '''
        header['X-Auth-Token'] = self.get_token(force)

    # This is used by resource_base.ResourceBase, calls api_lib
    def do_request(self, uri, method, body=None, query=None, headers=None):
        ''' Wrapper for api_lib.do_request that includes auth logic.
        '''
        query = query or dict()
        headers = headers or dict()

        # Username will be None if user has opted to skip authorization.
        if self.username is not None:
            self.set_header_token(headers)
        try:
            return api_lib.do_request(uri, method, body=body,
                                      query=query, headers=headers)
        except exc.HTTPUnauthorized:
            # Try one more time after logging in
            LOG.info("Got HTTPUnauthorized error, try logging in again")
            self.set_header_token(headers, force=True)
            return api_lib.do_request(uri, method, body=body, query=query,
                                      headers=headers)

    def do_upload(self, uri, body=None, query=None, headers=None):
        ''' Wrapper for api_lib.do_upload that includes auth logic.
        '''
        query = query or dict()
        headers = headers or dict()

        # Username will be None if user has opted to skip authorization.
        if self.username is not None:
            self.set_header_token(headers)
        try:
            return api_lib.do_upload(uri, body=body, query=query,
                                     headers=headers)
        except exc.HTTPUnauthorized:
            # Try one more time after logging in
            LOG.info("Got HTTPUnauthorized error, try logging in again")
            self.set_header_token(headers, force=True)
            return api_lib.do_upload(uri, body=body, query=query,
                                     headers=headers)
