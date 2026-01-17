import chisel3._
import chisel3.util._
import utils._

class RSRobValue extends Bundle {
  val valid = Bool()
  val value = UInt(32.W)
}

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
    val op1_val = UInt(32.W)
    val op1_ready = Bool()
    val op1_tag = UInt(5.W)
    val op2_val = UInt(32.W)
    val op2_ready = Bool()
    val op2_tag = UInt(5.W)
    val dest_tag = UInt(5.W)
  }

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val issue_valid = Input(Bool())
    val issue_bits = Input(new RSIssueBits())
    val cdb_valid = Input(Bool())
    val cdb_tag = Input(UInt(5.W))
    val cdb_value = Input(UInt(32.W))
    val rob_values = Input(Vec(32, new RSRobValue()))
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
    e.op1_val := 0.U
    e.op1_ready := false.B
    e.op1_tag := 0.U
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
  io.issue_ready := count =/= entries.U

  when(io.clear) {
    for(i <- 0 until entries){
      table(i).busy := false.B
      table(i).op1_ready := false.B
      table(i).op2_ready := false.B
    }
  }.otherwise {
    when(io.issue_valid && count =/= entries.U){
      val idx = tailPtr
      val e = table(idx)
      e.busy := true.B
      e.op := io.issue_bits.op
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
      }.otherwise {
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
      count := count + 1.U
    }

    // CDB snoop
    when(io.cdb_valid){
      for(i <- 0 until entries){
        when(table(i).busy){
          when(!table(i).op1_ready && table(i).op1_tag === io.cdb_tag){
            table(i).op1_ready := true.B
            table(i).op1_val := io.cdb_value
          }
          when(!table(i).op2_ready && table(i).op2_tag === io.cdb_tag){
            table(i).op2_ready := true.B
            table(i).op2_val := io.cdb_value
          }
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

  io.exec_valid := headReady && io.fu_ready
  io.exec_bits.op := headEntry.op
  io.exec_bits.op1 := headEntry.op1_val
  io.exec_bits.op2 := headEntry.op2_val
  io.exec_bits.tag := headEntry.dest_tag

  when(io.exec_valid){
    table(headPtr).busy := false.B
    headPtr := (headPtr + 1.U)(log2Ceil(entries)-1,0)
    count := count - 1.U
  }
}
