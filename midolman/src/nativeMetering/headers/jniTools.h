/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _JNI_TOOLS_H_
#define _JNI_TOOLS_H_

#include <string>
#include "jni.h"

/**
 * Convert a bytearray generated from the bytes in a java string to
 * a C++ string object (not necessarily a printable string).
 * This is mostly used for strings used as hash keys, or to be stored
 * off-heap.
 */
const std::string jba2str(JNIEnv *, jbyteArray);

/**
 * Convert back a string into the original java byte array obtained a
 * java string.
 */
jbyteArray str2jba(JNIEnv*, const std::string&);

#endif /* _JNI_TOOLS_H_ */

