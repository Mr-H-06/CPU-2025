package utils

import chisel3._
import chisel3.util._
import chisel3.experimental._

class ClearQueue[T <: Data](gen: T, entries: Int) extends Module {
  require(entries > 0, s"number of entries must be >0, got $entries")

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
    val clear = Input(Bool())
  })
  
  val mem = Reg(Vec(entries, gen))
  val enqPtr = Counter(entries)
  val deqPtr = Counter(entries)
  val enqReady = RegInit(true.B)
  val deqValid = RegInit(false.B)
  val deqValue = RegInit(0.U.asTypeOf(gen))

  val deqFire = WireInit(io.deq.ready && (enqPtr.value =/= deqPtr.value || !enqReady))

  io.enq.ready := enqReady
  io.deq.valid := deqValid
  io.deq.bits := deqValue

  when(io.clear) {
    enqPtr.reset()
    deqPtr.reset()
    enqReady := true.B
    deqValid := false.B
    deqValue := 0.U.asTypeOf(gen)
  }.otherwise {
    when(io.enq.fire) {
      when(deqFire) { // enq and deq, readiness doesn't change
        when(enqPtr.value === deqPtr.value) {
          assert(false.B, "oops: found bug in ClearQueue logic")
        }.otherwise {
          deqValid := true.B
          deqValue := mem(deqPtr.value)
          mem(enqPtr.value) := io.enq.bits
          enqPtr.inc()
          deqPtr.inc()
        }
      }.otherwise { // enq and not deq
        deqValid := false.B
        deqValue := 0.U.asTypeOf(gen)
        mem(enqPtr.value) := io.enq.bits
        val enqWrap = WireInit(enqPtr.inc())
        enqReady := (enqWrap && deqPtr.value =/= 0.U) || ((!enqWrap) && deqPtr.value =/= enqPtr.value + 1.U)
      }
    }.otherwise {
      when(deqFire) { // deq and not enq
        deqValid := true.B
        deqValue := mem(deqPtr.value)
        val deqWrap = WireInit(deqPtr.inc())
        enqReady := true.B
      }.otherwise { // not enq and not deq, nothing changes
        deqValid := false.B
        deqValue := 0.U.asTypeOf(gen)
      }
    }
  }
}