# ClearQueue

A simple FIFO queue with one-cycle clear.

## ports

### inputs

- clear signal
- enqueue(ready/valid protocol)

### output

- dequeue(ready/valid protocol)

## usage

`ClearQueue[T <: Data](gen: T, entries: Int)` constructs a queue of `T` with `entries` capacity.

On clear signal, ignore all other inputs and clear contents.

Otherwise, enqueue/dequeue takes effect one cycle after receiving valid value/ready signal.

No penetration - the minimum delay between receiving a value and dequeueing it is two cycles.
