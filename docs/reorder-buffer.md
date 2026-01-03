# Reorder Buffer

## Overview

The Reorder Buffer (ROB) is a circular buffer that maintains instructions in program order in Tomasulo's algorithm. It assigns unique tags for register renaming, tracks the status of each instruction, stores results temporarily, and ensures in-order commit of instructions to the architectural state. This prevents out-of-order execution from affecting the visible state until instructions are safely retired.

## Ports

In the Chisel implementation, the Reorder Buffer module has the following IO ports:

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to clear the buffer.
- `issue_valid`: Boolean indicating a valid instruction is being issued.
- `issue_bits`: Bundle containing issue data:
  - `dest_reg`: Destination register index.
  - `is_store`: Boolean for store operations.
  - `is_branch`: Boolean for branch operations.
- `writeback_valid`: Boolean indicating a result is ready to write back.
- `writeback_tag`: Tag of the ROB entry to update.
- `writeback_value`: Result value from execution.
- `cdb_valid`: Boolean from Common Data Bus (for monitoring broadcasts).
- `cdb_tag`: Tag on CDB.
- `cdb_value`: Value on CDB.
- `commit_ready`: Boolean indicating ready to commit (from head of buffer).

### Outputs

- `full`: Boolean indicating the ROB is full (cannot issue more).
- `issue_tag`: Tag assigned to the newly issued instruction.
- `head_tag`: Tag of the head entry (for commit).
- `head_value`: Value of the head entry (for register update on commit).
- `head_reg`: Destination register of the head entry.
- `head_ready`: Boolean indicating head entry is ready to commit.
- `exception`: Boolean indicating an exception at head (e.g., branch mispredict).

## Inner Workings

### Structure

The Reorder Buffer consists of:

- **Entry Array**: Circular buffer of entries, each containing:
  - `valid`: Boolean indicating if entry is occupied.
  - `ready`: Boolean indicating if result is available.
  - `value`: Result value (when ready).
  - `dest_reg`: Architectural register to update on commit.
  - `is_store`: Flag for store operations.
  - `is_branch`: Flag for branch operations.
  - `exception`: Flag for exceptions.
- **Head Pointer**: Points to the oldest instruction.
- **Tail Pointer**: Points to the next free entry.
- **Tag Generator**: Assigns unique tags to new entries.

### Operation

1. **Issue Phase**: When issuing an instruction, allocate a new entry at tail, assign a tag, store destination register and type. Increment tail.
2. **Write-Back Phase**: When a result is ready, update the corresponding entry with the value and mark as ready.
3. **Commit Phase**: At the head, if ready and no exceptions, commit the instruction: update architectural register file, increment head. For stores, mark as completed.
4. **Exception Handling**: If an exception occurs (e.g., mispredicted branch), flush younger instructions and reset pointers.
5. **Full Check**: ROB is full when tail would wrap around to head.

### Key Features

- **In-Order Commit**: Ensures instructions retire in program order, maintaining correctness.
- **Register Renaming**: Provides tags that replace register names, eliminating WAW/WAR hazards.
- **Speculative Execution**: Allows out-of-order execution while buffering results until commit.
- **Exception Recovery**: Supports precise exceptions by tracking instruction state.
- **Circular Buffer**: Efficient implementation with wrap-around pointers.

### Integration with Tomasulo's Algorithm

- Assigns tags during issue, used by reservation stations and register file.
- Receives write-backs from CDB and functional units.
- Commits results to register file and memory (for stores).
- Coordinates with load/store buffer for memory ordering.

This component is central to maintaining program semantics in the out-of-order pipeline.

## Implementation Details

The Reorder Buffer can be implemented with the following HDL-style pseudocode:

```
class ReorderBuffer:
  entries <= []  # Sequential state
  head <= 0
  tail <= 0
  full <= false
  issue_tag <= 0
  head_ready <= false
  head_value <= 0
  head_reg <= 0

  def on_clock():
    # Issue
    if issue_valid and (tail + 1) % size != head:
      issue_tag <= tail
      entries[tail] <= {
        'valid': True,
        'ready': False,
        'value': None,
        'dest_reg': issue_bits.dest_reg,
        'is_store': issue_bits.is_store,
        'is_branch': issue_bits.is_branch
      }
      tail <= (tail + 1) % size
      full <= False
    else:
      full <= True

    # Writeback
    if writeback_valid:
      entries[writeback_tag]['value'] <= writeback_value
      entries[writeback_tag]['ready'] <= True

    # Commit
    if entries[head]['valid'] and entries[head]['ready'] and commit_ready:
      head_value <= entries[head]['value']
      head_reg <= entries[head]['dest_reg']
      entries[head]['valid'] <= False
      head <= (head + 1) % size
      head_ready <= True
    else:
      head_ready <= False
```
