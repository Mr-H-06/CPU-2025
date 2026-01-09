# Reservation Stations

## Overview

Reservation Stations are buffers associated with each functional unit in the Tomasulo's algorithm. They store the operation to be performed, the operands (either values or ROB tags indicating which entry will produce the value), and control information. Each station can hold one instruction at a time, enabling dynamic scheduling and out-of-order execution by decoupling instruction issue from execution.

## Ports

Each Reservation Station module have the following IO ports. Note that there may be multiple instances (one per functional unit type).

### Inputs

- `reset`: Reset signal to clear the station.
- `issue_valid`: Boolean signal indicating a valid instruction is being issued to this station.
- `issue_bits`: Bundle containing issue data:
  - `op`: Operation type (e.g., ADD, SUB).
  - `op1_index`: Register index of operand 1.
  - `op2_index`: Register index of operand 2, or
  - `op2_value`: if operand 2 is an immediate.
  - `op2_type`: a boolean indicator of which of the above is the case.
  - `dest_tag`: Tag (ROB index) of the destination (from ROB, this is exactly the current queue tail).
- `cdb_valid`: Boolean signal from the Common Data Bus indicating a result is available.
- `cdb_tag`: Tag of the result on the CDB.
- `cdb_value`: Value of the result on the CDB.
- Values in ROB.
- Values and tags in RF.

### Outputs

- `issue_ready`: Boolean signal indicating if the station is ready for more instructions.
- `exec_bits`: Bundle sent to the Functional Unit (with ready/valid protocol)
  - `op`: Operation type.
  - `op1`: Operand 1 value.
  - `op2`: Operand 2 value.
  - `op3`: Operand 3 value(optional).
  - `dest_tag`: Tag associated with this station's destination (for CDB broadcasting).

Note that the LSB requires three parameters, but one of them - the offset - is always an immediate and the others are always register values. this means `op2_index` and `op2_value` can be both valid for the LSB, saving curcuit space.

## Inner Workings

### Structure

Each Reservation Station is a list of instructions that are handled by the same functional unit.
The instructions have the following data members:

- `valid`: Validness indicator.
- `op`: Operation type.
- `op1`: Operand 1 value with readiness indicator.
- `op2`: Operand 2 value with readiness indicator.
- `op1_tag`: Register tag of operand 1.
- `op2_tag`: Register tag of operand 2.
- `op3_tag`: Register tag of operand 3(optional).
- `dest_tag`: Register tag of destination.

### Operation

1. **Issue Phase**: When an instruction is issued, the operation and operands are loaded. If operands are not ready, tags are stored instead of values.
2. **Waiting for Operands**: The station monitors the CDB. When a matching tag is broadcast, the corresponding operand is updated with the value and marked valid.
3. **Execution Ready**: When both operands are valid and the Functional Unit is ready, execution is triggered.
4. **Execution**: The station sends operands to the Functional Unit and waits for completion.
5. **Tag Matching**: The station compares incoming CDB tags with its operand tags; if matched, captures the value.

## Implementation Details

When receiving an instruction, the station immediately tries to read its operand values according to the RF in the ROB. If a CDB broadcast is also received in the same cycle, the station also compares its ROB index with the ROB index of the input instruction. Otherwise this value would be effectively lost forever.
