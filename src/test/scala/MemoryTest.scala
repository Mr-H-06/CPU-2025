import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class MemoryTest extends AnyFlatSpec with ChiselScalatestTester {
  "Memory" should "initialize correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.iread.address.poke(0x00001000.U)
      c.io.iread.valid.poke(true.B)
      c.clock.step(1)
      c.io.iread.valid.poke(false.B)
      c.io.iout.valid.expect(false.B)
      c.clock.step(1)
      c.io.iout.valid.expect(true.B)
      c.io.iout.data.expect(0x00001737.U)
    }
  }

  "Memory" should "load word correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lw)
      c.io.memAccess.bits.address.poke(0x00001000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.io.memValue.data.valid.expect(false.B)
      c.io.memValue.ready.expect(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00001737.U)
      c.io.memValue.data.bits.index.expect(0.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "load byte signed correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lb)
      c.io.memAccess.bits.address.poke(0x00001000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00000037.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "load byte unsigned correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lbu)
      c.io.memAccess.bits.address.poke(0x00001000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00000037.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "load halfword signed correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lh)
      c.io.memAccess.bits.address.poke(0x00001000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00001737.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "load halfword unsigned correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lhu)
      c.io.memAccess.bits.address.poke(0x00001000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00001737.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "load negative byte correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lb)
      c.io.memAccess.bits.address.poke(0x00001004.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect("hFFFFFF83".U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "load unsigned negative byte correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lbu)
      c.io.memAccess.bits.address.poke(0x00001004.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00000083.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "store and load word correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      val testAddr = 0x2000.U
      val testValue = 0x12345678.U
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.sw)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(testValue)
      c.io.memAccess.bits.index.poke(1.U)
      c.io.commit.poke(true.B)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.io.commit.poke(false.B)
      c.clock.step(1)
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lw)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(2.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(testValue)
      c.io.memValue.data.bits.index.expect(2.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "clear instruction fetch" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.iread.address.poke(0x00001000.U)
      c.io.iread.valid.poke(true.B)
      c.clock.step(1)
      c.io.iread.valid.poke(false.B)
      c.io.iout.valid.expect(false.B)
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      c.io.iout.ready.expect(true.B)
      c.io.iout.valid.expect(false.B)
    }
  }

  "Memory" should "clear memory access" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lw)
      c.io.memAccess.bits.address.poke(0x00001000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.io.memValue.ready.expect(false.B)
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.clear.poke(false.B)
      c.io.memValue.ready.expect(true.B)
      c.io.memValue.data.valid.expect(false.B)
    }
  }

  "Memory" should "store and load byte correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      val testAddr = 0x2000.U
      val testValue = 0xAB.U
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.sb)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(testValue)
      c.io.memAccess.bits.index.poke(1.U)
      c.io.commit.poke(true.B)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.io.commit.poke(false.B)
      c.clock.step(1)
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lbu)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(2.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(testValue)
      c.io.memValue.data.bits.index.expect(2.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "store and load halfword correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      val testAddr = 0x2000.U
      val testValue = 0xABCD.U
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.sh)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(testValue)
      c.io.memAccess.bits.index.poke(1.U)
      c.io.commit.poke(true.B)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.io.commit.poke(false.B)
      c.clock.step(1)
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lhu)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(2.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(testValue)
      c.io.memValue.data.bits.index.expect(2.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "handle signed load halfword with negative value" in {
    test(new Memory("docs/memory-example.data", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lh)
      c.io.memAccess.bits.address.poke(0x00001040.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect("hFFFF8067".U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "initialize without file" in {
    test(new Memory("", 1 << 14, 4)) { c =>
      c.io.iread.address.poke(0x00000000.U)
      c.io.iread.valid.poke(true.B)
      c.clock.step(1)
      c.io.iread.valid.poke(false.B)
      c.clock.step(1)
      c.io.iout.valid.expect(true.B)
      c.io.iout.data.expect(0x00000000.U)
    }
  }

  "Memory" should "load word at address zero" in {
    test(new Memory("", 1 << 14, 4)) { c =>
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lw)
      c.io.memAccess.bits.address.poke(0x00000000.U)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(0.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(0x00000000.U)
      c.io.memValue.ready.expect(true.B)
    }
  }

  "Memory" should "store byte at high address" in {
    test(new Memory("", 1 << 14, 4)) { c =>
      val testAddr = 0x00003FFC.U // Near end of memory
      val testValue = 0xFF.U
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.sb)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(testValue)
      c.io.memAccess.bits.index.poke(1.U)
      c.io.commit.poke(true.B)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.io.commit.poke(false.B)
      c.clock.step(1)
      c.io.memValue.ready.expect(true.B)
      c.io.memAccess.valid.poke(true.B)
      c.io.memAccess.bits.op.poke(MemOpEnum.lbu)
      c.io.memAccess.bits.address.poke(testAddr)
      c.io.memAccess.bits.value.poke(0.U)
      c.io.memAccess.bits.index.poke(2.U)
      c.clock.step(1)
      c.io.memAccess.valid.poke(false.B)
      c.clock.step(3)
      c.io.memValue.data.valid.expect(true.B)
      c.io.memValue.data.bits.value.expect(testValue)
      c.io.memValue.data.bits.index.expect(2.U)
      c.io.memValue.ready.expect(true.B)
    }
  }
}