import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class AllDataDebugSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Core over all data programs"

  private val programs = Seq(
    "src/test/resources/array_test1.data",
    "src/test/resources/array_test2.data",
    "src/test/resources/basicopt1.data",
    "src/test/resources/bulgarian.data",
    "src/test/resources/expr.data",
    "src/test/resources/halt.data",
    "src/test/resources/hanoi.data",
    "src/test/resources/lvalue2.data",
    "src/test/resources/magic.data",
    "src/test/resources/manyarguments.data",
    "src/test/resources/multiarray.data",
    "src/test/resources/naive.data",
    "src/test/resources/qsort.data",
    "src/test/resources/simple.data",
    "src/test/resources/test.data",
    "src/test/resources/test2.data"
  )

  private val limits = Map(
    "src/test/resources/array_test2.data" -> (20000, 40),
    "src/test/resources/basicopt1.data" -> (20000, 40)
  )

  private def runProgram(path: String, defaultMax: Int = 30000, defaultStagnant: Int = 200): Unit = {
    val (maxCycles, maxStagnant) = limits.getOrElse(path, (defaultMax, defaultStagnant))
    test(new Core(initFile = path, memSize = 131072, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      var halted = false
      var lastPc: BigInt = -1
      var stagnant = 0

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val haltedNow = c.io.halted.peek().litToBoolean
        if (pc == lastPc) stagnant += 1 else stagnant = 0
        lastPc = pc

        if (stagnant >= maxStagnant) {
          fail(s"[$path] PC stuck at 0x${pc.toLong}%08x for $stagnant cycles")
        }

        c.clock.step()
        cycles += 1
        halted = haltedNow
      }

      val lastPcHex = if (lastPc >= 0) f"0x${lastPc.toLong}%08x" else lastPc.toString
      assert(halted, s"[$path] Core did not halt within $maxCycles cycles; last PC=$lastPcHex")
    }
  }

  it should "halt cleanly on every data program" in {
    programs.foreach { path =>
      info(s"Running $path")
      runProgram(path)
    }
  }
}
