import chisel3._
import chisel3.util._
import utils._

class CommonDataBus extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val lsb = Flipped(ValidIO(new CDBData))
    val alu = Flipped(ValidIO(new CDBData))
    val rs = ValidIO(new CDBData)
    val rf = ValidIO(new CDBData)
    val rob = ValidIO(new CDBData)
  })
  
  val lsbQueue = Module(new ClearQueue(new CDBData, 4))
  val aluQueue = Module(new ClearQueue(new CDBData, 4))

  lsbQueue.io.enq.valid <> io.lsb.valid
  lsbQueue.io.enq.bits <> io.lsb.bits
  lsbQueue.io.clear <> io.reset
  aluQueue.io.enq.valid <> io.alu.valid
  aluQueue.io.enq.bits <> io.alu.bits
  aluQueue.io.clear <> io.reset

  assert(lsbQueue.io.enq.ready, "lsbQueue overflow")
  assert(aluQueue.io.enq.ready, "aluQueue overflow")

  val arbiter = Module(new RRArbiter(new CDBData, 2))
  arbiter.io.in(0) <> lsbQueue.io.deq
  arbiter.io.in(1) <> aluQueue.io.deq
  arbiter.io.out.ready := true.B  // Consumers are always ready

  when(io.reset) {
    io.rs.valid := false.B
    io.rs.bits := 0.U.asTypeOf(new CDBData)
    io.rf.valid := false.B
    io.rf.bits := 0.U.asTypeOf(new CDBData)
    io.rob.valid := false.B
    io.rob.bits := 0.U.asTypeOf(new CDBData)
  }.otherwise {
    io.rs.valid := arbiter.io.out.valid
    io.rs.bits := arbiter.io.out.bits
    io.rf.valid := arbiter.io.out.valid
    io.rf.bits := arbiter.io.out.bits
    io.rob.valid := arbiter.io.out.valid
    io.rob.bits := arbiter.io.out.bits
  }
}