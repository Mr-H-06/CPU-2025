import chisel3._
import chisel3.util._
import utils._

class RSIssueBits extends Bundle {
  val op = AluOpEnum()
  val op1_index = UInt(5.W)
  val op2_index = UInt(5.W)
  val op2_value = UInt(32.W)
  val op2_type = Bool() // true: immediate, false: register
  val dest_tag = UInt(5.W)
}

class ReservationStations(entries: Int = 4) extends Module {
  require(entries > 0)

  class RSEntry extends Bundle {
    val busy = Bool()
    val op = AluOpEnum()
    val op1_idx = UInt(5.W)
    val op1_val = UInt(32.W)
    val op1_ready = Bool()
    val op1_tag = UInt(5.W)
    val op2_idx = UInt(5.W)
    val op2_val = UInt(32.W)
    val op2_ready = Bool()
    val op2_tag = UInt(5.W)
    val dest_tag = UInt(5.W)
  }

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val issue_valid = Input(Bool())
    val issue_bits = Input(new RSIssueBits())
    val cdb = Input(Valid(new CDBData))
    val rob_values = Input(Vec(32, new ROBValue()))
    val rf_regs = Input(Vec(32, RegisterEntry()))
    val fu_ready = Input(Bool())
    val issue_ready = Output(Bool())
    val exec_valid = Output(Bool())
    val exec_bits = Output(new AluExecBits())
  })

  val table = RegInit(VecInit(Seq.fill(entries){
    val e = Wire(new RSEntry())
    e.busy := false.B
    e.op := AluOpEnum.ADD
    e.op1_idx := 0.U
    e.op1_val := 0.U
    e.op1_ready := false.B
    e.op1_tag := 0.U
    e.op2_idx := 0.U
    e.op2_val := 0.U
    e.op2_ready := false.B
    e.op2_tag := 0.U
    e.dest_tag := 0.U
    e
  }))

  val freeIdxOH = PriorityEncoderOH(table.map(!_.busy))
  val hasFree = table.map(!_.busy).reduce(_||_)
  val headPtr = RegInit(0.U(log2Ceil(entries).W))
  val tailPtr = RegInit(0.U(log2Ceil(entries).W))
  val count = RegInit(0.U(log2Ceil(entries + 1).W))
  val issueFire = io.issue_valid && count =/= entries.U
  io.issue_ready := count =/= entries.U

  when(io.clear) {
    // Fully reset queue state on clear to avoid stale pointers/counts after a flush
    headPtr := 0.U
    tailPtr := 0.U
    count := 0.U
    for(i <- 0 until entries){
      table(i) := 0.U.asTypeOf(new RSEntry())
    }
  }.otherwise {
    when(issueFire){
      val idx = tailPtr
      val e = table(idx)
      e.busy := true.B
      e.op := io.issue_bits.op
      e.op1_idx := io.issue_bits.op1_index
      // op1 resolve
      val rf1 = io.rf_regs(io.issue_bits.op1_index)
      when(io.rob_values(rf1.tag).valid && rf1.tag_valid){
        e.op1_ready := true.B
        e.op1_val := io.rob_values(rf1.tag).value
      }.otherwise {
        e.op1_ready := !rf1.tag_valid
        e.op1_val := rf1.value
        e.op1_tag := rf1.tag
      }
      // op2 resolve
      when(io.issue_bits.op2_type){
        e.op2_ready := true.B
        e.op2_val := io.issue_bits.op2_value
        e.op2_idx := io.issue_bits.op2_index
      }.otherwise {
        e.op2_idx := io.issue_bits.op2_index
        val rf2 = io.rf_regs(io.issue_bits.op2_index)
        when(io.rob_values(rf2.tag).valid && rf2.tag_valid){
          e.op2_ready := true.B
          e.op2_val := io.rob_values(rf2.tag).value
        }.otherwise {
          e.op2_ready := !rf2.tag_valid
          e.op2_val := rf2.value
          e.op2_tag := rf2.tag
        }
      }
      e.dest_tag := io.issue_bits.dest_tag
      tailPtr := (tailPtr + 1.U)(log2Ceil(entries)-1,0)
    }

    // CDB snoop
    when(io.cdb.valid){
      for(i <- 0 until entries){
        when(table(i).busy){
          when(!table(i).op1_ready && table(i).op1_tag === io.cdb.bits.index){
            table(i).op1_ready := true.B
            table(i).op1_val := io.cdb.bits.value
          }
          when(!table(i).op2_ready && table(i).op2_tag === io.cdb.bits.index){
            table(i).op2_ready := true.B
            table(i).op2_val := io.cdb.bits.value
          }
        }
      }
    }

    // Also poll the ROB value table so operands that completed before this RS entry issued are not lost
    for(i <- 0 until entries){
      when(table(i).busy){
        when(!table(i).op1_ready && io.rob_values(table(i).op1_tag).valid){
          table(i).op1_ready := true.B
          table(i).op1_val := io.rob_values(table(i).op1_tag).value
        }
        when(!table(i).op2_ready && io.rob_values(table(i).op2_tag).valid){
          table(i).op2_ready := true.B
          table(i).op2_val := io.rob_values(table(i).op2_tag).value
        }
        // If the producer has already committed, fall back to the register file view
        when(!table(i).op1_ready && !io.rf_regs(table(i).op1_idx).tag_valid){
          table(i).op1_ready := true.B
          table(i).op1_val := io.rf_regs(table(i).op1_idx).value
        }
        when(!table(i).op2_ready && !io.rf_regs(table(i).op2_idx).tag_valid){
          table(i).op2_ready := true.B
          table(i).op2_val := io.rf_regs(table(i).op2_idx).value
        }
      }
    }
  }

  // pick oldest ready entry (lowest index priority)
  val readyVec = table.map(e => e.busy && e.op1_ready && e.op2_ready)
  val readyVecDyn = VecInit(readyVec)
  val hasReady = readyVec.reduce(_||_)

  val headEntry = table(headPtr)
  val headReady = headEntry.busy && headEntry.op1_ready && headEntry.op2_ready

  io.exec_valid := headReady && io.fu_ready && !io.clear
  io.exec_bits.op := headEntry.op
  io.exec_bits.op1 := headEntry.op1_val
  io.exec_bits.op2 := headEntry.op2_val
  io.exec_bits.tag := headEntry.dest_tag

  val execFire = io.exec_valid

  when(execFire){
    table(headPtr).busy := false.B
    headPtr := (headPtr + 1.U)(log2Ceil(entries)-1,0)
  }

  // Keep count accurate when issue and exec happen in the same cycle
  when(!io.clear) {
    when(issueFire && !execFire){
      count := count + 1.U
    }.elsewhen(!issueFire && execFire){
      count := count - 1.U
    }
  }

  // Targeted debug around the post-redirect window to see issue/exec handshakes
  val dbgCycle = RegInit(0.U(32.W))
  dbgCycle := dbgCycle + 1.U
  when(dbgCycle >= 90.U && dbgCycle < 160.U) {
    printf("[RS] state cycle=%d head=%d tail=%d count=%d ready=%d fu_ready=%d head_rdy=%d\n",
      dbgCycle, headPtr, tailPtr, count, io.issue_ready, io.fu_ready, headReady)
    when(io.issue_valid) {
      printf("[RS] issue cycle=%d head=%d tail=%d count=%d op=%d op1_rdy=%d op2_rdy=%d dest=%d\n",
        dbgCycle, headPtr, tailPtr, count, io.issue_bits.op.asUInt, table(tailPtr).op1_ready, table(tailPtr).op2_ready, io.issue_bits.dest_tag)
    }
    when(io.exec_valid) {
      printf("[RS] exec cycle=%d head=%d count=%d op=%d tag=%d op1=%x op2=%x\n",
        dbgCycle, headPtr, count, headEntry.op.asUInt, headEntry.dest_tag, headEntry.op1_val, headEntry.op2_val)
    }
    when(headEntry.busy && !headReady) {
      val op1RobReady = io.rob_values(headEntry.op1_tag).valid
      val op2RobReady = io.rob_values(headEntry.op2_tag).valid
      printf("[RS] wait cycle=%d head=%d op=%d dest=%d op1_rdy=%d tag=%d rob=%d op2_rdy=%d tag=%d rob=%d\n",
        dbgCycle, headPtr, headEntry.op.asUInt, headEntry.dest_tag,
        headEntry.op1_ready, headEntry.op1_tag, op1RobReady,
        headEntry.op2_ready, headEntry.op2_tag, op2RobReady)
    }
  }
}
