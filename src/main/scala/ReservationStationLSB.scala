import chisel3._
import chisel3.util._
import utils._

// Issue bundle for Load/Store RS
class IssueBitsLSB extends Bundle {
  val op = MemOpEnum()
  val op1_index = UInt(5.W)
  val op2_index = UInt(5.W)
  val op3_value = UInt(32.W)
  val dest_tag = UInt(5.W)
}

// Exec bundle sent to memory
class ExecBitsLSB extends Bundle {
  val op = MemOpEnum()
  val value = UInt(32.W)
  val address = UInt(32.W)
  val index = UInt(5.W)
}

// Internal queue entry
class ReservationStationEntryLSB extends Bundle {
  val op = MemOpEnum()
  val dest_tag = UInt(5.W)
  val op1_tag = UInt(5.W)
  val op1_ready = Bool()
  val op1_value = UInt(32.W)
  val op2_tag = UInt(5.W)
  val op2_ready = Bool()
  val op2_value = UInt(32.W)
  val op3_tag = UInt(5.W)
  val op3_ready = Bool()
  val op3_value = UInt(32.W)
}

class ReservationStationLSB(entries: Int = 4) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val issue_valid = Input(Bool())
    val issue_bits = Input(new IssueBitsLSB())
    val cdb = Input(Valid(new CDBData))
    val rf_entries = Input(Vec(32, new RegisterEntry()))
    val rob_entries = Input(Vec(32, new ROBValue()))
    // Memory accept signal: only fire exec when memory can take a request
    val mem_ready = Input(Bool())
    // Forward ROB commit in the same cycle as issue to avoid missing a just-written operand
    val wb_valid = Input(Bool())
    val wb_index = Input(UInt(5.W))
    val wb_tag = Input(UInt(5.W))
    val wb_value = Input(UInt(32.W))
    val issue_ready = Output(Bool())
    val exec_valid = Output(Bool())
    val exec_bits = Output(new ExecBitsLSB())
  })

  val mem = Reg(Vec(entries, new ReservationStationEntryLSB()))
  val enqPtr = RegInit(0.U(log2Ceil(entries).W))
  val deqPtr = RegInit(0.U(log2Ceil(entries).W))
  val count = RegInit(0.U(log2Ceil(entries + 1).W))
  // Debug cycle counter for short-term tracing
  private val dbgCycle = RegInit(0.U(32.W))
  dbgCycle := dbgCycle + 1.U

  val full = count === entries.U
  val empty = count === 0.U

  io.issue_ready := !full

  val rf_op1 = io.rf_entries(io.issue_bits.op1_index)
  val op1_tag = rf_op1.tag
  val wb_op1_hit = io.wb_valid && rf_op1.tag_valid && rf_op1.tag === io.wb_tag && io.wb_index === io.issue_bits.op1_index
  val op1_ready = !rf_op1.tag_valid || io.rob_entries(op1_tag).valid || wb_op1_hit
  val rf_op2 = io.rf_entries(io.issue_bits.op2_index)
  val op2_tag = rf_op2.tag
  val wb_op2_hit = io.wb_valid && rf_op2.tag_valid && rf_op2.tag === io.wb_tag && io.wb_index === io.issue_bits.op2_index
  val op2_ready = !rf_op2.tag_valid || io.rob_entries(op2_tag).valid || wb_op2_hit
  val isStore = io.issue_bits.op.isOneOf(MemOpEnum.sb, MemOpEnum.sh, MemOpEnum.sw)
  val op3_ready = true.B // immediate

  def readyValue(rf: RegisterEntry, tag: UInt, wbHit: Bool): UInt = {
    Mux(!rf.tag_valid, rf.value,
      Mux(io.rob_entries(tag).valid, io.rob_entries(tag).value,
        Mux(wbHit, io.wb_value, 0.U)))
  }

  // Prepare entry
  val entry = Wire(new ReservationStationEntryLSB())
  entry.op := io.issue_bits.op
  entry.dest_tag := io.issue_bits.dest_tag
  entry.op1_tag := op1_tag
  entry.op1_ready := op1_ready
  entry.op1_value := readyValue(rf_op1, op1_tag, wb_op1_hit)
  entry.op2_tag := op2_tag
  entry.op2_ready := op2_ready
  entry.op2_value := readyValue(rf_op2, op2_tag, wb_op2_hit)
  entry.op3_tag := 0.U
  entry.op3_ready := op3_ready
  entry.op3_value := io.issue_bits.op3_value

  // Handle CDB broadcast in same cycle as issue
  when(io.cdb.valid && io.cdb.bits.index === op1_tag) {
    entry.op1_ready := true.B
    entry.op1_value := io.cdb.bits.value
  }
  when(io.cdb.valid && io.cdb.bits.index === op2_tag) {
    entry.op2_ready := true.B
    entry.op2_value := io.cdb.bits.value
  }
  when(io.cdb.valid && io.cdb.bits.index === entry.op3_tag) {
    entry.op3_ready := true.B
    entry.op3_value := io.cdb.bits.value
  }

  // Issue
  when(io.issue_valid && !full) {
    mem(enqPtr) := entry
    enqPtr := (enqPtr + 1.U) % entries.U
    count := count + 1.U

    when(dbgCycle < 200.U) {
      printf("[LSB] enq cycle=%d ptr=%d op=%d dest=%d op1_ready=%d op2_ready=%d addr=%x val=%x\n",
        dbgCycle, enqPtr, entry.op.asUInt, entry.dest_tag, entry.op1_ready, entry.op2_ready,
        entry.op2_value + entry.op3_value, entry.op1_value)
    }
  }

  // Deq
  val deq_entry = mem(deqPtr)
  val effective_empty = empty && ! (io.issue_valid && !full)
  val effective_deq_entry = Mux(io.issue_valid && !full && empty, entry, deq_entry)
  def isStoreFromOp(op: MemOpEnum.Type) = op.isOneOf(MemOpEnum.sb, MemOpEnum.sh, MemOpEnum.sw)
  val operands_ready_now = effective_deq_entry.op1_ready && effective_deq_entry.op2_ready && (effective_deq_entry.op3_ready || !isStoreFromOp(effective_deq_entry.op))
  val operands_ready_prev = RegNext(operands_ready_now, false.B)
  val exec_from_deq = !effective_empty && operands_ready_now

  // 如果操作数刚刚变为就绪且本拍 CDB 有效，推迟一拍发射
  val exec_gate = exec_from_deq && (operands_ready_prev || !io.cdb.valid) && io.mem_ready

  // Targeted debug to catch mem_ready gating stalls
  when(dbgCycle < 80.U) {
    when(exec_from_deq && !io.mem_ready) {
      printf("[LSBDBG] stall mem_not_ready cycle=%d deq=%d cnt=%d op=%d op1_rdy=%d op2_rdy=%d gate_prev=%d\n",
        dbgCycle, deqPtr, count, effective_deq_entry.op.asUInt, effective_deq_entry.op1_ready, effective_deq_entry.op2_ready, operands_ready_prev)
    }
    when(exec_from_deq && io.mem_ready) {
      printf("[LSBDBG] ready_for_exec cycle=%d deq=%d cnt=%d op=%d gate_prev=%d cdb_v=%d exec_gate=%d\n",
        dbgCycle, deqPtr, count, effective_deq_entry.op.asUInt, operands_ready_prev, io.cdb.valid, exec_gate)
    }
  }

  io.exec_valid := exec_gate
  io.exec_bits.op := effective_deq_entry.op
  io.exec_bits.value := Mux(isStoreFromOp(effective_deq_entry.op), effective_deq_entry.op1_value, 0.U)
  io.exec_bits.address := effective_deq_entry.op2_value + effective_deq_entry.op3_value
  io.exec_bits.index := effective_deq_entry.dest_tag

  when(exec_gate) {
    when(!(io.issue_valid && !full && empty)) {
      deqPtr := (deqPtr + 1.U) % entries.U
      count := count - 1.U
    }

    when(dbgCycle < 200.U) {
      printf("[LSB] exec cycle=%d ptr=%d op=%d idx=%d addr=%x val=%x op1_rdy=%d op2_rdy=%d\n",
        dbgCycle, deqPtr, effective_deq_entry.op.asUInt, effective_deq_entry.dest_tag,
        io.exec_bits.address, io.exec_bits.value,
        effective_deq_entry.op1_ready, effective_deq_entry.op2_ready)
    }
  }

  // CDB updates
  when(io.cdb.valid) {
    for (i <- 0 until entries) {
      when(mem(i).op1_tag === io.cdb.bits.index && !mem(i).op1_ready) {
        mem(i).op1_value := io.cdb.bits.value
        mem(i).op1_ready := true.B
      }
      when(mem(i).op2_tag === io.cdb.bits.index && !mem(i).op2_ready) {
        mem(i).op2_value := io.cdb.bits.value
        mem(i).op2_ready := true.B
      }
      when(mem(i).op3_tag === io.cdb.bits.index && !mem(i).op3_ready) {
        mem(i).op3_value := io.cdb.bits.value
        mem(i).op3_ready := true.B
      }
    }
  }

  // Reset
  when(io.clear) {
    enqPtr := 0.U
    deqPtr := 0.U
    count := 0.U
    for (i <- 0 until entries) {
      mem(i) := 0.U.asTypeOf(new ReservationStationEntryLSB())
    }
  }
}