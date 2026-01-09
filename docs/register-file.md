# Register File

## Overview

The Register File stores the current values of architectural registers. Each register entry includes a value and a tag(with validness indicator) that indicates which ROB entry will produce the next value for that register. This implements register renaming to handle write-after-write (WAW) and write-after-read (WAR) hazards, enabling out-of-order execution. It has two reading ports for each functional unit.

## Ports

In the Chisel design, the Register File module has the following IO ports:

### Inputs

- `register_index`, `tag` and `value`: writeback value with validness indicator from ROB.
- `tail`: ROB tail position, combined with
- `destination`: of the issued instruction from IF with validness indicator, used to update tags.
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
- **Read Ports**: Combinational logic for reading rs1 and rs2. Again, this is actually implemented in reservation stations.

### Operation

1. **Read Operations**: On read requests, returns the current value and tag.
2. **Bookkeeping**: When an instruction is issued, IF tells RF to update the corresponding register tag to RF tail.
3. **Writeback**: When the ROB commits a register write, change the register value accordingly. If the bookkeeping tag is same as input from ROB, the tag becomes invalid.
4. **Initialization**: On reset, all registers are set to zero, no tags. On clear, the tags all become invalid, while the register values remain.

## Implementation Details

According to the RV32I instruction set, there are $32$ registers of four bytes each. Register zero is always zero, so its tag should always be invalid, the value being zero, ignoring all write instructions.

Clear and writeback should not be valid at the same time, no need to worry.

A register tag can be modified simultaneously by IF bookkeeping and ROB writeback, in which case IF bookkeeping has higher precedence, so it should be **after** ROB writeback by Chisel semantics.

There is **NO** penetration, the read and write are seperate, because ROB and RF states are always synchronized when read by RS, and IF bookkeeping should take effect after RS read to deal with instructions like `xor rd rd rd`.
