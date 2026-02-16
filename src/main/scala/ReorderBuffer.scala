import chisel3._
import chisel3.util._
import utils._

class ROBIssueBits extends Bundle {
	val op = UInt(7.W)
	val rd = UInt(5.W)
	val pc = UInt(32.W)
	val prediction = UInt(32.W)
	val pc_reset = UInt(32.W)
}

class ROBValue extends Bundle {
	val valid = Bool()
	val value = UInt(32.W)
}

class ReorderBuffer(entries: Int = 32) extends Module {
	private val idxWidth = log2Ceil(entries)
	// Debug cycle counter
	private val dbgCycle = RegInit(0.U(32.W))
	dbgCycle := dbgCycle + 1.U
	private val enableRobDebug = dbgCycle < 180.U

	class Entry extends Bundle {
		val valid = Bool()
		val ready = Bool()
		val op = UInt(7.W)
		val rd = UInt(5.W)
		val pc = UInt(32.W)
		val value = UInt(32.W)
		val prediction = UInt(32.W)
	}

	val io = IO(new Bundle {
		// issue
		val issue_valid = Input(Bool())
		val issue_bits = Input(new ROBIssueBits())
		val issue_has_value = Input(Bool())
		val issue_value = Input(UInt(32.W))

		// CDB snoop
		val cdb = Input(Valid(new CDBData))

		// status / bypass
		val ready = Output(Bool())
		val tail = Output(UInt(idxWidth.W))
		val values = Output(Vec(entries, new ROBValue()))

		// commit path to RF
		val writeback_valid = Output(Bool())
		val writeback_index = Output(UInt(5.W))
		val writeback_tag = Output(UInt(idxWidth.W))
		val writeback_value = Output(UInt(32.W))

		// commit notifications
		val commit_store = Output(Bool())
		val clear = Output(Bool())
		val pc_reset = Output(UInt(32.W))

		// debug preview of head entry
		val head_pc = Output(UInt(32.W))
		val head_op = Output(UInt(7.W))
		val head_rd = Output(UInt(5.W))
		val head_valid = Output(Bool())
		val head_ready = Output(Bool())

		// commit trace outputs
		val commit_valid = Output(Bool())
		val commit_pc = Output(UInt(32.W))
		val commit_op = Output(UInt(7.W))
		val commit_rd = Output(UInt(5.W))
		val commit_is_store = Output(Bool())
		val commit_value = Output(UInt(32.W))
		val commit_tag = Output(UInt(idxWidth.W))
	})

