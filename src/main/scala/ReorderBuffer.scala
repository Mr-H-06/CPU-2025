import chisel3._
import chisel3.util._

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

	class Entry extends Bundle {
		val valid = Bool()
		val ready = Bool()
		val op = UInt(7.W)
		val rd = UInt(5.W)
		val value = UInt(32.W)
		val prediction = UInt(32.W)
	}

	val io = IO(new Bundle {
		// issue
		val issue_valid = Input(Bool())
		val issue_bits = Input(new ROBIssueBits())

		// CDB snoop
		val cdb_valid = Input(Bool())
		val cdb_tag = Input(UInt(idxWidth.W))
		val cdb_value = Input(UInt(32.W))

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

	// broadcast table for RS
	for (i <- 0 until entries) {
		io.values(i).valid := entriesReg(i).valid && entriesReg(i).ready
		io.values(i).value := entriesReg(i).value
	}

	// issue phase
	when(io.issue_valid && io.ready) {
		entriesReg(tail).valid := true.B
		entriesReg(tail).ready := false.B
		entriesReg(tail).op := io.issue_bits.op
		entriesReg(tail).rd := io.issue_bits.rd
		entriesReg(tail).value := 0.U
		entriesReg(tail).prediction := io.issue_bits.prediction
		pcResetTable(tail) := io.issue_bits.pc_reset
		tail := tail + 1.U
		count := count + 1.U

		when(io.issue_bits.op === "b1100011".U || io.issue_bits.op === "b1100111".U) {
			pcResetReg := io.issue_bits.pc_reset & (~3.U(32.W))
		}
	}

	// snoop CDB
	when(io.cdb_valid) {
		entriesReg(io.cdb_tag).value := io.cdb_value
		when(entriesReg(io.cdb_tag).valid) {
			entriesReg(io.cdb_tag).ready := true.B
		}
	}

	// commit logic
	val headEntry = entriesReg(head)
	val isBranch = headEntry.op === "b1100011".U
	val isJalr = headEntry.op === "b1100111".U
	val isStore = headEntry.op === "b0100011".U
	val headReady = count =/= 0.U && headEntry.valid && headEntry.ready

	// defaults for pulse outputs
	writebackValidReg := false.B
	commitStoreReg := false.B
	clearReg := false.B

	when(headReady && (isBranch || isJalr)) {
		pcResetReg := pcResetTable(head) & (~3.U(32.W))
	}

	when(headReady) {
		val mispredict = (isBranch || isJalr) && (headEntry.value =/= headEntry.prediction)
		when(mispredict) {
			clearReg := true.B
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
			count := count - 1.U
			head := head + 1.U
		}
	}
}
