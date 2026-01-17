import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class ReservationStationsTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ReservationStations"

  private def initRfAndRob(c: ReservationStations): Unit = {
    for (i <- 0 until 32) {
      c.io.rf_regs(i).value.poke(0.U)
      c.io.rf_regs(i).tag.poke(0.U)
      c.io.rf_regs(i).tag_valid.poke(false.B)
      c.io.rob_values(i).valid.poke(false.B)
      c.io.rob_values(i).value.poke(0.U)
    }
  }

  it should "立即数与就绪寄存器操作数可直接发射" in {
    test(new ReservationStations(4)) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)
      initRfAndRob(c)

      c.io.fu_ready.poke(true.B)
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(AluOpEnum.ADD)
      c.io.issue_bits.op1_index.poke(1.U)
      c.io.issue_bits.op2_value.poke(5.U)
      c.io.issue_bits.op2_type.poke(true.B) // 立即数
      c.io.issue_bits.dest_tag.poke(3.U)

      // RF 提供就绪的 op1
      c.io.rf_regs(1).value.poke(10.U)
      c.io.rf_regs(1).tag_valid.poke(false.B)

      c.clock.step()

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(AluOpEnum.ADD)
      c.io.exec_bits.op1.expect(10.U)
      c.io.exec_bits.op2.expect(5.U)
      c.io.exec_bits.tag.expect(3.U)
    }
  }

  it should "通过 CDB 填充待定操作数后再发射" in {
    test(new ReservationStations(4)) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)
      initRfAndRob(c)

      c.io.fu_ready.poke(true.B)
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(AluOpEnum.SUB)
      c.io.issue_bits.op1_index.poke(2.U)
      c.io.issue_bits.op2_value.poke(1.U)
      c.io.issue_bits.op2_type.poke(true.B)
      c.io.issue_bits.dest_tag.poke(4.U)

      // op1 来自 ROB tag 2，尚未就绪
      c.io.rf_regs(2).tag.poke(2.U)
      c.io.rf_regs(2).tag_valid.poke(true.B)

      c.clock.step()
      c.io.exec_valid.expect(false.B)

      // CDB 广播 tag 2 的值
      c.io.issue_valid.poke(false.B)
      c.io.cdb_valid.poke(true.B)
      c.io.cdb_tag.poke(2.U)
      c.io.cdb_value.poke(11.U)

      c.clock.step()

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(AluOpEnum.SUB)
      c.io.exec_bits.op1.expect(11.U)
      c.io.exec_bits.op2.expect(1.U)
      c.io.exec_bits.tag.expect(4.U)
    }
  }

  it should "保持按队头顺序发射，即便后续指令已就绪" in {
    test(new ReservationStations(4)) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)
      initRfAndRob(c)

      c.io.fu_ready.poke(true.B)

      // 指令 1：op1 待定，阻塞队头
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(AluOpEnum.ADD)
      c.io.issue_bits.op1_index.poke(3.U)
      c.io.issue_bits.op2_value.poke(1.U)
      c.io.issue_bits.op2_type.poke(true.B)
      c.io.issue_bits.dest_tag.poke(5.U)
      c.io.rf_regs(3).tag.poke(6.U)
      c.io.rf_regs(3).tag_valid.poke(true.B)
      c.clock.step()

      // 指令 2：完全就绪，但应等待队头
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(AluOpEnum.ADD)
      c.io.issue_bits.op1_index.poke(4.U)
      c.io.issue_bits.op2_value.poke(2.U)
      c.io.issue_bits.op2_type.poke(true.B)
      c.io.issue_bits.dest_tag.poke(7.U)
      c.io.rf_regs(4).value.poke(20.U)
      c.io.rf_regs(4).tag_valid.poke(false.B)
      c.clock.step()

      c.io.exec_valid.expect(false.B) // 队头未就绪

      // CDB 为队头补全操作数
      c.io.issue_valid.poke(false.B)
      c.io.cdb_valid.poke(true.B)
      c.io.cdb_tag.poke(6.U)
      c.io.cdb_value.poke(30.U)
      c.clock.step()

      // 先发射指令 1
      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(AluOpEnum.ADD)
      c.io.exec_bits.op1.expect(30.U)
      c.io.exec_bits.op2.expect(1.U)
      c.io.exec_bits.tag.expect(5.U)

      // 下一拍应轮到指令 2
      c.clock.step()
      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(AluOpEnum.ADD)
      c.io.exec_bits.op1.expect(20.U)
      c.io.exec_bits.op2.expect(2.U)
      c.io.exec_bits.tag.expect(7.U)
    }
  }
}