	val head = RegInit(0.U(idxWidth.W))
	val tail = RegInit(0.U(idxWidth.W))
	val count = RegInit(0.U((idxWidth + 1).W))
	val entriesReg = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry()))))
	val pcResetTable = RegInit(VecInit(Seq.fill(entries)(0.U(32.W))))

	// one-cycle outputs for commit/clear paths
	val writebackValidReg = RegInit(false.B)
	val writebackIndexReg = RegInit(0.U(5.W))
	val writebackTagReg = RegInit(0.U(idxWidth.W))
	val writebackValueReg = RegInit(0.U(32.W))
	val commitStoreReg = RegInit(false.B)
	val clearReg = RegInit(false.B)
	val pcResetReg = RegInit(0.U(32.W))

	// defaults
	io.ready := count =/= entries.U
	io.tail := tail
	io.writeback_valid := writebackValidReg
	io.writeback_index := writebackIndexReg
	io.writeback_tag := writebackTagReg
	io.writeback_value := writebackValueReg
	io.commit_store := commitStoreReg
	io.clear := clearReg
	io.pc_reset := pcResetReg

	io.head_pc := entriesReg(head).pc
	io.head_op := entriesReg(head).op
	io.head_rd := entriesReg(head).rd
	io.head_valid := entriesReg(head).valid
	io.head_ready := entriesReg(head).valid && entriesReg(head).ready

	val commitValidReg = RegInit(false.B)
	val commitPcReg = RegInit(0.U(32.W))
	val commitOpReg = RegInit(0.U(7.W))
	val commitRdReg = RegInit(0.U(5.W))
	val commitIsStoreReg = RegInit(false.B)
	val commitValueReg = RegInit(0.U(32.W))
	val commitTagReg = RegInit(0.U(idxWidth.W))
	io.commit_valid := commitValidReg
	io.commit_pc := commitPcReg
	io.commit_op := commitOpReg
	io.commit_rd := commitRdReg
	io.commit_is_store := commitIsStoreReg
	io.commit_value := commitValueReg
	io.commit_tag := commitTagReg

	// broadcast table for RS
	for (i <- 0 until entries) {
		io.values(i).valid := entriesReg(i).valid && entriesReg(i).ready
		io.values(i).value := entriesReg(i).value
	}

	// issue phase
	when(io.issue_valid && io.ready) {
		entriesReg(tail).valid := true.B
		// Control ops like JALR must wait for the ALU to produce the target, so only mark non-JALR immediates ready here
		entriesReg(tail).ready := io.issue_has_value && (io.issue_bits.op =/= "b1100111".U || io.issue_bits.pc_reset =/= 0.U)
		entriesReg(tail).op := io.issue_bits.op
		entriesReg(tail).rd := io.issue_bits.rd
		entriesReg(tail).pc := io.issue_bits.pc
		entriesReg(tail).value := Mux(io.issue_has_value, io.issue_value, 0.U)
		entriesReg(tail).prediction := io.issue_bits.prediction
		pcResetTable(tail) := io.issue_bits.pc_reset
		tail := tail + 1.U
		count := count + 1.U

		// Focused debug around the __umodsi3 window to ensure t0 (x5) is captured correctly
		when(enableRobDebug && io.issue_bits.pc >= "h00001130".U && io.issue_bits.pc <= "h00001170".U) {
		}

		when(io.issue_bits.op === "b1100011".U || io.issue_bits.op === "b1100111".U) {
			pcResetReg := io.issue_bits.pc_reset & (~3.U(32.W))
		}
	}

	// snoop CDB
	when(io.cdb.valid) {
		val idx = io.cdb.bits.index
		when(entriesReg(idx).valid) {
			when(entriesReg(idx).op === "b1100111".U) { // JALR
				entriesReg(idx).ready := true.B
				pcResetTable(idx) := io.cdb.bits.value
			}.otherwise {
				entriesReg(idx).value := io.cdb.bits.value
				entriesReg(idx).ready := true.B
			}
		}
	}

	// commit logic
	val headEntry = entriesReg(head)
	val isBranch = headEntry.op === "b1100011".U
	val isJalr = headEntry.op === "b1100111".U
	val isJal = headEntry.op === "b1101111".U
	val isCtrl = isBranch || isJalr || isJal
	val isStore = headEntry.op === "b0100011".U
	val headReady = headEntry.valid && headEntry.ready
	when(enableRobDebug && headReady && count === 0.U) {
	}

	when(enableRobDebug && headReady) {
	}


	// Targeted debug around the array_test2 hang window
	val robDbg = enableRobDebug && dbgCycle >= 60.U && dbgCycle < 150.U
	when(robDbg) {
	}

	// defaults for pulse outputs
	writebackValidReg := false.B
	commitStoreReg := false.B
	clearReg := false.B
	commitValidReg := false.B
	commitIsStoreReg := false.B
	commitTagReg := 0.U

	when(headReady && isCtrl) {
		pcResetReg := pcResetTable(head) & (~3.U(32.W))
	}

	when(headReady) {
		commitValidReg := true.B
		commitPcReg := headEntry.pc
		commitOpReg := headEntry.op
		commitRdReg := headEntry.rd
		commitValueReg := headEntry.value
		commitIsStoreReg := isStore
		commitTagReg := head

		val branchMispredict = isBranch && (headEntry.value =/= headEntry.prediction)
		val jumpRedirect = !branchMispredict && (isJal || isJalr)

		when(branchMispredict) {
			clearReg := true.B
			when(headEntry.rd =/= 0.U && !isStore) {
				writebackValidReg := true.B
				writebackIndexReg := headEntry.rd
				writebackTagReg := head
				writebackValueReg := headEntry.value
			}
			for (i <- 0 until entries) {
				entriesReg(i).valid := false.B
				entriesReg(i).ready := false.B
			}
			head := 0.U
			tail := 0.U
			count := 0.U
		}.elsewhen(jumpRedirect) {
			// Redirect to jump target; retire the head and flush everything else (treat like a control mispredict)
			clearReg := true.B
			when(headEntry.rd =/= 0.U && !isStore) {
				writebackValidReg := true.B
				writebackIndexReg := headEntry.rd
				writebackTagReg := head
				writebackValueReg := headEntry.value
			}
			for (i <- 0 until entries) {
				entriesReg(i).valid := false.B
				entriesReg(i).ready := false.B
			}
			head := 0.U
			tail := 0.U
			count := 0.U
		}.otherwise {
			when(headEntry.rd =/= 0.U && !isStore) {
				writebackValidReg := true.B
				writebackIndexReg := headEntry.rd
				writebackTagReg := head
				writebackValueReg := headEntry.value
			}
			when(isStore) {
				commitStoreReg := true.B
			}
			entriesReg(head).valid := false.B
			entriesReg(head).ready := false.B
			count := Mux(count === 0.U, 0.U, count - 1.U)
			head := head + 1.U
		}
	}
}
