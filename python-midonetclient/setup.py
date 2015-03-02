#!/usr/bin/env python

# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import os
import setuptools
import sys

SRC_DIR = "src"
MODULE_NAME = "midonetclient"

# Prepend the directory which contains the module to `PYTHONPATH` temporarily.
sys.path.insert(
    0, '/'.join([os.path.dirname(os.path.abspath(__file__)), SRC_DIR]))


def _readme():
    with open("README") as f:
        return f.read()

setuptools.setup(name=MODULE_NAME,
      version=__import__(MODULE_NAME).__version__,
      description="Midonet API client library for Python applications",
      long_description=_readme(),
      classifiers=[
          "Development Status :: 4 - Beta",
          "Intended Audience :: Developers",
          "Intended Audience :: System Administrators",
          "Intended Audience :: Telecommunications Industry",
          "License :: OSI Approved :: Apache Software License",
          "Topic :: System :: Networking",
          "Topic :: System :: Shells",
          "Topic :: Utilities",
      ],
      keywords="MidoNet midonetclient python-midonetclient",
      author="Midokura Engineers",
      author_email="midonet-dev@midonet.org",
      url="https://github.com/midonet/python-midonetclient",
      include_package_data=True,
      package_dir={"": SRC_DIR},
      packages=setuptools.find_packages(SRC_DIR, exclude=["tests"]),
      scripts=["src/bin/midonet-cli"],
      install_requires=[
          "httplib2",
          "readline",
          "webob",
      ],
      zip_safe=False,
      tests_require=["nose", "ddt"],
      test_suite="nose.collector",
      license="Apache License, Version 2.0")
