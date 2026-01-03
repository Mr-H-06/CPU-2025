# Reservation Stations

## Overview

Reservation Stations are buffers associated with each functional unit in the Tomasulo's algorithm. They store the operation to be performed, the operands (either values or ROB tags indicating which entry will produce the value), and control information. Each station can hold one instruction at a time, enabling dynamic scheduling and out-of-order execution by decoupling instruction issue from execution.

## Ports

In the Chisel implementation, each Reservation Station module would have the following IO ports. Note that there may be multiple instances (one per functional unit type).

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to clear the station.
- `issue_valid`: Boolean signal indicating a valid instruction is being issued to this station.
- `issue_bits`: Bundle containing issue data:
  - `op`: Operation type (e.g., ADD, SUB).
  - `dest_tag`: Tag for the destination (used for broadcasting results).
  - `op1_valid`: Boolean indicating if operand 1 is ready.
  - `op1_value`: Value of operand 1 (if ready).
  - `op1_tag`: Tag for operand 1 (if not ready).
  - `op2_valid`: Boolean for operand 2.
  - `op2_value`: Value of operand 2.
  - `op2_tag`: Tag for operand 2.
  - `imm`: Immediate value (for certain operations).
- `cdb_valid`: Boolean signal from the Common Data Bus indicating a result is available.
- `cdb_tag`: Tag of the result on the CDB.
- `cdb_value`: Value of the result on the CDB.
- `fu_ready`: Boolean signal from the associated Functional Unit indicating it can start execution.

### Outputs

- `busy`: Boolean signal indicating if the station is occupied.
- `op_ready`: Boolean signal indicating both operands are ready and the station is ready to execute.
- `exec_valid`: Boolean signal to the Functional Unit to start execution.
- `exec_bits`: Bundle sent to the Functional Unit:
  - `op`: Operation type.
  - `op1`: Operand 1 value.
  - `op2`: Operand 2 value.
  - `imm`: Immediate value.
- `result_tag`: Tag associated with this station's result (for CDB broadcasting).

## Inner Workings

### Structure

Each Reservation Station contains:

- **Operation Field**: Stores the operation type.
- **Operand Fields**: Two operand slots, each with:
  - Valid bit: Indicates if the value is ready.
  - Value: The actual data.
  - Tag: Identifier of the station producing the value (if not ready).
- **Destination Tag**: Unique identifier for this station's result.
- **Busy Bit**: Indicates if the station is in use.
- **Control Logic**: Finite state machine managing issue, wait, execute, and write-back states.

### Operation

1. **Issue Phase**: When an instruction is issued, if the station is free (`busy` is false), the operation and operands are loaded. If operands are not ready, tags are stored instead of values.
2. **Waiting for Operands**: The station monitors the CDB. When a matching tag is broadcast, the corresponding operand is updated with the value and marked valid.
3. **Execution Ready**: When both operands are valid and the Functional Unit is ready, `op_ready` is asserted, triggering execution.
4. **Execution**: The station sends operands to the Functional Unit and waits for completion.
5. **Write-Back**: Upon completion, the result is broadcast on the CDB with the destination tag, and the station is freed.
6. **Tag Matching**: The station compares incoming CDB tags with its operand tags; if matched, captures the value.

### Key Features

- **Register Renaming**: By using tags instead of register names, WAW and WAR hazards are eliminated.
- **Parallelism**: Multiple stations can operate simultaneously, allowing out-of-order execution.
- **Hazard Resolution**: RAW hazards are resolved by waiting for tagged operands via CDB broadcasts.
- **Scalability**: The number of stations per functional unit type can be parameterized.

This design enables the core dynamic scheduling capability of Tomasulo's algorithm, allowing instructions to execute as soon as their dependencies are satisfied.

## Implementation Details

A Reservation Station can be implemented with the following HDL-style pseudocode:

```
class ReservationStation:
  busy <= False
  op <= None
  dest_tag <= None
  op1_valid <= False
  op1_value <= None
  op1_tag <= None
  op2_valid <= False
  op2_value <= None
  op2_tag <= None
  imm <= None
  op_ready <= False
  exec_valid <= False
  exec_bits <= {'op': None, 'op1': 0, 'op2': 0, 'imm': 0}
  result_tag <= 0

  def on_clock():
    # Issue
    if issue_valid and not busy:
      busy <= True
      op <= issue_bits.op
      dest_tag <= issue_bits.dest_tag
      op1_valid <= issue_bits.op1_valid
      op1_value <= issue_bits.op1_value
      op1_tag <= issue_bits.op1_tag
      op2_valid <= issue_bits.op2_valid
      op2_value <= issue_bits.op2_value
      op2_tag <= issue_bits.op2_tag
      imm <= issue_bits.imm

    # CDB update
    if cdb_valid:
      if op1_tag == cdb_tag:
        op1_value <= cdb_value
        op1_valid <= True
        op1_tag <= None
      if op2_tag == cdb_tag:
        op2_value <= cdb_value
        op2_valid <= True
        op2_tag <= None

    # Check ready and execute
    op_ready <= busy and op1_valid and op2_valid and fu_ready
    if op_ready:
      exec_valid <= True
      exec_bits <= {
        'op': op,
        'op1': op1_value,
        'op2': op2_value,
        'imm': imm
      }
      result_tag <= dest_tag
      busy <= False  # free after execution
    else:
      exec_valid <= False
```
