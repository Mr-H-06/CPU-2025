import chisel3._
import utils._

case class RegisterEntry() extends Bundle {
  val value = UInt(32.W)
  val tag = UInt(5.W)
  val tag_valid = Bool()
}

class RegisterFile extends Module {
  val io = IO(new Bundle {
    // Inputs
    val writeback_valid = Input(Bool())
    val writeback_index = Input(UInt(5.W))
    val writeback_tag = Input(UInt(5.W))
    val writeback_value = Input(UInt(32.W))
    val tail = Input(UInt(5.W))
    val destination_valid = Input(Bool())
    val destination = Input(UInt(5.W))
    val clear = Input(Bool())

    // Outputs
    val alu_regs = Output(Vec(32, RegisterEntry()))
    val lsb_regs = Output(Vec(32, RegisterEntry()))
    val debug_regs = Output(Vec(32, UInt(32.W)))
  })

  val regs = RegInit(VecInit(Seq.fill(32)(0.U.asTypeOf(new RegisterEntry))))

  // Ensure register 0 is always zero with invalid tag
  regs(0).value := 0.U
  regs(0).tag_valid := false.B

  // Output the register file to reservation stations
  io.alu_regs := regs
  io.lsb_regs := regs
  io.debug_regs := regs.map(_.value)

  // Logic for updates
  when(io.clear) {
    for (i <- 0 until 32) {
      regs(i).tag_valid := false.B
    }
  }

  // ROB writeback (allowed even during a clear so completed ops are not lost)
  when(io.writeback_valid && io.writeback_index =/= 0.U) {
    regs(io.writeback_index).value := io.writeback_value
    when(regs(io.writeback_index).tag === io.writeback_tag) {
      regs(io.writeback_index).tag_valid := false.B
    }
  }
  // IF bookkeeping (higher precedence, but suppressed during a clear)
  when(!io.clear && io.destination_valid && io.destination =/= 0.U) {
    regs(io.destination).tag := io.tail
    regs(io.destination).tag_valid := true.B
  }
}