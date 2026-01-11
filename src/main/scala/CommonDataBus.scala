import chisel3._
import chisel3.util._

class CDBData extends Bundle {
  val index = UInt(5.W)
  val value = UInt(32.W)
}

class CommonDataBus extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val lsb = Flipped(Decoupled(new CDBData))
    val alu = Flipped(Decoupled(new CDBData))
    val rs = Valid(new CDBData)
    val rf = Valid(new CDBData)
    val rob = Valid(new CDBData)
  })

  val arbiter = Module(new RRArbiter(new CDBData, 2))
  arbiter.io.in(0) <> io.lsb
  arbiter.io.in(1) <> io.alu
  arbiter.io.out.ready := true.B

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