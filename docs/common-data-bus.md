# Common Data Bus (CDB)

## Overview

The Common Data Bus is a broadcast mechanism that carries results from completed operations. When a functional unit finishes an operation, it broadcasts the result along with the destination tag on the CDB, allowing multiple reservation stations and the register file to capture the value simultaneously. This enables efficient communication in the out-of-order execution pipeline.

## Ports

In the Chisel implementation, the CDB is a shared bus with the following IO structure. It may be implemented as a module that connects all producers and consumers.

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to clear the bus.
- `producers`: Vector of input bundles from Functional Units and Load/Store Buffer:
  - `valid`: Boolean indicating a result is ready to broadcast.
  - `tag`: Destination tag of the result.
  - `value`: The result value.
- Arbitration signals if multiple producers compete (e.g., priority encoder).

### Outputs

- `consumers`: Vector of output bundles to Reservation Stations and Register File:
  - `valid`: Boolean indicating a broadcast is occurring.
  - `tag`: Tag of the broadcast result.
  - `value`: The broadcast value.

## Inner Workings

### Structure

The CDB consists of:

- **Bus Lines**: Parallel wires for tag and value data.
- **Valid Signal**: Indicates when the bus has valid data.
- **Arbitration Logic**: If multiple units try to broadcast simultaneously, selects one (e.g., round-robin or priority-based).
- **Broadcast Mechanism**: All connected modules receive the same signal simultaneously.

### Operation

1. **Result Ready**: Functional Units assert their `result_valid` when computation completes.
2. **Arbitration**: If multiple results are ready, the CDB arbitrates which one broadcasts first.
3. **Broadcast**: The selected result's tag and value are driven onto the bus, with `valid` asserted.
4. **Capture**: All Reservation Stations and the Register File check if their waiting tags match the broadcast tag; if so, they capture the value and mark operands as ready.
5. **Acknowledgment**: Producers receive ack and can proceed (e.g., free the station).
6. **Cycle**: Broadcasting typically occurs in one clock cycle, allowing immediate capture.

### Key Features

- **Simultaneous Update**: Eliminates the need for point-to-point connections, reducing wiring complexity.
- **Tag-Based Matching**: Enables register renaming and hazard resolution.
- **Bandwidth Management**: Arbitration prevents bus conflicts.
- **Scalability**: Can support multiple broadcasts per cycle if bus is wide enough.
- **Timing**: Synchronous broadcast ensures all consumers see the data at the same time.

### Integration with Tomasulo's Algorithm

- In the Write-Back Phase, results are broadcast to resolve dependencies.
- Allows out-of-order completion while maintaining correct data flow.
- Critical for achieving the algorithm's performance benefits by quickly propagating results.

This design facilitates the dynamic nature of Tomasulo's algorithm, enabling efficient communication across the processor core.

## Implementation Details

The Common Data Bus can be implemented with the following HDL-style pseudocode:

```
class CommonDataBus:
  cdb_valid <= false
  cdb_tag <= 0
  cdb_value <= 0

  def on_clock():
    valid_producers = [p for p in producers if p.valid]  # combinational
    if valid_producers:
      selected = valid_producers[0]  # arbitration logic
      cdb_valid <= true
      cdb_tag <= selected.tag
      cdb_value <= selected.value
    else:
      cdb_valid <= false
```
