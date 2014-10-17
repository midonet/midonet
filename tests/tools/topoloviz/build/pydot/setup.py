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

#!/usr/bin/env python

try:
    from distutils.core import setup
except ImportError, excp:
    from setuptools import setup
    
import pydot
import os

os.environ['COPY_EXTENDED_ATTRIBUTES_DISABLE'] = 'true'
os.environ['COPYFILE_DISABLE'] = 'true'

setup(	name = 'pydot',
    version = pydot.__version__,
    description = 'Python interface to Graphviz\'s Dot',
    author = 'Ero Carrera',
    author_email = 'ero@dkbza.org',
    url = 'http://code.google.com/p/pydot/',
    download_url = 'http://pydot.googlecode.com/files/pydot-%s.tar.gz' % pydot.__version__,
    license = 'MIT',
    keywords = 'graphviz dot graphs visualization',
    platforms = ['any'],
    classifiers =	['Development Status :: 5 - Production/Stable',
        'Intended Audience :: Science/Research',
        'License :: OSI Approved :: MIT License',
        'Natural Language :: English',
        'Operating System :: OS Independent',
        'Programming Language :: Python',
        'Topic :: Scientific/Engineering :: Visualization',
        'Topic :: Software Development :: Libraries :: Python Modules'],
    long_description = "\n".join(pydot.__doc__.split('\n')),
    py_modules = ['pydot', 'dot_parser'],
    install_requires = ['pyparsing', 'setuptools'],
    data_files = [('.', ['LICENSE', 'README'])] )
