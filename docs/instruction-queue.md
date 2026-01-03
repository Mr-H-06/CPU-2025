# Instruction Queue

## Overview

The Instruction Queue is a key component in the Tomasulo's algorithm implementation within the CPU simulator. It holds decoded instructions that are waiting to be issued to reservation stations, ensuring that instructions are processed in program order for correct execution. This component acts as a buffer between the instruction fetch/decode stage and the issue stage, managing the flow of instructions to prevent stalls and maintain sequential consistency.

## Ports

In the Chisel hardware description, the Instruction Queue module would have the following IO ports:

### Inputs

- `clock`: Clock signal for synchronous operations.
- `reset`: Reset signal to initialize the queue.
- `enq_valid`: Boolean signal indicating if there is a valid decoded instruction to enqueue.
- `enq_bits`: Bundle containing the decoded instruction data, including:
  - `opcode`: Operation code (e.g., ADD, LOAD, etc.).
  - `rd`: Destination register.
  - `rs1`: Source register 1.
  - `rs2`: Source register 2.
  - `imm`: Immediate value (if applicable).
  - `funct3`: Function field for RISC-V instructions.
  - `funct7`: Additional function field.
- `deq_ready`: Boolean signal from downstream indicating readiness to accept an instruction.

### Outputs

- `enq_ready`: Boolean signal indicating if the queue can accept a new instruction.
- `deq_valid`: Boolean signal indicating if there is a valid instruction available for dequeue.
- `deq_bits`: Bundle containing the instruction data to be issued, same structure as `enq_bits`.
- `full`: Boolean signal indicating if the queue is full.
- `empty`: Boolean signal indicating if the queue is empty.

## Inner Workings

### Structure

The Instruction Queue is implemented as a First-In-First-Out (FIFO) buffer with a fixed depth (e.g., 16 or 32 entries, configurable). It uses a circular buffer or shift register approach in Chisel for efficient hardware synthesis.

- **Head and Tail Pointers**: Two pointers track the enqueue and dequeue positions.
- **Valid Bits**: Each entry has a valid bit to distinguish between occupied and free slots.
- **Data Storage**: An array of bundles storing the instruction data.

### Operation

1. **Enqueue**: When `enq_valid` is true and the queue is not full (`enq_ready` is true), the instruction is written to the tail position, and the tail pointer is incremented.
2. **Dequeue**: When `deq_ready` is true and the queue is not empty (`deq_valid` is true), the instruction at the head is read, the valid bit is cleared, and the head pointer is incremented.
3. **Full/Empty Detection**: The queue is full when the next tail position equals the head and all entries are valid. Empty when head equals tail and no valid entries.
4. **Program Order Maintenance**: Instructions are dequeued in the order they were enqueued, ensuring sequential execution semantics.
5. **Hazard Handling**: The queue does not directly handle data hazards; it relies on the reservation stations and register file for dependency resolution.

### Integration with Tomasulo's Algorithm

- In the Issue Phase, the queue provides instructions to available reservation stations.
- It prevents issuing when no stations are free or when operands are not ready (though readiness is checked elsewhere).
- The queue size affects the pipeline depth and potential for out-of-order execution.

This design ensures efficient instruction buffering while maintaining the necessary ordering for correct program execution.

## Implementation Details

The Instruction Queue can be implemented as a FIFO buffer with the following HDL-style pseudocode:

```
class InstructionQueue:
  buffer <= []  # Sequential state
  enq_ready <= true
  deq_valid <= false
  deq_bits <= None
  full <= false
  empty <= true

  def on_clock():
    # Enqueue logic
    if enq_valid and len(buffer) < max_size:
      buffer <= buffer + [enq_bits]  # append
      enq_ready <= true
    else:
      enq_ready <= false

    # Dequeue logic
    if deq_ready and buffer:
      deq_bits <= buffer[0]
      buffer <= buffer[1:]  # pop front
      deq_valid <= true
    else:
      deq_valid <= false

    # Status
    full <= (len(buffer) == max_size)
    empty <= (len(buffer) == 0)
```
