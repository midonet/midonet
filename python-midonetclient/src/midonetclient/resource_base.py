# Copyright 2013, 2014 Midokura SARL, All Rights Reserved.
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


class ResourceBase(object):

    media_type = 'application/json'  # default media_type for all subclasses

    def __init__(self, uri, dto, auth):
        self.uri = uri
        self.dto = dto
        self.auth = auth

    def _ensure_content_type(self, headers):
        """Ensure that http header dict has a value for 'Content-Type'"""
        headers.setdefault('Content-Type', self.media_type)

    def _ensure_accept(self, headers):
        """Ensure that http header dict has a value for 'Accept'"""
        headers.setdefault('Accept', self.media_type)

    def create(self, headers=None):
        """Does REST POST at some uri followed by REST GET at new location"""

        headers = headers or dict()
        self._ensure_content_type(headers)

        resp, _ = self.auth.do_request(self.uri, 'POST', body=self.dto,
                                       headers=headers)

        self._ensure_accept(headers)
        _, self.dto = self.auth.do_request(resp['location'], 'GET',
                                           headers=headers)
        return self

    def upload(self, uri, body, headers=None):
        """Does REST POST with a binary body at some URI"""

        headers = headers or dict()
        self._ensure_content_type(headers)

        resp, self.dto = self.auth.do_upload(uri, body=body, headers=headers)
        self._ensure_accept(headers)

        return self

    def get(self, headers=None, **kwargs):
        """Does REST GET at some uri"""
        headers = headers or dict()
        self._ensure_accept(headers)
        uri = self.dto['uri']
        _, self.dto = self.auth.do_request(uri, 'GET', headers=headers)
        return self

    def get_children(self, uri, query, headers, clazz, extra_args=None):
        """Does GET at uri and create objects found in server answer"""
        self._ensure_accept(headers)

        _, dtos = self.auth.do_request(uri, 'GET', query=query,
                                       headers=headers)

        return map(
            lambda dto: clazz(uri, dto, self.auth, *(extra_args or [])),
            dtos or []  # in case API returns empty when no hosts
        )

    def get_uri(self):
        """Return one's own uri"""
        return self.dto['uri']

    def update(self, headers=None):
        """Does PUT at own uri followed by GET at own uri"""
        headers = headers or dict()

        self._ensure_content_type(headers)

        self.auth.do_request(self.dto['uri'], 'PUT',
                             body=self.dto,
                             headers=headers)

        headers['Accept'] = self.media_type
        _, self.dto = self.auth.do_request(self.dto['uri'], 'GET',
                                           headers=headers)
        return self

    def delete(self, headers={}):
        """Does DELETE at own uri"""
        self.auth.do_request(self.dto['uri'], 'DELETE', headers=headers)
        return None

    def __repr__(self):
        return self.__class__.__name__ + str(self.dto)
