import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class ReorderBufferTest extends AnyFreeSpec with ChiselScalatestTester {
	"basic issue and commit" in {
		test(new ReorderBuffer()) { c =>
			// 单条算术：issue -> CDB 写回 -> 寄存器写回脉冲
			c.io.issue_valid.poke(true.B)
			c.io.issue_bits.op.poke("b0110011".U) // R-type placeholder
			c.io.issue_bits.rd.poke(3.U)
			c.io.issue_bits.pc.poke(0.U)
			c.io.issue_bits.prediction.poke(0.U)
			c.io.issue_bits.pc_reset.poke(0.U)
			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			// result arrives
			c.io.issue_valid.poke(false.B)
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke("hDEADBEEF".U)
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.writeback_valid.expect(true.B)
			c.io.writeback_index.expect(3.U)
			c.io.writeback_tag.expect(0.U)
			c.io.writeback_value.expect("hDEADBEEF".U)
			c.io.commit_store.expect(false.B)
			c.io.clear.expect(false.B)
		}
	}

	"commits two entries in order" in {
		test(new ReorderBuffer()) { c =>
			// 两条算术按序提交，检查各自 rd/tag/value
			c.io.issue_valid.poke(true.B)
			c.io.issue_bits.op.poke("b0110011".U)
			c.io.issue_bits.rd.poke(5.U)
			c.io.issue_bits.pc.poke(0.U)
			c.io.issue_bits.prediction.poke(0.U)
			c.io.issue_bits.pc_reset.poke(0.U)
			c.clock.step()

			c.io.issue_bits.rd.poke(6.U)
			c.clock.step()

			// write back first entry
			c.io.issue_valid.poke(false.B)
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke(10.U)
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.writeback_valid.expect(true.B)
			c.io.writeback_index.expect(5.U)
			c.io.writeback_tag.expect(0.U)
			c.io.writeback_value.expect(10.U)

			// write back second entry
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(1.U)
			c.io.cdb_value.poke(20.U)
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.writeback_valid.expect(true.B)
			c.io.writeback_index.expect(6.U)
			c.io.writeback_tag.expect(1.U)
			c.io.writeback_value.expect(20.U)
		}
	}

	"values array reflects ready entries" in {
		test(new ReorderBuffer()) { c =>
			// CDB 写回后 values 数组正确暴露 valid/value
			c.io.issue_valid.poke(true.B)
			c.io.issue_bits.op.poke("b0110011".U)
			c.io.issue_bits.rd.poke(7.U)
			c.io.issue_bits.pc.poke(0.U)
			c.io.issue_bits.prediction.poke(0.U)
			c.io.issue_bits.pc_reset.poke(0.U)
			c.clock.step()

			c.io.issue_valid.poke(false.B)
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke(0x55.U)
			c.clock.step()

			c.io.values(0).valid.expect(true.B)
			c.io.values(0).value.expect(0x55.U)
			c.io.values(1).valid.expect(false.B)
		}
	}

	"store commits without writeback" in {
		test(new ReorderBuffer()) { c =>
			// STORE：只产生 commit_store，不写寄存器
			c.io.issue_valid.poke(true.B)
			c.io.issue_bits.op.poke("b0100011".U) // STORE
			c.io.issue_bits.rd.poke(0.U)
			c.io.issue_bits.pc.poke(0.U)
			c.io.issue_bits.prediction.poke(0.U)
			c.io.issue_bits.pc_reset.poke(0.U)
			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.issue_valid.poke(false.B)
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke(15.U)
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.commit_store.expect(true.B)
			c.io.writeback_valid.expect(false.B)
			c.io.clear.expect(false.B)
		}
	}

	"branch mispredict triggers clear" in {
		test(new ReorderBuffer()) { c =>
			// 分支误判：触发 clear 与对齐后的 pc_reset
			c.io.issue_valid.poke(true.B)
			c.io.issue_bits.op.poke("b1100011".U) // BRANCH
			c.io.issue_bits.rd.poke(0.U)
			c.io.issue_bits.pc.poke(0.U)
			c.io.issue_bits.prediction.poke(1.U)
			c.io.issue_bits.pc_reset.poke(12.U)
			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.issue_valid.poke(false.B)
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke(0.U) // different from prediction
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.clear.expect(true.B)
			c.io.pc_reset.expect(12.U)
			c.io.commit_store.expect(false.B)
			c.io.writeback_valid.expect(false.B)
			c.io.ready.expect(true.B)
		}
	}

	"mispredict flushes younger entries" in {
		test(new ReorderBuffer()) { c =>
			// 误判时清空 younger entries，保持 ready
			c.io.issue_valid.poke(true.B)
			c.io.issue_bits.op.poke("b1100011".U)
			c.io.issue_bits.rd.poke(0.U)
			c.io.issue_bits.pc.poke(0.U)
			c.io.issue_bits.prediction.poke(1.U)
			c.io.issue_bits.pc_reset.poke(28.U)
			c.clock.step()

			c.io.issue_bits.op.poke("b0110011".U)
			c.io.issue_bits.rd.poke(8.U)
			c.io.issue_bits.prediction.poke(0.U)
			c.io.issue_bits.pc_reset.poke(0.U)
			c.clock.step()

			c.io.issue_valid.poke(false.B)
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke(0.U) // mispredict
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.clear.expect(true.B)
			c.io.pc_reset.expect(28.U)
			c.io.writeback_valid.expect(false.B)
			c.io.commit_store.expect(false.B)
			c.io.ready.expect(true.B)
			// younger entry should be flushed
			c.io.values(1).valid.expect(false.B)
		}
	}

	"ready deasserts when full" in {
		test(new ReorderBuffer(entries = 4)) { c =>
			// 小深度填满后 ready 低，提交一项后恢复
			for (_ <- 0 until 4) {
				c.io.issue_valid.poke(true.B)
				c.io.issue_bits.op.poke("b0110011".U)
				c.io.issue_bits.rd.poke(1.U)
				c.io.issue_bits.pc.poke(0.U)
				c.io.issue_bits.prediction.poke(0.U)
				c.io.issue_bits.pc_reset.poke(0.U)
				c.clock.step()
			}
			c.io.issue_valid.poke(false.B)
			c.io.ready.expect(false.B)

			// free one slot
			c.io.cdb_valid.poke(true.B)
			c.io.cdb_tag.poke(0.U)
			c.io.cdb_value.poke(1.U)
			c.clock.step()

			c.io.cdb_valid.poke(false.B)
			c.clock.step()

			c.io.ready.expect(true.B)
		}
	}
}
