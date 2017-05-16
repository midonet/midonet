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
#include "org_midonet_midolman_flows_NativeFlowController.h"

int NativeFlowController::test() {
  return 0;
}

jint Java_org_midonet_midolman_flows_NativeFlowController_test(JNIEnv *, jobject) {
  return 0;
}

Flow::Flow()
  : m_id(NULL_ID), m_flow_match(),
    m_sequence(-1), m_linked_id(NULL_ID) {}

Flow::Flow(FlowId id, std::string& flow_match)
  : m_id(id), m_flow_match(flow_match),
    m_sequence(-1), m_linked_id(NULL_ID) {}

FlowId Flow::id() const { return m_id; }
std::string Flow::flow_match() const { return m_flow_match; }

long long Flow::sequence() const { return m_sequence; }
void Flow::set_sequence(long long sequence) { m_sequence = sequence; }

FlowId Flow::linked_id() const { return m_linked_id; }
void Flow::set_linked_id(FlowId linked_id) { m_linked_id = linked_id; }

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

FlowId FlowTable::put(std::string& fmatch) {
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
