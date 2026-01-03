# Register File

## Overview

The Register File stores the current values of architectural registers. Each register entry includes a value and a tag that indicates which ROB entry will produce the next value for that register. This implements register renaming to handle write-after-write (WAW) and write-after-read (WAR) hazards, enabling out-of-order execution.

## Ports

In the Chisel design, the Register File module has the following IO ports:

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to initialize registers.
- `read_rs1`: Register index for source 1 read.
- `read_rs2`: Register index for source 2 read.
- `issue_rd`: Register index for destination (during issue).
- `issue_tag`: Tag assigned to the destination register during issue.
- `cdb_valid`: Boolean from Common Data Bus indicating a result is available.
- `cdb_tag`: Tag of the result on CDB.
- `cdb_value`: Value of the result on CDB.

### Outputs

- `rs1_value`: Value read from register rs1.
- `rs1_tag`: Tag associated with rs1 (if not ready).
- `rs1_valid`: Boolean indicating if rs1 value is current.
- `rs2_value`: Value read from register rs2.
- `rs2_tag`: Tag for rs2.
- `rs2_valid`: Boolean for rs2 readiness.
- `rd_busy`: Boolean indicating if the destination register is busy (tagged).

## Inner Workings

### Structure

The Register File consists of:

- **Register Array**: Array of register entries, each containing:
  - `value`: Current value.
  - `tag`: Tag of the station producing the next value (or invalid if ready).
  - `busy`: Bit indicating if the register is waiting for a result.
- **Read Ports**: Combinational logic for reading rs1 and rs2.
- **Write/Update Logic**: Synchronous updates on CDB broadcasts and issues.

### Operation

1. **Read Operations**: On read requests, returns the current value and tag. If busy, the tag indicates the producing station.
2. **Issue Phase**: When issuing an instruction, if the destination register is not busy, it's marked busy with the assigned tag. The old value remains until updated.
3. **CDB Monitoring**: Continuously checks CDB broadcasts. If the tag matches a register's tag, updates the value, clears the busy bit, and sets tag to invalid.
4. **Register Renaming**: By associating tags with registers, multiple versions of the same register can exist simultaneously.
5. **Initialization**: On reset, all registers are set to zero, not busy, no tags.

### Key Features

- **Hazard Elimination**: WAW and WAR hazards are resolved via tagging, allowing out-of-order writes.
- **Dual Read Ports**: Supports simultaneous reading of two source registers.
- **Broadcast Listening**: All registers listen to CDB for updates, enabling immediate value propagation.
- **Architectural State**: Maintains the programmer-visible register state.
- **Scalability**: Number of registers is fixed (e.g., 32 for RISC-V).

### Integration with Tomasulo's Algorithm

- Provides operands during issue; if not ready, tags are used in reservation stations.
- Receives updates from CDB to keep values current.
- Ensures correct register state for committed instructions.

This component is crucial for maintaining register state in the dynamic scheduling environment.

## Implementation Details

The Register File can be implemented with the following HDL-style pseudocode:

```
class RegisterFile:
  registers <= []  # Sequential state
  rs1_value <= 0
  rs1_tag <= None
  rs1_valid <= true
  rs2_value <= 0
  rs2_tag <= None
  rs2_valid <= true
  rd_busy <= false

  def on_clock():
    # Combinational reads
    rs1_data = registers[read_rs1]
    rs1_value <= rs1_data['value']
    rs1_tag <= rs1_data['tag']
    rs1_valid <= not rs1_data['busy']
    rs2_data = registers[read_rs2]
    rs2_value <= rs2_data['value']
    rs2_tag <= rs2_data['tag']
    rs2_valid <= not rs2_data['busy']

    # Issue logic
    if not registers[issue_rd]['busy']:
      registers[issue_rd]['busy'] <= True
      registers[issue_rd]['tag'] <= issue_tag
      rd_busy <= False
    else:
      rd_busy <= True

    # CDB update
    if cdb_valid:
      updated_regs = []
      for reg in registers:
        if reg['tag'] == cdb_tag:
          reg['value'] = cdb_value
          reg['busy'] = False
          reg['tag'] = None
        updated_regs.append(reg)
      registers <= updated_regs
```
