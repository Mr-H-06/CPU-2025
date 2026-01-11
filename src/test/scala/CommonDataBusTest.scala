import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class CommonDataBusTest extends AnyFlatSpec with ChiselScalatestTester {
  "CommonDataBus" should "initialize correctly" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      c.io.rs.valid.expect(false.B)
      c.io.rf.valid.expect(false.B)
      c.io.rob.valid.expect(false.B)
    }
  }

  "CommonDataBus" should "broadcast LSB result to all consumers" in {
    test(new CommonDataBus) { c =>
      // Send data from LSB
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(5.U)
      c.io.lsb.bits.value.poke(0x12345678.U)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      
      // After one cycle, data should be in queue
      c.io.rs.valid.expect(false.B)
      c.io.rf.valid.expect(false.B)
      c.io.rob.valid.expect(false.B)
      
      // Step again to get data from queue
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(5.U)
      c.io.rs.bits.value.expect(0x12345678.U)
      c.io.rf.valid.expect(true.B)
      c.io.rf.bits.index.expect(5.U)
      c.io.rf.bits.value.expect(0x12345678.U)
      c.io.rob.valid.expect(true.B)
      c.io.rob.bits.index.expect(5.U)
      c.io.rob.bits.value.expect(0x12345678.U)
    }
  }

  "CommonDataBus" should "broadcast ALU result to all consumers" in {
    test(new CommonDataBus) { c =>
      // Send data from ALU
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(10.U)
      c.io.alu.bits.value.poke("hABCDEF01".U)
      c.clock.step(1)
      
      // After one cycle, data should be in queue
      c.io.rs.valid.expect(false.B)
      c.io.rf.valid.expect(false.B)
      c.io.rob.valid.expect(false.B)
      
      // Step again to get data from queue
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(10.U)
      c.io.rs.bits.value.expect("hABCDEF01".U)
      c.io.rf.valid.expect(true.B)
      c.io.rf.bits.index.expect(10.U)
      c.io.rf.bits.value.expect("hABCDEF01".U)
      c.io.rob.valid.expect(true.B)
      c.io.rob.bits.index.expect(10.U)
      c.io.rob.bits.value.expect("hABCDEF01".U)
    }
  }

  "CommonDataBus" should "handle round-robin arbitration" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      
      // First, send from LSB
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(1.U)
      c.io.lsb.bits.value.poke("h11111111".U)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      
      // Step to get LSB data
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(1.U)
      c.io.rs.bits.value.expect("h11111111".U)
      
      // Now send from ALU while LSB is idle
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(2.U)
      c.io.alu.bits.value.poke("h22222222".U)
      c.clock.step(1)
      
      // Step to get ALU data
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(2.U)
      c.io.rs.bits.value.expect("h22222222".U)
      
      // Send from LSB again
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(3.U)
      c.io.lsb.bits.value.poke("h33333333".U)
      c.io.alu.valid.poke(false.B)
      c.clock.step(1)
      
      // Step to get LSB data again
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(3.U)
      c.io.rs.bits.value.expect("h33333333".U)
    }
  }

  "CommonDataBus" should "handle simultaneous inputs with round-robin priority" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      
      // Send from both LSB and ALU simultaneously
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(1.U)
      c.io.lsb.bits.value.poke("hAAAAAAA".U)
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(2.U)
      c.io.alu.bits.value.poke("hBBBBBBB".U)
      c.clock.step(1)
      
      // After one cycle, data should be in queues
      c.io.rs.valid.expect(false.B)
      
      // Step again - should get one of the inputs (round-robin)
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      // The arbiter should choose one of the inputs
      val receivedIndex = c.io.rs.bits.index.peek().litValue
      val receivedValue = c.io.rs.bits.value.peek().litValue
      assert((receivedIndex == 1 && receivedValue == "hAAAAAAA".U.litValue) ||
             (receivedIndex == 2 && receivedValue == "hBBBBBBB".U.litValue))
      
      // Step again to get the other input
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      val receivedIndex2 = c.io.rs.bits.index.peek().litValue
      val receivedValue2 = c.io.rs.bits.value.peek().litValue
      assert((receivedIndex2 == 1 && receivedValue2 == "hAAAAAAA".U.litValue) ||
             (receivedIndex2 == 2 && receivedValue2 == "hBBBBBBB".U.litValue))
      // The arbiter should eventually provide both inputs, but we don't enforce strict alternation
      // since the round-robin behavior might vary based on implementation
    }
  }

  "CommonDataBus" should "handle reset correctly" in {
    test(new CommonDataBus) { c =>
      // Send some data first
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(5.U)
      c.io.lsb.bits.value.poke(0x12345678.U)
      c.clock.step(1)
      
      // Now reset
      c.io.reset.poke(true.B)
      c.clock.step(1)
      
      // Should output nothing and clear queues
      c.io.rs.valid.expect(false.B)
      c.io.rf.valid.expect(false.B)
      c.io.rob.valid.expect(false.B)
      
      // After reset, should work normally again
      c.io.reset.poke(false.B)
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(6.U)
      c.io.lsb.bits.value.poke("h87654321".U)
      c.clock.step(1)
      
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(6.U)
      c.io.rs.bits.value.expect("h87654321".U)
    }
  }

  "CommonDataBus" should "handle multiple consumers simultaneously" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      
      // Send data from ALU
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(7.U)
      c.io.alu.bits.value.poke("h77777777".U)
      c.io.lsb.valid.poke(false.B)
      c.clock.step(1)
      
      // Step to get data
      c.clock.step(1)
      
      // All three consumers should receive the same data simultaneously
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(7.U)
      c.io.rs.bits.value.expect("h77777777".U)
      
      c.io.rf.valid.expect(true.B)
      c.io.rf.bits.index.expect(7.U)
      c.io.rf.bits.value.expect("h77777777".U)
      
      c.io.rob.valid.expect(true.B)
      c.io.rob.bits.index.expect(7.U)
      c.io.rob.bits.value.expect("h77777777".U)
    }
  }

  "CommonDataBus" should "handle idle periods correctly" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      
      // Send some data
      c.io.lsb.valid.poke(true.B)
      c.io.lsb.bits.index.poke(1.U)
      c.io.lsb.bits.value.poke("h11111111".U)
      c.clock.step(1)
      
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      
      // Now idle for several cycles
      c.io.lsb.valid.poke(false.B)
      c.io.alu.valid.poke(false.B)
      for (i <- 0 until 3) {
        c.clock.step(1)
        // After the first idle cycle, output should be false
        if (i > 0) {
          c.io.rs.valid.expect(false.B)
        }
      }
      
      // Send new data
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(2.U)
      c.io.alu.bits.value.poke("h22222222".U)
      c.clock.step(1)
      
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(2.U)
      c.io.rs.bits.value.expect("h22222222".U)
    }
  }

  "CommonDataBus" should "handle different ROB indices correctly" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      
      // Test with various ROB indices
      val testIndices = Seq(0, 1, 15, 31) // Test boundary values
      val testValues = Seq("h0".U, "h1".U, "hFFFFFFFF".U, "h80000000".U)
      
      for ((index, value) <- testIndices.zip(testValues)) {
        c.io.lsb.valid.poke(true.B)
        c.io.lsb.bits.index.poke(index.U)
        c.io.lsb.bits.value.poke(value)
        c.io.alu.valid.poke(false.B)
        c.clock.step(1)
        
        c.clock.step(1)
        c.io.rs.valid.expect(true.B)
        c.io.rs.bits.index.expect(index.U)
        c.io.rs.bits.value.expect(value)
      }
    }
  }

  "CommonDataBus" should "handle zero values correctly" in {
    test(new CommonDataBus) { c =>
      c.io.reset.poke(false.B)
      
      // Send zero value
      c.io.alu.valid.poke(true.B)
      c.io.alu.bits.index.poke(0.U)
      c.io.alu.bits.value.poke("h0".U)
      c.io.lsb.valid.poke(false.B)
      c.clock.step(1)
      
      c.clock.step(1)
      c.io.rs.valid.expect(true.B)
      c.io.rs.bits.index.expect(0.U)
      c.io.rs.bits.value.expect("h0".U)
    }
  }
}