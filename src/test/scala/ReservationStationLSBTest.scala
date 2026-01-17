import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class ReservationStationLSBTest extends AnyFlatSpec with ChiselScalatestTester {
  private def initRfAndRob(c: ReservationStationLSB): Unit = {
    for (i <- 0 until 32) {
      c.io.rf_entries(i).value.poke(0.U)
      c.io.rf_entries(i).tag.poke(0.U)
      c.io.rf_entries(i).tag_valid.poke(false.B)
      c.io.rob_entries(i).valid.poke(false.B)
      c.io.rob_entries(i).value.poke(0.U)
    }
    c.io.cdb.valid.poke(false.B)
    c.io.cdb.bits.index.poke(0.U)
    c.io.cdb.bits.value.poke(0.U)
    c.io.mem_ready.poke(true.B)
  }

  "ReservationStationLSB" should "issue and execute a load with ready operands" in {
    test(new ReservationStationLSB(4)) { c =>
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      initRfAndRob(c)

      // Setup RF: reg 1 has value 100, no tag
      c.io.rf_entries(1).value.poke(100.U)
      c.io.rf_entries(1).tag_valid.poke(false.B)
      // others default to tag_valid=false, value=0

      // Issue load: lw x1, 4(x1)
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.lw)
      c.io.issue_bits.op1_index.poke(1.U) // dest x1
      c.io.issue_bits.op2_index.poke(1.U) // base x1
      c.io.issue_bits.op3_value.poke(4.U) // offset
      c.io.issue_bits.dest_tag.poke(1.U)
      c.io.cdb.valid.poke(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(MemOpEnum.lw)
      c.io.exec_bits.value.expect(0.U)
      c.io.exec_bits.address.expect(104.U) // 100 + 4
      c.io.exec_bits.index.expect(1.U)
    }
  }

  "ReservationStationLSB" should "handle load with pending operand" in {
    test(new ReservationStationLSB(4)) { c =>
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      initRfAndRob(c)

      // RF: reg 1 has tag 2 (pending)
      c.io.rf_entries(1).tag.poke(2.U)
      c.io.rf_entries(1).tag_valid.poke(true.B)
      // ROB defaults to invalid

      // Issue load
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.lw)
      c.io.issue_bits.op1_index.poke(1.U)
      c.io.issue_bits.op2_index.poke(1.U)
      c.io.issue_bits.op3_value.poke(4.U)
      c.io.issue_bits.dest_tag.poke(1.U)
      c.io.cdb.valid.poke(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(false.B) // not ready

      // CDB broadcast for tag 2
      c.io.cdb.valid.poke(true.B)
      c.io.cdb.bits.index.poke(2.U)
      c.io.cdb.bits.value.poke(200.U)
      c.io.issue_valid.poke(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.value.expect(0.U)
      c.io.exec_bits.address.expect(204.U) // 200 + 4
      c.io.exec_bits.index.expect(1.U)
    }
  }

  "ReservationStationLSB" should "handle store with pending operands" in {
    test(new ReservationStationLSB(4)) { c =>
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      initRfAndRob(c)

      // RF: reg 1 ready, reg 2 pending tag 3
      c.io.rf_entries(1).value.poke(100.U)
      c.io.rf_entries(1).tag_valid.poke(false.B)
      c.io.rf_entries(2).tag.poke(3.U)
      c.io.rf_entries(2).tag_valid.poke(true.B)
      // ROB defaults to invalid

      // Issue store: sw x1, 8(x1)
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.sw)
      c.io.issue_bits.op1_index.poke(1.U) // value x1
      c.io.issue_bits.op2_index.poke(1.U) // base x1
      c.io.issue_bits.op3_value.poke(8.U) // offset
      c.io.issue_bits.dest_tag.poke(1.U)
      c.io.cdb.valid.poke(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(MemOpEnum.sw)
      c.io.exec_bits.value.expect(100.U)
      c.io.exec_bits.address.expect(108.U) // 100 + 8
      c.io.exec_bits.index.expect(1.U)
    }
  }

  "ReservationStationLSB" should "execute in order" in {
    test(new ReservationStationLSB(4)) { c =>
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      initRfAndRob(c)

      c.io.rf_entries(1).value.poke(100.U)
      c.io.rf_entries(1).tag_valid.poke(false.B)
      c.io.rf_entries(2).value.poke(200.U)
      c.io.rf_entries(2).tag_valid.poke(false.B)
      // ROB defaults to invalid

      // Issue load1
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.lw)
      c.io.issue_bits.op1_index.poke(1.U)
      c.io.issue_bits.op2_index.poke(1.U)
      c.io.issue_bits.op3_value.poke(4.U)
      c.io.issue_bits.dest_tag.poke(1.U)
      c.io.cdb.valid.poke(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.value.expect(0.U)
      c.io.exec_bits.address.expect(104.U) // 100 + 4
      c.io.exec_bits.index.expect(1.U)

      // Issue load2
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.lw)
      c.io.issue_bits.op1_index.poke(2.U)
      c.io.issue_bits.op2_index.poke(2.U)
      c.io.issue_bits.op3_value.poke(8.U)
      c.io.issue_bits.dest_tag.poke(2.U)

      c.clock.step(1)

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.value.expect(0.U)
      c.io.exec_bits.address.expect(208.U) // 200 + 8
      c.io.exec_bits.index.expect(2.U)
    }
  }

  "ReservationStationLSB" should "handle reset" in {
    test(new ReservationStationLSB(4)) { c =>
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      initRfAndRob(c)


      // Issue something
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.lw)
      c.io.issue_bits.op1_index.poke(1.U)
      c.io.issue_bits.op2_index.poke(1.U)
      c.io.issue_bits.op3_value.poke(4.U)
      c.io.issue_bits.dest_tag.poke(1.U)
      c.io.cdb.valid.poke(false.B)

      c.clock.step(1)

      c.io.exec_valid.expect(true.B)

      c.io.clear.poke(true.B)
      c.io.issue_valid.poke(false.B)
      c.clock.step(1)

      c.io.exec_valid.expect(false.B)
      c.io.issue_ready.expect(true.B)
    }
  }

  "ReservationStationLSB" should "backpressure when memory not ready" in {
    test(new ReservationStationLSB(4)) { c =>
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      initRfAndRob(c)

      // Ready operands
      c.io.rf_entries(1).value.poke(50.U)
      c.io.rf_entries(1).tag_valid.poke(false.B)
      c.io.rf_entries(2).value.poke(100.U)
      c.io.rf_entries(2).tag_valid.poke(false.B)

      // Issue store: sw x1, 4(x2), 立即就绪但 mem_ready=0
      c.io.mem_ready.poke(false.B)
      c.io.issue_valid.poke(true.B)
      c.io.issue_bits.op.poke(MemOpEnum.sw)
      c.io.issue_bits.op1_index.poke(1.U)
      c.io.issue_bits.op2_index.poke(2.U)
      c.io.issue_bits.op3_value.poke(4.U)
      c.io.issue_bits.dest_tag.poke(1.U)

      c.clock.step(1)

      // 应该阻塞发射
      c.io.exec_valid.expect(false.B)

      // mem_ready 拉高后当前拍应即可拉高 exec_valid（无需再等一个周期）
      c.io.mem_ready.poke(true.B)
      c.io.issue_valid.poke(false.B)

      c.io.exec_valid.expect(true.B)
      c.io.exec_bits.op.expect(MemOpEnum.sw)
      c.io.exec_bits.address.expect(104.U)
      c.io.exec_bits.value.expect(50.U)
      c.io.exec_bits.index.expect(1.U)
    }
  }
}