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

from distutils.command import build_py
from distutils.command import clean
from distutils import spawn
import os
import setuptools
import subprocess
import sys

SRC_DIR = "src"
MODULE_NAME = "midonetclient"

# Prepend the directory which contains the module to `PYTHONPATH` temporarily.
sys.path.insert(
    0, '/'.join([os.path.dirname(os.path.abspath(__file__)), SRC_DIR]))


def _readme():
    with open("README") as f:
        return f.read()


def build_proto(proto_path, include_path, out_path):
    protoc = spawn.find_executable('protoc')
    if protoc is None:
        sys.stderr.write('Cannot find the protoc compiler in $PATH')
        sys.exit(1)
    if subprocess.call([protoc, '-I=%s' % include_path,
                        '--python_out=%s' % out_path, proto_path]) != 0:
        sys.stderr.write('Failed to compile %s' % proto_path)
        sys.exit(1)


class build_with_proto_py(build_py.build_py):
    def run(self):
        _PROTOBUF_PATH = 'src/midonetclient/topology/_protobuf'
        try:
            os.makedirs(_PROTOBUF_PATH)
        except OSError as ose:
            if ose.errno == os.errno.EEXIST:
                pass
        else:
            with open(os.path.join(_PROTOBUF_PATH, '__init__.py'), 'a'):
                pass
        build_proto('../nsdb/src/main/proto/commons.proto',
                    '../nsdb/src/main/proto',
                    'src/midonetclient/topology/_protobuf')
        build_proto('../nsdb/src/main/proto/topology_api.proto',
                    '../nsdb/src/main/proto',
                    'src/midonetclient/topology/_protobuf')
        build_proto('../nsdb/src/main/proto/topology.proto',
                    '../nsdb/src/main/proto',
                    'src/midonetclient/topology/_protobuf')

        # build testing protobuf
        build_proto('src/tests/test.proto',
                    'src',
                    'src')

        # Build the rest
        build_py.build_py.run(self)


class clean_even_proto(clean.clean):
    def run(self):
        for (dirpath, dirnames, filenames) in os.walk("."):
            for filename in filenames:
                filepath = os.path.join(dirpath, filename)
                if (filepath.endswith("_pb2.py") or
                        filepath.endswith("_pb2.pyc")):
                    os.remove(filepath)
        clean.clean.run(self)

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
      cmdclass={'clean': clean_even_proto,
                'build_py': build_with_proto_py},
      install_requires=[
          "httplib2",
          "webob",
      ],
      zip_safe=False,
      tests_require=["nose", "ddt"],
      test_suite="nose.collector",
      license="Apache License, Version 2.0")
