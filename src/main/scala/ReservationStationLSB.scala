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
  val valid = Bool()
  val op = MemOpEnum()
  val store_committed = Bool()
  val dest_tag = UInt(5.W)
  val op1_index = UInt(5.W)
  val op1_tag = UInt(5.W)
  val op1_ready = Bool()
  val op1_value = UInt(32.W)
  val op2_index = UInt(5.W)
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
    // Store authorization from ROB commit (by tag)
    val commit_store_valid = Input(Bool())
    val commit_store_tag = Input(UInt(5.W))
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
  // Debug cycle counter for short-term tracing (muted by lsbDbg flag)
  private val dbgCycle = RegInit(0.U(32.W))
  dbgCycle := dbgCycle + 1.U
  private val lsbDbg = dbgCycle < 400.U

  val full = count === entries.U
  val empty = count === 0.U

  // Locate a free slot; bias search from enqPtr to preserve approximate FIFO order
  val freeVec = Wire(Vec(entries, Bool()))
  for (i <- 0 until entries) {
    freeVec(i) := !mem(i).valid
  }
  val freeByOffset = Wire(Vec(entries, Bool()))
  for (i <- 0 until entries) {
    val idx = (enqPtr + i.U) % entries.U
    freeByOffset(i) := freeVec(idx)
  }
  val hasFree = freeByOffset.asUInt.orR
  val freeOffset = PriorityEncoder(freeByOffset.asUInt)
  val chosenEnq = (enqPtr + freeOffset) % entries.U
  val issue_fire = io.issue_valid && hasFree

  io.issue_ready := hasFree

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
  entry.valid := true.B
  entry.op := io.issue_bits.op
  entry.store_committed := !isStore
  entry.dest_tag := io.issue_bits.dest_tag
  entry.op1_index := io.issue_bits.op1_index
  entry.op1_tag := op1_tag
  entry.op1_ready := op1_ready
  entry.op1_value := readyValue(rf_op1, op1_tag, wb_op1_hit)
  entry.op2_index := io.issue_bits.op2_index
  entry.op2_tag := op2_tag
  entry.op2_ready := op2_ready
  entry.op2_value := readyValue(rf_op2, op2_tag, wb_op2_hit)
  entry.op3_tag := 0.U
  entry.op3_ready := op3_ready
  entry.op3_value := io.issue_bits.op3_value

  // Handle CDB broadcast in same cycle as issue
  when(io.cdb.valid && !op1_ready && io.cdb.bits.index === op1_tag) {
    entry.op1_ready := true.B
    entry.op1_value := io.cdb.bits.value
  }
  when(io.cdb.valid && !op2_ready && io.cdb.bits.index === op2_tag) {
    entry.op2_ready := true.B
    entry.op2_value := io.cdb.bits.value
  }

  // Issue
  when(issue_fire) {
    mem(chosenEnq) := entry
    enqPtr := (chosenEnq + 1.U) % entries.U
  }

  // Readiness tracking per slot to let ready_now -> ready_prev be remembered even when head is skipped
  def isStoreFromOp(op: MemOpEnum.Type) = op.isOneOf(MemOpEnum.sb, MemOpEnum.sh, MemOpEnum.sw)
  val operands_ready_now = Wire(Vec(entries, Bool()))
  val operands_ready_prev = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val storeVec = Wire(Vec(entries, Bool()))
  for (i <- 0 until entries) {
    storeVec(i) := isStoreFromOp(mem(i).op)
    operands_ready_now(i) := mem(i).valid && mem(i).op1_ready && mem(i).op2_ready && (mem(i).op3_ready || !storeVec(i))
  }
  operands_ready_prev := operands_ready_now

  // Strict FIFO at queue head to preserve memory ordering correctness.
  val headIdx = deqPtr
  val headIsStore = storeVec(headIdx)
  val headReadyNow = operands_ready_now(headIdx)
  val headReadyPrev = operands_ready_prev(headIdx)
  val ready_gate = headReadyPrev || !io.cdb.valid || headIsStore
  when(io.commit_store_valid) {
    // Tags are reused by ROB; only authorize the oldest matching uncommitted store.
    // Authorizing all matches can incorrectly release younger stores that happen to share tag.
    val commitMatchByOffset = Wire(Vec(entries, Bool()))
    for (off <- 0 until entries) {
      val idx = (deqPtr + off.U) % entries.U
      commitMatchByOffset(off) := mem(idx).valid && isStoreFromOp(mem(idx).op) && !mem(idx).store_committed && mem(idx).dest_tag === io.commit_store_tag
    }
    val hasCommitMatch = commitMatchByOffset.asUInt.orR
    val commitOffset = PriorityEncoder(commitMatchByOffset.asUInt)
    val commitIdx = (deqPtr + commitOffset) % entries.U
    when(hasCommitMatch) {
      mem(commitIdx).store_committed := true.B
    }
  }

  val storeAuthorizedNow = mem(headIdx).store_committed ||
    (io.commit_store_valid && io.commit_store_tag === mem(headIdx).dest_tag)
  val canExecHead = mem(headIdx).valid && headReadyNow && ready_gate && (!headIsStore || storeAuthorizedNow)

  val exec_gate = canExecHead && io.mem_ready
  val exec_fire = exec_gate


  io.exec_valid := exec_gate
  io.exec_bits.op := mem(headIdx).op
  io.exec_bits.value := Mux(isStoreFromOp(mem(headIdx).op), mem(headIdx).op1_value, 0.U)
  io.exec_bits.address := mem(headIdx).op2_value + mem(headIdx).op3_value
  io.exec_bits.index := mem(headIdx).dest_tag

  when(exec_fire) {
    mem(headIdx).valid := false.B
    deqPtr := (headIdx + 1.U) % entries.U
  }

  // Keep queue occupancy accurate when issue and exec happen in the same cycle.
  when(!io.clear) {
    when(issue_fire && !exec_fire) {
      count := count + 1.U
    }.elsewhen(!issue_fire && exec_fire) {
      count := Mux(count === 0.U, 0.U, count - 1.U)
    }
  }

  // CDB updates
  when(io.cdb.valid) {
    for (i <- 0 until entries) {
      when(mem(i).valid && mem(i).op1_tag === io.cdb.bits.index && !mem(i).op1_ready) {
        mem(i).op1_value := io.cdb.bits.value
        mem(i).op1_ready := true.B
      }
      when(mem(i).valid && mem(i).op2_tag === io.cdb.bits.index && !mem(i).op2_ready) {
        mem(i).op2_value := io.cdb.bits.value
        mem(i).op2_ready := true.B
      }
      when(mem(i).valid && mem(i).op3_tag === io.cdb.bits.index && !mem(i).op3_ready) {
        mem(i).op3_value := io.cdb.bits.value
        mem(i).op3_ready := true.B
      }
    }
  }

  // Wake waiting operands from ROB writeback path as well (e.g. wb-only producers).
  when(io.wb_valid) {
    for (i <- 0 until entries) {
      when(mem(i).valid && mem(i).op1_tag === io.wb_tag && !mem(i).op1_ready) {
        mem(i).op1_value := io.wb_value
        mem(i).op1_ready := true.B
      }
      when(mem(i).valid && mem(i).op2_tag === io.wb_tag && !mem(i).op2_ready) {
        mem(i).op2_value := io.wb_value
        mem(i).op2_ready := true.B
      }
      when(mem(i).valid && mem(i).op3_tag === io.wb_tag && !mem(i).op3_ready) {
        mem(i).op3_value := io.wb_value
        mem(i).op3_ready := true.B
      }
    }
  }

  // If a producer has already committed and cleared the RF tag, recover operand readiness from RF.
  for (i <- 0 until entries) {
    when(mem(i).valid) {
      when(!mem(i).op1_ready && !io.rf_entries(mem(i).op1_index).tag_valid) {
        mem(i).op1_value := io.rf_entries(mem(i).op1_index).value
        mem(i).op1_ready := true.B
      }
      when(!mem(i).op2_ready && !io.rf_entries(mem(i).op2_index).tag_valid) {
        mem(i).op2_value := io.rf_entries(mem(i).op2_index).value
        mem(i).op2_ready := true.B
      }
    }
  }

  // If clear preserved committed stores as sparse entries, advance deqPtr across holes.
  when(!io.clear && count =/= 0.U && !mem(deqPtr).valid) {
    deqPtr := deqPtr + 1.U
  }

  // Reset
  when(io.clear) {
    val keepCommitted = Wire(Vec(entries, Bool()))
    for (i <- 0 until entries) {
      keepCommitted(i) := mem(i).valid && isStoreFromOp(mem(i).op) && mem(i).store_committed
    }

    val keepByOffset = Wire(Vec(entries, Bool()))
    for (off <- 0 until entries) {
      val idx = (deqPtr + off.U) % entries.U
      keepByOffset(off) := keepCommitted(idx)
    }

    val keptCount = PopCount(keepCommitted)
    val hasKept = keepByOffset.asUInt.orR
    val keptOffset = PriorityEncoder(keepByOffset.asUInt)

    for (i <- 0 until entries) {
      when(!keepCommitted(i)) {
        mem(i) := 0.U.asTypeOf(new ReservationStationEntryLSB())
        mem(i).valid := false.B
      }
    }

    enqPtr := 0.U
    deqPtr := Mux(hasKept, (deqPtr + keptOffset) % entries.U, 0.U)
    count := keptCount
  }
}