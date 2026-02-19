# Load/Store Buffer

## Overview

The Load/Store Buffer manages memory operations in the Tomasulo's algorithm. It handles the ordering of loads and stores to ensure memory consistency, preventing hazards like read-after-write (RAW) in memory accesses. It acts as a queue for memory instructions, coordinating with the memory subsystem while maintaining proper sequencing.

## Ports

In the Chisel implementation, the Load/Store Buffer module has the following IO ports:

### Inputs

- `clear`: Reset signal to clear the buffer.
- `issue_valid`: Boolean indicating a load/store instruction is being issued.
- `issue_bits`: Bundle with issue data:
  - `op`: Operation code (e.g., LW, SH).
  - `op1`: First operand value.
  - `op2`: Second operand value if operation is store, register tag if operation is load.
  - `offset`: Memory offset.
- `CDB_ready`: Backpressure from CDB.
- `ROB_commit`: Signal for actually committing memory stores from ROB.
- `mem_response`: Memory response signal with validness protocol.

### Outputs

- `ready`: Boolean indicating buffer is ready.
- `mem_access`: Bundle with issue data (validness protocol):
  - `op`: Operation code.
  - `value`: Store value.
  - `address`: Load/Store address.
- `cdb_broadcast_valid`: Boolean to broadcast load result.
- `cdb_broadcast_tag`: Register tag for load result.
- `cdb_broadcast_value`: Loaded value.

## Inner Workings

### Structure

The Load/Store Buffer includes a memory interface for loading/storing.

### Operation

1. **Idle State**: The LSB is ready and waits for `issue_valid` signal.
2. **Memory access**: Computes access address, accesses memory module, delays caused by cache misses aren't supported.
3. **Result Generation**: Once memory responds, `cdb_broadcast_valid` is asserted with the result and tag.
4. **Broadcasting**: Result is sent to the CDB for distribution. The LSB is ready to handle new data.
5. **Backpressure**: Before the result is sent to CDB, it goes through a small buffer to smooth out backpressure.
6. **Reset**: On reset, clears internal state and becomes ready.

## Implementation Details

To avoid data hazards of memory, the load/store reservation station fires the instructions in order. If the instruction is a store, the LSB doesn't accept further instructions until it receives a store commit signal from ROB.
