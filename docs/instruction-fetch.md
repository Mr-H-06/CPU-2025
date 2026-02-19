# Instruction Fetch

## Ports

The Instruction Fetch module has the following IO ports:

### Inputs

- `reset`: Value to reset program counter to, from ROB with validness indicator.
- `instruction`: Instruction read from memory.
- `instruction_ready`: Readiness indicator of memory.

### Outputs

- decoded instruction (ready/valid protocol) to ROB/LSB/ALU
- `destination`: instruction destination to RF.

## Inner Workings

### Structure

The Instruction Fetch has a program counter, a decoder and a FIFO buffer queue of decoded instructions.

### Operation

On the reset signal, clear all contents of instruction queue and reset program counter to received value.
When the queue is not full, the decoder reads the next instruction and decodes it, putting it in the buffer.
When ROB and corresponding functional unit (ROB/LSB/not required) are ready, issue the next instruction in queue. Tell RF to update register tags.
The current branch prediction method is the most naive, assuming a jump always occurs.

## Implementation Details

When the program counter is changed by control operations, it's two lowest bits should always be set to zero, ensuring correctly aligned instruction fetch.
