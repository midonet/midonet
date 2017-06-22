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

#include <string>
#include <vector>
#include <nativeFlowMatchList.h>
#include "org_midonet_midolman_flows_NativeFlowMatchListJNI.h"

const std::string bb2str(JNIEnv *env, jobject bb, int pos, int len) {
  char* buf = (char *)env->GetDirectBufferAddress(bb);
  return std::string(buf + pos, len);
}

jbyteArray str2jba(JNIEnv *env, std::string str) {
  jbyteArray retBytes = env->NewByteArray(str.size());
  env->SetByteArrayRegion(retBytes, 0, str.size(),
                          reinterpret_cast<const jbyte*>(str.c_str()));
  return retBytes;
}

jlong
Java_org_midonet_midolman_flows_NativeFlowMatchListJNI_createFlowMatchList
(JNIEnv *, jclass) {
  return reinterpret_cast<jlong>(new FlowMatchList());
}

void
Java_org_midonet_midolman_flows_NativeFlowMatchListJNI_pushFlowMatch
(JNIEnv *env, jclass, jlong pointer, jobject flowMatch, jint pos, jint len) {
  auto list = reinterpret_cast<FlowMatchList*>(pointer);
  list->push_flow_match(bb2str(env, flowMatch, pos, len));
}

jbyteArray
Java_org_midonet_midolman_flows_NativeFlowMatchListJNI_popFlowMatch
(JNIEnv *env, jclass, jlong pointer) {
  auto list = reinterpret_cast<FlowMatchList*>(pointer);
  return str2jba(env, list->pop_flow_match());
}

jint
Java_org_midonet_midolman_flows_NativeFlowMatchListJNI_size
(JNIEnv *, jclass, jlong pointer) {
  auto list = reinterpret_cast<FlowMatchList*>(pointer);
  return list->size();
}

void
Java_org_midonet_midolman_flows_NativeFlowMatchListJNI_deleteFlowMatchList
(JNIEnv *, jclass, jlong pointer) {
  delete reinterpret_cast<FlowMatchList*>(pointer);
}

FlowMatchList::FlowMatchList()
  : m_list() {}

void FlowMatchList::push_flow_match(FlowMatch match) {
    m_list.push_back(match);
}

FlowMatch FlowMatchList::pop_flow_match() {
  if (m_list.size() == 0) {
    return std::string();
  } else {
    auto last = m_list.back();
    m_list.pop_back();
    return last;
  }
}

int FlowMatchList::size() {
  return m_list.size();
}
