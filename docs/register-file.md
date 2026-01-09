# Register File

## Overview

The Register File stores the current values of architectural registers. Each register entry includes a value and a tag(with validness indicator) that indicates which ROB entry will produce the next value for that register. This implements register renaming to handle write-after-write (WAW) and write-after-read (WAR) hazards, enabling out-of-order execution. It has two reading ports for each functional unit.

## Ports

In the Chisel design, the Register File module has the following IO ports:

### Inputs

- `ROB_index` and `value`: value with validness indicator from CDB.
- `register_index` and `value`: writeback value with validness indicator from ROB.
- `tail`: ROB tail position, combined with
- `destination`: of the issued instruction from IF, used to update tags.
- `clear`: boolean clear signal from ROB.

### Outputs

one set of the following for each reservation station, which means two sets for ALU and LSB.

- `rs1_value`: Value read from register rs1.
- `rs1_tag`: Tag associated with rs1 (if not ready).
- `rs1_tag_valid`: Tag validness indicator.
- `rs2_value`
- `rs2_tag`
- `rs2_valid`

**Important note**: The description of the output is for understanding but not implementing. In Chisel, simply output the `Reg(Vec)` once for each reservation station. It is the design of the reservation station that guarantees there will be at most two reads per cycle, that is, only two MUX's will be generated.

## Inner Workings

### Structure

The Register File consists of:

- **Register Array**: Array of register entries, each containing:
  - `value`: Current value.
  - `tag`: Tag of the station producing the next value (or invalid if ready).
  - `tag_valid`: Bit indicating if the tag is valid
- **Read Ports**: Combinational logic for reading rs1 and rs2.

### Operation

1. **Read Operations**: On read requests, returns the current value and tag.
2. **Writeback**: When the ROB commits a register write, change the value accordingly.
3. **CDB Monitoring**: Continuously checks CDB broadcasts. If the tag matches a register's tag, updates the value, sets tag to invalid.
4. **Initialization**: On reset, all registers are set to zero, no tags. On clear, the tags all become invalid.

## Implementation Details

No penetration - read and monitor are separated. The corresponding functional unit is responsible for simultaneously reading from the register file and listening to CDB broadcasts.
