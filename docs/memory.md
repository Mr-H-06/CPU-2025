# Memory

## Overview

The memory is divided into two parts, ROM, which stores the program, and RAM, which stores data.

## Ports

The memory has the following ports:

### Inputs

- `Iread`: Location of instruction fetch (validness protocol).
- `mem_access`: Bundle with issue data (validness protocol):
  - `op`: Operation code.
  - `value`: Store value.
  - `address`: Load/Store address.

### Outputs

- `instruction`: Instruction of four bytes.
- `value`: Memory read value with validness.

## Inner Workings

On `Iread` valid signal, read four bytes of memory at `Iread` value address, and output it as `instruction`.

On `mem_access` valid signal, if the operation is a read, read memory at `address` according to specific operation mode, and output it as `value`. Otherwise store `mem_access_value` at `address` according to the operation mode, and also output `mem_access_value` to indicate done.

## Implementation Details

It is guaranteed that `Iread` and `mem_access` don't access the same memory.

The memory is implemented in Chisel as `SyncReadMem`, its outputs go through a `ShiftRegister` to simulate lag. The memory is initialized by reading from a file. The file format is Intel Hex, `memory-example.data` is an example. As `loadMemoryFromFile` only accepts raw data files, the input is converted by a helper function to `{filename}.converted`.

The memory is little-endian: byte order should be reversed in IF and load/store.

`op` is one of the following:

- `lb`, Load Byte, 1 byte of memory, sign-extended.
- `lbu`, Load Byte (Unsigned), 1 byte of memory, zero-extended.
- `lh`, Load Half-word, 2 bytes of memory, sign-extended.
- `lhu`, Load Half-word (Unsigned), 2 bytes of memory, zero-extended.
- `lw`, Load Word, 4 bytes of memory.
- `sb`, Store Byte, 1 byte of least significance.
- `sh`, Store Half-word, 2 bytes of least significance.
- `sw`, Store Word, 4 bytes.
