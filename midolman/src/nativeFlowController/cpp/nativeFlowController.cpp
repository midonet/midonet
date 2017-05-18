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
#include <algorithm>
#include <cassert>
#include <iostream>
#include <nativeFlowController.h>
#include "org_midonet_midolman_flows_NativeFlowControllerJNI.h"

const std::string jba2str(JNIEnv *env, jbyteArray ba) {
  auto len = env->GetArrayLength(ba);
  auto bytes = reinterpret_cast<const char*>(env->GetByteArrayElements(ba, 0));
  return std::string(bytes, len);
}

jbyteArray str2jba(JNIEnv *env, std::string str) {
  jbyteArray retBytes = env->NewByteArray(str.size());
  env->SetByteArrayRegion(retBytes, 0, str.size(),
                          reinterpret_cast<const jbyte*>(str.c_str()));
  return retBytes;
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_createFlowTable
(JNIEnv *env, jclass, jint maxFlows) {
  return reinterpret_cast<jlong>(new FlowTable(maxFlows));
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTablePutFlow
(JNIEnv *env, jclass, jlong pointer, jbyteArray flowMatch) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->put(jba2str(env, flowMatch));
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableClearFlow
(JNIEnv *env, jclass, jlong pointer, jlong flowId) {
    auto table = reinterpret_cast<FlowTable*>(pointer);
    table->clear(flowId);
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableIdAtIndex
(JNIEnv *env, jclass, jlong pointer, jint index) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->id_at_index(index);
}

jint
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableOccupied
(JNIEnv *env, jclass, jlong pointer) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->occupied();
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableEvictionCandidate
(JNIEnv *env, jclass, jlong pointer) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->candidate_for_eviction();
}

jbyteArray
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowMatch
(JNIEnv *env, jclass, jlong pointer, jlong flowId) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return str2jba(env, table->get(flowId).flow_match());
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowSequence
(JNIEnv *env, jclass, jlong pointer, jlong flowId) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->get(flowId).sequence();
}

void
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowSetSequence
(JNIEnv *env, jclass, jlong pointer, jlong flowId, jlong sequence) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  table->get(flowId).set_sequence(sequence);
}

jlong
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowLinkedId
(JNIEnv *env, jclass, jlong pointer, jlong flowId) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->get(flowId).linked_id();
}

void
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowSetLinkedId
(JNIEnv *env, jclass, jlong pointer, jlong flowId, jlong linkedFlowId) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  table->get(flowId).set_linked_id(linkedFlowId);
}

void
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowAddCallback
(JNIEnv *env, jclass, jlong pointer, jlong flowId, jlong cbId, jbyteArray args) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  table->get(flowId).add_callback(CallbackSpec(cbId, jba2str(env, args)));
}

jint
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowCallbackCount
(JNIEnv *env, jclass, jlong pointer, jlong flowId) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->get(flowId).callbacks().size();
}

jlong Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowCallbackId
(JNIEnv *env, jclass, jlong pointer, jlong flowId, jint index) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return table->get(flowId).callbacks().at(index).cb_id();
}

jbyteArray
Java_org_midonet_midolman_flows_NativeFlowControllerJNI_flowTableFlowCallbackArgs
(JNIEnv *env, jclass, jlong pointer, jlong flowId, jint index) {
  auto table = reinterpret_cast<FlowTable*>(pointer);
  return str2jba(env, table->get(flowId).callbacks().at(index).args());
}

CallbackSpec::CallbackSpec(): m_cb_id(-1), m_args() {}
CallbackSpec::CallbackSpec(long long cb_id, std::string args)
  : m_cb_id(cb_id), m_args(args) {}

long long CallbackSpec::cb_id() const {
  return m_cb_id;
}

std::string CallbackSpec::args() const {
  return m_args;
}

Flow::Flow()
  : m_id(NULL_ID), m_flow_match(),
    m_sequence(-1), m_linked_id(NULL_ID),
    m_callbacks() {}

Flow::Flow(FlowId id, std::string& flow_match)
  : m_id(id), m_flow_match(flow_match),
    m_sequence(-1), m_linked_id(NULL_ID),
    m_callbacks() {}

FlowId Flow::id() const { return m_id; }
std::string Flow::flow_match() const { return m_flow_match; }

long long Flow::sequence() const { return m_sequence; }
void Flow::set_sequence(long long sequence) { m_sequence = sequence; }

FlowId Flow::linked_id() const { return m_linked_id; }
void Flow::set_linked_id(FlowId linked_id) { m_linked_id = linked_id; }

std::vector<CallbackSpec> Flow::callbacks() const {
  return m_callbacks;
}

void Flow::add_callback(CallbackSpec spec) {
  m_callbacks.push_back(spec);
}

int leading_zeros(int input) {
  int leading_zeros = 0;
  int int_width = sizeof(int)*8;
  for (int i = int_width - 1; i >= 0; i--) {
    if (((input >> i) & 0x1) == 0x1) {
      break;
    } else {
      leading_zeros++;
    }
  }
  return leading_zeros;
}

int next_pos_power_of_two(int input) {
  int int_width = sizeof(int)*8;
  if (input > (1 << (int_width - 2))) {
    return (1 << (int_width - 2));
  } else if (input < 0) {
    return 1;
  }
  return 1 << (int_width - leading_zeros(input - 1));
}

FlowTable::FlowTable(int max_flows)
  : m_max_flows(std::min(next_pos_power_of_two(max_flows), MAX_TABLE_SIZE)),
    m_mask(m_max_flows - 1),
    m_table(m_max_flows), m_id_counter(0), m_occupied(0) {}


int FlowTable::occupied() const {
  return m_occupied;
}

FlowId FlowTable::id_at_index(int index) const {
  return m_table[index & m_mask].id();
}

FlowId FlowTable::put(std::string fmatch) {
  int index = ++m_id_counter & m_mask;
  int start = index;
  FlowId id = NULL_ID;
  do {
     if (m_table[index].id() == NULL_ID) {
       m_table[index] = Flow(m_id_counter, fmatch);
       id = m_id_counter;
       m_occupied++;
     }
     index = ++m_id_counter & m_mask;
   } while (index != start && id == NULL_ID);
  return id;
}

Flow& FlowTable::get(FlowId id) {
  return m_table[id & m_mask];
}

void FlowTable::clear(FlowId id) {
  m_table[id & m_mask] = Flow();
  m_occupied--;
}

FlowId FlowTable::candidate_for_eviction() {
  auto counter = m_id_counter;
  auto index = counter & m_mask;
  auto start = index;
  auto to_evict = NULL_ID;
  do {
    to_evict = m_table[index].id();
    index = ++counter & m_mask;
  } while (index != start && to_evict == NULL_ID);
  return to_evict;
}
