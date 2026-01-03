# Load/Store Buffer

## Overview

The Load/Store Buffer manages memory operations in the Tomasulo's algorithm. It handles the ordering of loads and stores to ensure memory consistency, preventing hazards like read-after-write (RAW) in memory accesses. It acts as a queue for memory instructions, coordinating with the memory subsystem while maintaining proper sequencing.

## Ports

In the Chisel implementation, the Load/Store Buffer module has the following IO ports:

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to clear the buffer.
- `issue_valid`: Boolean indicating a load/store instruction is being issued.
- `issue_bits`: Bundle with issue data:
  - `is_store`: Boolean for store vs. load.
  - `addr_tag`: Tag for base address (if not ready).
  - `addr_value`: Base address value (if ready).
  - `data_tag`: Tag for store data (if not ready).
  - `data_value`: Store data value (if ready).
  - `offset`: Immediate offset.
  - `dest_tag`: Tag for load destination.
- `cdb_valid`: Boolean from Common Data Bus.
- `cdb_tag`: Tag on CDB.
- `cdb_value`: Value on CDB.
- `mem_resp_valid`: Boolean indicating memory response.
- `mem_resp_bits`: Bundle with loaded data and address.

### Outputs

- `busy`: Boolean indicating buffer is full.
- `mem_req_valid`: Boolean for memory request.
- `mem_req_bits`: Bundle for request:
  - `addr`: Effective address.
  - `data`: Data for store.
  - `is_store`: Operation type.
- `cdb_broadcast_valid`: Boolean to broadcast load result.
- `cdb_broadcast_tag`: Tag for load result.
- `cdb_broadcast_value`: Loaded value.

## Inner Workings

### Structure

The Load/Store Buffer includes:

- **Entry Array**: FIFO or circular buffer of entries, each with:
  - Operation type (load/store).
  - Address components (base, offset, tag/value).
  - Data (for stores, tag/value).
  - State (waiting for address, waiting for data, waiting for memory, ready to commit).
- **Address Calculator**: Combinational logic to compute effective address.
- **Dependency Checker**: Logic to prevent out-of-order execution that violates memory consistency.
- **Memory Interface**: Handles requests and responses.

### Operation

1. **Issue**: Instructions are added to the buffer with their operands/tags.
2. **Address Resolution**: Waits for address operands to be ready via CDB.
3. **Data Resolution**: For stores, waits for data to be ready.
4. **Memory Access**: Once ready, issues memory request. Loads wait for response; stores complete after request.
5. **Ordering**: Ensures loads/stores to same address are ordered; may stall younger operations.
6. **Commit**: Loads broadcast result on CDB; stores mark as completed.
7. **Hazard Prevention**: Implements memory disambiguation to avoid RAW hazards.

### Key Features

- **Memory Consistency**: Maintains program order for memory operations.
- **Out-of-Order Issue**: Issues when operands ready, but commits in order.
- **Buffering**: Allows multiple pending memory operations.
- **Address Calculation**: Combines base and offset for effective address.
- **Broadcasting**: Load results propagate via CDB like other operations.

### Integration with Tomasulo's Algorithm

- Connected to reservation stations for issue.
- Interfaces with memory subsystem for data transfer.
- Ensures memory operations fit into the dynamic scheduling framework.

This component is essential for handling the complexities of memory operations in out-of-order processors.

## Implementation Details

The Load/Store Buffer can be implemented with the following HDL-style pseudocode:

```
class LoadStoreBuffer:
  entries <= []  # Sequential state
  busy <= false
  mem_req_valid <= false
  mem_req_bits <= {'addr': 0, 'data': 0, 'is_store': false}
  cdb_broadcast_valid <= false
  cdb_broadcast_tag <= 0
  cdb_broadcast_value <= 0

  def on_clock():
    # Issue new entry
    if issue_valid and len(entries) < max_entries:
      new_entry = {
        'is_store': issue_bits.is_store,
        'addr_tag': issue_bits.addr_tag,
        'addr_value': issue_bits.addr_value,
        'data_tag': issue_bits.data_tag,
        'data_value': issue_bits.data_value,
        'offset': issue_bits.offset,
        'dest_tag': issue_bits.dest_tag,
        'state': 'waiting'
      }
      entries <= entries + [new_entry]
      busy <= false
    else:
      busy <= true

    # Update from CDB (combinational updates to entries)
    updated_entries = []
    for entry in entries:
      if entry['addr_tag'] == cdb_tag:
        entry['addr_value'] = cdb_value
        entry['addr_tag'] = None
      if entry['data_tag'] == cdb_tag:
        entry['data_value'] = cdb_value
        entry['data_tag'] = None
      if entry['addr_value'] is not None and (not entry['is_store'] or entry['data_value'] is not None):
        entry['state'] = 'ready'
      updated_entries.append(entry)
    entries <= updated_entries

    # Process memory
    for i, entry in enumerate(entries):
      if entry['state'] == 'ready':
        addr = entry['addr_value'] + entry['offset']
        if entry['is_store']:
          mem_req_valid <= true
          mem_req_bits <= {'addr': addr, 'data': entry['data_value'], 'is_store': true}
          entries[i]['state'] = 'completed'
        else:
          mem_req_valid <= true
          mem_req_bits <= {'addr': addr, 'data': None, 'is_store': false}
          entries[i]['state'] = 'waiting_resp'

    # Handle memory response
    if mem_resp_valid:
      for i, entry in enumerate(entries):
        if entry['state'] == 'waiting_resp' and entry['addr_value'] + entry['offset'] == mem_resp_bits['addr']:
          cdb_broadcast_valid <= true
          cdb_broadcast_tag <= entry['dest_tag']
          cdb_broadcast_value <= mem_resp_bits['value']
          # Remove entry
          entries <= entries[:i] + entries[i+1:]
```
