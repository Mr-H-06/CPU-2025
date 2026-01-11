# Arithmetic-Logic Unit

## Overview

The ALU performs arithmetic and logic operations in the Tomasulo's algorithm. It is connected to reservation stations and executes operations when all operands are available, enabling parallel execution of independent instructions.

## Ports

In the Chisel design, the ALU module has the following IO ports.

### Inputs

- `clear`: Reset signal to initialize the unit.
- `exec_valid`: Boolean signal from the Reservation Station indicating execution should start.
- `exec_bits`: Bundle containing execution data:
  - `op`: Operation code (e.g., ADD, SUB).
  - `op1`: First operand value.
  - `op2`: Second operand value.
  - `tag`: Destination tag for broadcasting.
- `CDB_ready`: Backpressure from CDB.

### Outputs

- `ready`: Boolean signal indicating the unit is ready to accept a new operation.
- `result_valid`: Boolean signal indicating the result is ready.
- `result_bits`: Bundle containing the result:
  - `value`: Computed result value.
  - `tag`: Destination tag for broadcasting.

## Inner Workings

### Structure

The ALU consists of combinational or pipelined circuits for arithmetic and logic operations (e.g., adder, comparator).

### Operation

1. **Idle State**: The ALU is ready and waits for `exec_valid` signal.
2. **Execution Start**: Upon receiving valid execution data, the ALU begins computation based on the operation type.
3. **Computation**: Performs integer arithmetic/logic operations in 1-2 cycles.
4. **Result Generation**: Once computation completes, `result_valid` is asserted with the result and tag.
5. **Broadcasting**: Result is sent to the Common Data Bus for distribution. The ALU is ready to handle new data.
6. **Backpressure**: Before the result is sent to CDB, it goes through a small buffer to smooth out backpressure.
7. **Reset**: On reset, clears internal state and becomes ready.

## Implementation Details

The result goes through a `ShiftRegister` to simulate lag.
