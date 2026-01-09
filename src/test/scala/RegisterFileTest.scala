import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class RegisterFileTest extends AnyFlatSpec with ChiselScalatestTester {

  "RegisterFile" should "initialize registers correctly" in {
    test(new RegisterFile) { dut =>
      // Register 0 should always be zero with invalid tag
      dut.io.alu_regs(0).value.expect(0.U)
      dut.io.alu_regs(0).tag_valid.expect(false.B)
      dut.io.lsb_regs(0).value.expect(0.U)
      dut.io.lsb_regs(0).tag_valid.expect(false.B)

      // Other registers should be initialized to zero with invalid tags
      for (i <- 1 until 32) {
        dut.io.alu_regs(i).value.expect(0.U)
        dut.io.alu_regs(i).tag_valid.expect(false.B)
        dut.io.lsb_regs(i).value.expect(0.U)
        dut.io.lsb_regs(i).tag_valid.expect(false.B)
      }
    }
  }

  it should "handle writeback operations" in {
    test(new RegisterFile) { dut =>
      // Write to register 1
      dut.io.writeback_valid.poke(true.B)
      dut.io.writeback_index.poke(1.U)
      dut.io.writeback_tag.poke(5.U)
      dut.io.writeback_value.poke(42.U)
      dut.clock.step()

      dut.io.alu_regs(1).value.expect(42.U)
      dut.io.lsb_regs(1).value.expect(42.U)
      // Tag doesn't match initial (0), so tag_valid remains false
      dut.io.alu_regs(1).tag_valid.expect(false.B)
    }
  }

  it should "handle destination updates from IF" in {
    test(new RegisterFile) { dut =>
      // Update tag for register 2
      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(2.U)
      dut.io.tail.poke(10.U)
      dut.clock.step()

      dut.io.alu_regs(2).tag.expect(10.U)
      dut.io.alu_regs(2).tag_valid.expect(true.B)
      dut.io.lsb_regs(2).tag.expect(10.U)
      dut.io.lsb_regs(2).tag_valid.expect(true.B)
    }
  }

  it should "handle clear signal" in {
    test(new RegisterFile) { dut =>
      // First set some tags
      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(1.U)
      dut.io.tail.poke(5.U)
      dut.clock.step()

      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(2.U)
      dut.io.tail.poke(15.U)
      dut.clock.step()

      // Verify tags are set
      dut.io.alu_regs(1).tag_valid.expect(true.B)
      dut.io.alu_regs(2).tag_valid.expect(true.B)

      // Clear
      dut.io.clear.poke(true.B)
      dut.clock.step()

      // Tags should be invalid
      dut.io.alu_regs(1).tag_valid.expect(false.B)
      dut.io.alu_regs(2).tag_valid.expect(false.B)
      // Values should remain
      dut.io.alu_regs(1).value.expect(0.U)
      dut.io.alu_regs(2).value.expect(0.U)
    }
  }

  it should "handle writeback tag matching" in {
    test(new RegisterFile) { dut =>
      // Set tag for register 1
      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(1.U)
      dut.io.tail.poke(7.U)
      dut.clock.step()

      // Ensure destination_valid is false for next cycle
      dut.io.destination_valid.poke(false.B)

      // Writeback with matching tag
      dut.io.writeback_valid.poke(true.B)
      dut.io.writeback_index.poke(1.U)
      dut.io.writeback_tag.poke(7.U)
      dut.io.writeback_value.poke(99.U)
      dut.clock.step()

      // Tag should become invalid
      dut.io.alu_regs(1).tag_valid.expect(false.B)
      dut.io.alu_regs(1).value.expect(99.U)
    }
  }

  it should "ignore writes to register 0" in {
    test(new RegisterFile) { dut =>
      // Try to write to register 0
      dut.io.writeback_valid.poke(true.B)
      dut.io.writeback_index.poke(0.U)
      dut.io.writeback_value.poke(123.U)
      dut.clock.step()

      // Register 0 should remain zero
      dut.io.alu_regs(0).value.expect(0.U)
      dut.io.alu_regs(0).tag_valid.expect(false.B)
    }
  }

  it should "ignore destination updates to register 0" in {
    test(new RegisterFile) { dut =>
      // Try to update tag for register 0
      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(0.U)
      dut.io.tail.poke(20.U)
      dut.clock.step()

      // Register 0 should remain with invalid tag
      dut.io.alu_regs(0).tag_valid.expect(false.B)
    }
  }

  it should "handle simultaneous writeback and destination update with precedence" in {
    test(new RegisterFile) { dut =>
      // Set initial tag
      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(3.U)
      dut.io.tail.poke(8.U)
      dut.clock.step()

      // Simultaneous: writeback and new destination update
      dut.io.writeback_valid.poke(true.B)
      dut.io.writeback_index.poke(3.U)
      dut.io.writeback_tag.poke(8.U) // matches
      dut.io.writeback_value.poke(77.U)

      dut.io.destination_valid.poke(true.B)
      dut.io.destination.poke(3.U)
      dut.io.tail.poke(12.U) // new tag

      dut.clock.step()

      // Since IF has higher precedence, tag should be updated to 12, valid
      dut.io.alu_regs(3).tag.expect(12.U)
      dut.io.alu_regs(3).tag_valid.expect(true.B)
      dut.io.alu_regs(3).value.expect(77.U)
    }
  }
}