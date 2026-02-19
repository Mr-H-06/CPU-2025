import chisel3._
import chisel3.util._
import utils._

class IFDecoded extends Bundle {
  val instr = UInt(32.W)
  val pc = UInt(32.W)
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val imm = UInt(32.W)
  val opcode = UInt(7.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
}

class InstructionFetch(initialPC: Int = 0, queueDepth: Int = 4) extends Module {
  val io = IO(new Bundle {
    // control
    val resetPC = Input(UInt(32.W))
    val resetValid = Input(Bool())
    val clear = Input(Bool())

    // memory side (to external instruction memory)
    val mem_iread_address = Output(UInt(32.W))
    val mem_iread_valid = Output(Bool())
    val mem_iout_ready = Input(Bool())
    val mem_iout_valid = Input(Bool())
    val mem_iout_data = Input(UInt(32.W))

    // downstream consumer (ROB/RS/LSB)
    val out = Decoupled(new IFDecoded)
  })

  // 调试逻辑保留（默认注释，避免运行时开销）
  // val dbgCycle = RegInit(0.U(32.W))
  // dbgCycle := dbgCycle + 1.U

  val pcReg = RegInit(initialPC.U(32.W))
  val reqPcReg = RegInit(initialPC.U(32.W))
  // epoch toggles on clears to invalidate stale memory responses
  val epoch = RegInit(false.B)
  val outstandingEpoch = RegInit(false.B)
  val issuing = WireDefault(false.B)
  val outstanding = RegInit(false.B)

  val resetTarget = io.resetPC & (~3.U(32.W))

  when(io.clear || io.resetValid) {
    val target = Mux(io.resetValid, resetTarget, initialPC.U)
    pcReg := target
    reqPcReg := target
    epoch := ~epoch
    outstanding := false.B
  }.elsewhen(issuing) {
    pcReg := pcReg + 4.U
  }

  // request when queue can accept and memory ready
  val q = withReset(reset.asBool || io.clear || io.resetValid)(Module(new Queue(new IFDecoded, queueDepth)))
  q.io.deq <> io.out

  // Block new fetch requests while a pipeline clear/reset is in flight
  val canRequest = q.io.enq.ready && io.mem_iout_ready && !io.clear && !io.resetValid
  issuing := canRequest
  io.mem_iread_address := pcReg
  io.mem_iread_valid := canRequest

  when(issuing) {
    reqPcReg := pcReg
    outstandingEpoch := epoch
    outstanding := true.B
  }

  // val ifDbg = false.B
  // when(ifDbg && io.resetValid) {
  //   printf("[IF] cycle=%d resetValid pcReg=%x resetPC=%x clear=%d\\n", dbgCycle, pcReg, io.resetPC, io.clear)
  // }

  def decodeImm(instr: UInt): UInt = {
    val opcode = instr(6, 0)
    val immI = Cat(Fill(20, instr(31)), instr(31, 20))
    val immS = Cat(Fill(20, instr(31)), instr(31, 25), instr(11, 7))
    // Branch immediate: imm[12|10:5|4:1|11|0] with full sign-extension
    val immB = Cat(Fill(20, instr(31)), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
    val immU = Cat(instr(31, 12), 0.U(12.W))
    // J-type immediate: sign-extend imm[20], layout [20|10:1|11|19:12|0]
    val immJ = Cat(Fill(12, instr(31)), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))
    MuxLookup(opcode, 0.U, Seq(
      "b0010011".U -> immI, // OP-IMM
      "b0000011".U -> immI, // LOAD
      "b1100111".U -> immI, // JALR
      "b1110011".U -> immI, // SYSTEM (ecall/ebreak/csr*)
      "b0100011".U -> immS, // STORE
      "b1100011".U -> immB, // BRANCH
      "b0110111".U -> immU, // LUI
      "b0010111".U -> immU, // AUIPC
      "b1101111".U -> immJ  // JAL
    ))
  }

  // accept instruction return only if it matches current epoch and we have an outstanding request
  // Accept a response in the same cycle we launched a request (issuing)
  // to handle zero-latency instruction memory.
  val respMatches = (outstanding || issuing) && (outstandingEpoch === epoch)
  q.io.enq.valid := io.mem_iout_valid && !io.clear && respMatches
  val opcode = io.mem_iout_data(6, 0)
  q.io.enq.bits.instr := io.mem_iout_data
  q.io.enq.bits.pc := reqPcReg
  val rdRaw = io.mem_iout_data(11, 7)
  q.io.enq.bits.rd := MuxLookup(opcode, rdRaw, Seq(
    "b1100011".U -> 0.U, // BRANCH
    "b0100011".U -> 0.U, // STORE
    "b1110011".U -> 0.U  // SYSTEM
  ))
  q.io.enq.bits.rs1 := io.mem_iout_data(19, 15)
  q.io.enq.bits.rs2 := io.mem_iout_data(24, 20)
  q.io.enq.bits.imm := decodeImm(io.mem_iout_data)
  q.io.enq.bits.opcode := opcode
  q.io.enq.bits.funct3 := io.mem_iout_data(14, 12)
  q.io.enq.bits.funct7 := io.mem_iout_data(31, 25)

  when(io.clear) {
    q.io.deq.ready := true.B // drop queued entries
  }

  when(io.mem_iout_valid && respMatches) {
    outstanding := false.B
  }

  // when(ifDbg && dbgCycle < 48.U) {
  //   printf("[IFDBG] cycle=%d pcReg=%x reqPc=%x resetValid=%d resetPC=%x clear=%d outstanding=%d\\n",
  //     dbgCycle, pcReg, reqPcReg, io.resetValid, resetTarget, io.clear, outstanding)
  // }
  // when(ifDbg && dbgCycle >= 40.U && dbgCycle < 120.U) {
  //   printf("[IFDBG2] cycle=%d pcReg=%x reqPc=%x outstanding=%d clear=%d resetValid=%d epoch=%d outEpoch=%d mem_req=%d mem_rdy=%d mem_rsp=%d q_enq_v=%d q_enq_r=%d q_deq_v=%d q_deq_r=%d\\n",
  //     dbgCycle, pcReg, reqPcReg, outstanding, io.clear, io.resetValid, epoch, outstandingEpoch,
  //     io.mem_iread_valid, io.mem_iout_ready, io.mem_iout_valid,
  //     q.io.enq.valid, q.io.enq.ready, q.io.deq.valid, q.io.deq.ready)
  // }

}
