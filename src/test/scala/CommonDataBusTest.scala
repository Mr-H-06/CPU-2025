import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._

import cdb.{CommonDataBus, CDBData}

class CommonDataBusTest extends AnyFlatSpec with ChiselScalatestTester {
  "CommonDataBus" should "initialize with invalid consumers" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(true.B)
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach(_.valid.expect(false.B))
    }
  }

  "CommonDataBus" should "broadcast from LSB" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(1.U)
      c.io.lsb.bits.value.poke(123.U)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach { consumer =>
        consumer.valid.expect(true.B)
        consumer.bits.index.expect(1.U)
        consumer.bits.value.expect(123.U)
      }
    }
  }

  "CommonDataBus" should "broadcast from ALU" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(2.U)
      c.io.alu.bits.value.poke(456.U)
      c.clock.step(1)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach { consumer =>
        consumer.valid.expect(true.B)
        consumer.bits.index.expect(2.U)
        consumer.bits.value.expect(456.U)
      }
    }
  }

  "CommonDataBus" should "arbitrate between LSB and ALU" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      // Both valid
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(3.U)
      c.io.lsb.bits.value.poke(789.U)
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(4.U)
      c.io.alu.bits.value.poke(101.U)
      c.clock.step(1)
      // Should select LSB first (index 0), so lsb.ready = true, alu.ready = false
      c.io.lsb.ready.expect(true.B)
      c.io.alu.ready.expect(false.B)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach { consumer =>
        consumer.valid.expect(true.B)
        consumer.bits.index.expect(3.U)
        consumer.bits.value.expect(789.U)
      }
      // Next cycle, should select ALU, so lsb.ready = false, alu.ready = true
      c.clock.step(1)
      c.io.lsb.ready.expect(false.B)
      c.io.alu.ready.expect(true.B)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach { consumer =>
        consumer.valid.expect(true.B)
        consumer.bits.index.expect(4.U)
        consumer.bits.value.expect(101.U)
      }
    }
  }

  "CommonDataBus" should "handle reset" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(5.U)
      c.io.lsb.bits.value.poke(999.U)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.reset.poke(true.B)
      c.clock.step(1)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach(_.valid.expect(false.B))
    }
  }

  "CommonDataBus" should "not broadcast when no producers valid" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      Seq(c.io.rs, c.io.rf, c.io.rob).foreach(_.valid.expect(false.B))
    }
  }
}