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

import re

first_cap_regex = re.compile('(.)([A-Z][a-z]+)')
all_cap_regex = re.compile('([a-z0-9])([A-Z])')


def camel_to_snake(name):
    """Returns the snake case of given camel cased input"""
    s1 = first_cap_regex.sub(r'\1_\2', name)
    return all_cap_regex.sub(r'\1_\2', s1).lower()


def snake_to_camel(name):
    """Returns the camel case of the given snake cased input"""
    parts = name.split('_')
    return parts[0] + "".join(x.title() for x in parts[1:])


def convert_dict_keys(x, converter):
    """Recursively modifies dictionary keys by applying converter"""
    if isinstance(x, dict):
        new_dict = dict()
        for k, v in x.iteritems():
            new_dict[converter(k)] = convert_dict_keys(v, converter)
        return new_dict
    elif isinstance(x, list):
        new_list = list()
        for item in x:
            new_list.append(convert_dict_keys(item, converter))
        return new_list
    else:
        return x


def convert_case(f):
    def wrapper(*args, **kwargs):
        new_args = list()
        for arg in args:
            new_args.append(convert_dict_keys(arg, snake_to_camel))

        new_kwargs = dict()
        for k, v in kwargs.iteritems():
            new_kwargs[k] = convert_dict_keys(v, snake_to_camel)

        return convert_dict_keys(f(*new_args, **new_kwargs), camel_to_snake)

    return wrapper
