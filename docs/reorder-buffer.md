# Reorder Buffer

## Overview

The Reorder Buffer (ROB) is a circular buffer that maintains instructions in program order in Tomasulo's algorithm. It assigns unique tags for register renaming, tracks the status of each instruction, stores results temporarily, and ensures in-order commit of instructions to the architectural state. This prevents out-of-order execution from affecting the visible state until instructions are safely retired.

## Ports

The Reorder Buffer module has the following IO ports:

### Inputs

- `issue_valid`: Boolean indicating a valid instruction is being issued.
- `issue_bits`: Bundle containing issue data:
  - `op`: Operation code.
  - `pc`: Program counter value or location of current instruction.
  - `prediction`: Predicted value of result.
  - `pc_reset`: Value to reset PC to if prediction fails.
- `cdb_valid`: Boolean from Common Data Bus (for monitoring broadcasts).
- `cdb_tag`: Tag on CDB.
- `cdb_value`: Value on CDB.

### Outputs

- `ready`: Boolean indicating the ROB is ready to accept more instructions.
- `tail`: Current tail position, to RF and RS.
- `values`: Execution result values (with validness indicator) of elements in the queue, to RS.
- `register_index`, `tag` and `value`: writeback value with validness indicator, to RF.
- `commit_store`: Boolean indicating a store instruction is committed, to LSB.
- `clear`: Boolean indicating an exception, one to each other module.
- `pc_reset`: Value to reset PC to when exeptions arise.

## Inner Workings

### Structure

The Reorder Buffer consists of:

- **Entry Array**: Circular buffer of entries, each containing:
  - `op`: Operation code.
  - `value`: Result value (with validness indicator).
  - `prediction`: Predicted value (valid if `op` is branch or jalr)
  - `pc_reset`: Value to reset PC to if prediction fails (valid if `op` is branch)
- **Head Pointer**: Points to the oldest instruction.
- **Tail Pointer**: Points to the next free entry.

### Operation

1. **Issue Phase**: When issuing an instruction, allocate a new entry at tail, increment tail.
2. **Write-Back Phase**: When a result is ready, update the corresponding entry with the value and mark as ready.
3. **Commit Phase**: At the head, if ready and no exceptions, commit the instruction: update RF, increment head. For stores, also notify LSB.
4. **Exception Handling**: If an exception occurs (mispredicted branch or jalr), flush younger instructions and broadcast clear signal to all other modules, also tell IF where to reset PC.

## Implementation Details
