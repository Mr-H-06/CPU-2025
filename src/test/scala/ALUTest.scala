import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class ALUTest extends AnyFlatSpec with ChiselScalatestTester {
  "ALU" should "perform ADD operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.ready.expect(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.ADD)
      c.io.exec_bits.op1.poke(10.U)
      c.io.exec_bits.op2.poke(5.U)
      c.io.exec_bits.tag.poke(1.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(15.U)
      c.io.result_bits.tag.expect(1.U)
      c.io.ready.expect(false.B)
      c.clock.step(1)
      c.io.result_valid.expect(false.B)
      c.io.ready.expect(true.B)
    }
  }

  "ALU" should "perform SUB operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.SUB)
      c.io.exec_bits.op1.poke(10.U)
      c.io.exec_bits.op2.poke(5.U)
      c.io.exec_bits.tag.poke(2.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(5.U)
      c.io.result_bits.tag.expect(2.U)
    }
  }

  "ALU" should "perform AND operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.AND)
      c.io.exec_bits.op1.poke(0xF0.U)
      c.io.exec_bits.op2.poke(0xFF.U)
      c.io.exec_bits.tag.poke(3.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(0xF0.U)
      c.io.result_bits.tag.expect(3.U)
    }
  }

  "ALU" should "perform OR operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.OR)
      c.io.exec_bits.op1.poke(0xF0.U)
      c.io.exec_bits.op2.poke(0x0F.U)
      c.io.exec_bits.tag.poke(4.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(0xFF.U)
      c.io.result_bits.tag.expect(4.U)
    }
  }

  "ALU" should "perform XOR operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.XOR)
      c.io.exec_bits.op1.poke(0xFF.U)
      c.io.exec_bits.op2.poke(0xF0.U)
      c.io.exec_bits.tag.poke(5.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(0x0F.U)
      c.io.result_bits.tag.expect(5.U)
    }
  }

  "ALU" should "perform SLT operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.SLT)
      c.io.exec_bits.op1.poke(5.U)
      c.io.exec_bits.op2.poke(10.U)
      c.io.exec_bits.tag.poke(6.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(1.U)
      c.io.result_bits.tag.expect(6.U)
    }
  }

  "ALU" should "perform SLTU operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.SLTU)
      c.io.exec_bits.op1.poke(10.U)
      c.io.exec_bits.op2.poke(5.U)
      c.io.exec_bits.tag.poke(7.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(0.U)
      c.io.result_bits.tag.expect(7.U)
    }
  }

  "ALU" should "perform LL operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.LL)
      c.io.exec_bits.op1.poke(1.U)
      c.io.exec_bits.op2.poke(2.U)
      c.io.exec_bits.tag.poke(8.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(4.U)
      c.io.result_bits.tag.expect(8.U)
    }
  }

  "ALU" should "perform RL operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.RL)
      c.io.exec_bits.op1.poke(8.U)
      c.io.exec_bits.op2.poke(1.U)
      c.io.exec_bits.tag.poke(9.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(4.U)
      c.io.result_bits.tag.expect(9.U)
    }
  }

  "ALU" should "perform RA operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.RA)
      c.io.exec_bits.op1.poke("hFFFFFFF8".U) // -8 in two's complement
      c.io.exec_bits.op2.poke(1.U)
      c.io.exec_bits.tag.poke(10.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect("hFFFFFFFC".U) // -4
      c.io.result_bits.tag.expect(10.U)
    }
  }

  "ALU" should "perform EQ operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.EQ)
      c.io.exec_bits.op1.poke(5.U)
      c.io.exec_bits.op2.poke(5.U)
      c.io.exec_bits.tag.poke(11.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(1.U)
      c.io.result_bits.tag.expect(11.U)
    }
  }

  "ALU" should "perform NE operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.NE)
      c.io.exec_bits.op1.poke(5.U)
      c.io.exec_bits.op2.poke(6.U)
      c.io.exec_bits.tag.poke(12.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(1.U)
      c.io.result_bits.tag.expect(12.U)
    }
  }

  "ALU" should "perform GE operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.GE)
      c.io.exec_bits.op1.poke(10.U)
      c.io.exec_bits.op2.poke(5.U)
      c.io.exec_bits.tag.poke(13.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(1.U)
      c.io.result_bits.tag.expect(13.U)
    }
  }

  "ALU" should "perform GEU operation" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.GEU)
      c.io.exec_bits.op1.poke(10.U)
      c.io.exec_bits.op2.poke(5.U)
      c.io.exec_bits.tag.poke(14.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.result_bits.value.expect(1.U)
      c.io.result_bits.tag.expect(14.U)
    }
  }

  "ALU" should "handle clear signal" in {
    test(new ALU) { c =>
      c.io.CDB_ready.poke(true.B)
      c.io.exec_valid.poke(true.B)
      c.io.exec_bits.op.poke(AluOpEnum.ADD)
      c.io.exec_bits.op1.poke(1.U)
      c.io.exec_bits.op2.poke(1.U)
      c.io.exec_bits.tag.poke(1.U)
      c.clock.step(1)
      c.io.result_valid.expect(true.B)
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.result_valid.expect(false.B)
      c.io.ready.expect(true.B)
    }
  }
}