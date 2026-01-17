import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class InstructionFetchTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "InstructionFetch"

  it should "request and output instructions in order" in {
    test(new InstructionFetch()) { c =>
      // init/reset path
      c.io.clear.poke(false.B)
      c.io.mem_iout_ready.poke(true.B)
      c.io.mem_iout_valid.poke(false.B)
      c.io.out.ready.poke(false.B)

      // set PC to 0
      c.io.resetPC.poke(0.U)
      c.io.resetValid.poke(true.B)
      c.clock.step(1)
      c.io.resetValid.poke(false.B)

      // first fetch/emit: addi x1,x0,1 (0x00100093)
      c.clock.step(1)
      c.io.mem_iout_valid.poke(true.B)
      c.io.mem_iout_data.poke("h00100093".U)
      c.clock.step(1)
      c.io.mem_iout_valid.poke(false.B)

      // hold downstream, then check output
      c.clock.step(1)
      c.io.out.valid.expect(true.B)
      c.io.out.bits.instr.expect("h00100093".U)
      c.io.out.bits.rd.expect(1.U)
      c.io.out.bits.rs1.expect(0.U)
      c.io.out.bits.imm.expect(1.U)

      // consume first, then beq x1,x0,+8 (0x00008463)
      c.io.out.ready.poke(true.B)
      c.clock.step(1)
      c.io.out.ready.poke(false.B)
      c.io.mem_iout_valid.poke(true.B)
      // beq x1,x0, +8  (0x00008463)
      c.io.mem_iout_data.poke("h00008463".U)
      c.clock.step(1)
      c.io.mem_iout_valid.poke(false.B)

      c.clock.step(1)
      c.io.out.valid.expect(true.B)
      c.io.out.bits.instr.expect("h00008463".U)
      c.io.out.bits.rd.expect(0.U)
      c.io.out.bits.rs1.expect(1.U)
      c.io.out.bits.rs2.expect(0.U)
      c.io.out.bits.imm.expect(8.U)
    }
  }
}
