# ALU

## Overview

The ALU performs arithmetic and logic operations in the Tomasulo's algorithm. It is connected to reservation stations and executes operations when all operands are available, enabling parallel execution of independent instructions.

## Ports

In the Chisel design, the ALU module has the following IO ports.

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to initialize the unit.
- `exec_valid`: Boolean signal from the Reservation Station indicating execution should start.
- `exec_bits`: Bundle containing execution data:
  - `op`: Operation code (e.g., ADD, SUB).
  - `op1`: First operand value.
  - `op2`: Second operand value.

### Outputs

- `ready`: Boolean signal indicating the unit is ready to accept a new operation.
- `result_valid`: Boolean signal indicating the result is ready.
- `result_bits`: Bundle containing the result:
  - `value`: Computed result value.
  - `tag`: Destination tag for broadcasting.

## Inner Workings

### Structure

The ALU consists of:

- **Execution Logic**: Combinational or pipelined circuits for arithmetic and logic operations (e.g., adder, comparator).
- **Control FSM**: Manages states: idle, executing.

### Operation

1. **Idle State**: The ALU waits for `exec_valid` signal.
2. **Execution Start**: Upon receiving valid execution data, the ALU begins computation based on the operation type.
3. **Computation**: Performs integer arithmetic/logic operations in 1-2 cycles.
4. **Result Generation**: Once computation completes, `result_valid` is asserted with the result and tag.
5. **Broadcasting**: Result is sent to the Common Data Bus for distribution.
6. **Reset**: On reset, clears internal state and becomes ready.

### Key Features

- **Pipelining**: The ALU may be pipelined to allow multiple operations in flight.
- **Latency Variation**: Different operations have different latencies; the unit signals readiness accordingly.
- **Exception Handling**: The ALU may include logic for detecting overflows, etc.

### Integration

- Connected directly to Reservation Stations for operand delivery.
- Results broadcast on CDB to update dependent stations and registers.

This design allows the CPU to exploit instruction-level parallelism by executing operations as soon as operands are available.

## Implementation Details

The ALU can be implemented with the following HDL-style pseudocode using non-blocking assignments:

```
class ALU:
  result_valid <= false
  result_bits <= {'value': 0, 'tag': 0}
  ready <= true

  def on_clock():
    if exec_valid and ready:
      result_value = exec_bits.op1 + exec_bits.op2 if exec_bits.op == 'ADD' else exec_bits.op1 - exec_bits.op2  # combinational
      result_bits <= {'value': result_value, 'tag': exec_bits.dest_tag}
      result_valid <= true
      ready <= false
    else:
      result_valid <= false
      ready <= true
```
