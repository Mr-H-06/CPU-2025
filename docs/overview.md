# Overview

This is a CPU simulator using Tomasulo's algorithm, written in Chisel. It uses the RISC-V instruction set.

## Tomasulo's Algorithm

Tomasulo's algorithm is a hardware algorithm for dynamic scheduling of instructions that allows out-of-order execution in pipelined processors. It eliminates name dependencies by using register renaming and reservation stations, enabling better utilization of functional units and reducing stalls due to data hazards.

## Key Components

### Instruction Queue

The instruction queue holds decoded instructions that are waiting to be issued to reservation stations. It ensures that instructions are processed in program order for correct execution.

### Reservation Stations

Reservation stations are buffers associated with each functional unit. They store the operation to be performed, the operands (either values or tags indicating which station will produce the value), and control information. Each station can hold one instruction at a time.

### Functional Units

Functional units perform the actual computations, such as arithmetic operations (ALU). They are connected to reservation stations and execute operations when all operands are available.

### Common Data Bus (CDB)

The Common Data Bus is a broadcast mechanism that carries results from completed operations. When a functional unit finishes an operation, it broadcasts the result along with the destination tag on the CDB, allowing multiple reservation stations and the register file to capture the value simultaneously.

### Register File

The register file stores the current values of architectural registers. Each register entry includes a value and a tag that indicates which ROB entry will produce the next value for that register. This implements register renaming to handle write-after-write (WAW) and write-after-read (WAR) hazards.

### Load/Store Buffer

The load/store buffer manages memory operations. It handles the ordering of loads and stores to ensure memory consistency, preventing hazards like read-after-write (RAW) in memory accesses.

### Reorder Buffer

The Reorder Buffer (ROB) is a circular buffer that maintains instructions in program order. It assigns unique tags for register renaming, tracks instruction completion, and ensures in-order commit of results to the architectural state, preventing speculative updates from becoming visible prematurely.

## How Components Work Together

The Tomasulo algorithm with Reorder Buffer operates in four main phases: issue, execute, write-back, and commit.

1. **Issue Phase**: Instructions are fetched, decoded, and issued to available reservation stations and allocated an entry in the ROB. The ROB assigns a unique tag for the destination, which is used for register renaming. If operands are not ready, their tags are stored in the reservation station.

2. **Execute Phase**: Once all operands for an operation in a reservation station are available (either from the register file or via the CDB), the functional unit begins execution. Multiple operations can execute in parallel as long as their dependencies are resolved.

3. **Write-Back Phase**: Upon completion, the result is written to the corresponding ROB entry and broadcast on the CDB with the destination tag. All reservation stations, the register file, and ROB listen to the CDB; if they have a matching tag, they capture the value and mark the operand as ready.

4. **Commit Phase**: Instructions are retired from the ROB in program order once their results are written back and all previous instructions have committed. This updates the architectural register file and ensures memory operations are finalized, maintaining correct program semantics.

This dynamic scheduling with ROB allows instructions to execute out-of-order while ensuring in-order commit, improving processor performance by reducing idle time in functional units and providing precise exception handling.
