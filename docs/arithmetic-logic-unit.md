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
3. **Computation**: Performs integer arithmetic/logic operations in 1 cycle. Send results on next cycle.
4. **Result Generation**: Once computation completes, `result_valid` is asserted with the result and tag.
5. **Broadcasting**: Result is sent to the Common Data Bus for distribution. The ALU is ready to handle new data.
6. **Reset**: On reset, clears internal state and becomes ready.

## Implementation Details

`op` is one of the following:

- `add`, op1 + op2.
- `sub`, op1 - op2.
- `and`, op1 & op2 (bitwise)
- `or` , op1 | op2 (bitwise)
- `xor`, op1 ^ op2 (bitwise)
- `ll` , op1 << op2 (logical)
- `rl` , op1 >> op2 (logical, zero-extend)
- `ra` , op1 >> op2 (arithmetic, sign-extend)
- `lt` , op1 < op2 ? 1 : 0 (signed comparison)
- `ltu`, op1 < op2 ? 1 : 0 (unsigned comparison)
- `eq` , op1 == op2 ? 1 : 0
- `ne` , op1 != op2 ? 1 : 0
- `ge` , op1 >= op2 ? 1 : 0 (signed comparison)
- `geu`, op1 >= op2 ? 1 : 0 (unsigned comparison)
