import chisel3._
import chisel3.util._
import utils._

class CommonDataBus extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val lsb = Flipped(ValidIO(new CDBData))
    val alu = Flipped(ValidIO(new CDBData))
    val rs = ValidIO(new CDBData)
    val rf = ValidIO(new CDBData)
    val rob = ValidIO(new CDBData)
  })
  
  // Small FIFO that can accept up to two inputs per cycle and
  // outputs at most one per cycle (broadcast bus).
  val depth = 32
  val idxWidth = log2Ceil(depth)
  val fifo = withReset(io.clear)(RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new CDBData)))))
  val head = withReset(io.clear)(RegInit(0.U(idxWidth.W)))
  val tail = withReset(io.clear)(RegInit(0.U(idxWidth.W)))
  val count = withReset(io.clear)(RegInit(0.U(log2Ceil(depth + 1).W)))

  val deqFire = count =/= 0.U
  val headAfterDeq = WireDefault(head)
  val countAfterDeq = WireDefault(count)
  when(deqFire) {
    headAfterDeq := head + 1.U
    countAfterDeq := count - 1.U
  }

  val slots = (depth.U - countAfterDeq)
  val enq0 = io.lsb.valid && slots > 0.U
  val enq1 = io.alu.valid && slots > Mux(enq0, 1.U, 0.U)

  val tail0 = tail
  val tail1 = tail + Mux(enq0, 1.U, 0.U)
  when(enq0) { fifo(tail0) := io.lsb.bits }
  when(enq1) { fifo(tail1) := io.alu.bits }

  val enqCount = enq0.asUInt +& enq1.asUInt
  val tailAfterEnq = tail + enqCount
  val countAfterEnq = countAfterDeq + enqCount

  head := headAfterDeq
  tail := tailAfterEnq
  count := countAfterEnq

  io.rs.valid := deqFire
  io.rs.bits := fifo(head)
  io.rf.valid := deqFire
  io.rf.bits := fifo(head)
  io.rob.valid := deqFire
  io.rob.bits := fifo(head)
}