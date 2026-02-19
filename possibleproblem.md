你的CPU能通过简单测试点，说明基本功能（指令执行、数据 forwarding 等）是正常的。但复杂测试点（数十万周期）失败，通常意味着存在**状态机的不一致性**、**资源泄漏**或**边界条件处理错误**，这些问题只有在长时间、多指令交错的情况下才会暴露。

以下是我根据你的代码逐模块分析出的**高风险问题**，并给出相应的**修复建议**。

---

## 1. LSB 清除逻辑：最危险的“时间炸弹”
**文件：`ReservationStationLSB.scala`**  
在 `when(io.clear)` 块中，你试图保留已提交的 store，同时将 `enqPtr` 强制置 0，而 `deqPtr` 可能指向某个保留的 store。

```scala
when(io.clear) {
  val keepCommitted = ...
  enqPtr := 0.U
  deqPtr := Mux(hasKept, (deqPtr + keptOffset) % entries.U, 0.U)
  count := keptCount
}
```

### 问题：
- `enqPtr` 突然跳回 0，但队列中可能还有保留的 store 分散在各处（`keepCommitted` 为 true 的项）。新发射的指令会从索引 0 开始存放，可能覆盖掉尚未执行的保留 store（如果它们的索引恰好是 0），或者破坏 FIFO 顺序。
- `deqPtr` 的计算依赖于 `keptOffset`，但 `keptOffset` 是相对于清除前的 `deqPtr` 的偏移，而清除后队列的物理布局被压缩了吗？实际上你没有移动保留的 store，只是保留了它们原来的位置，然后让 `enqPtr` 指向 0。这会导致队列出现空洞，后续的 FIFO 行为变得不可预测。

### 后果：
在连续多次清空（如多次分支误预测）后，LSB 内部的状态可能彻底混乱，导致 store 永远无法被授权、load 拿到错误的值，最终程序挂起或计算出错。

### 修复建议：
- **简化清除逻辑**：分支误预测时，**直接清空整个 LSB**，不要保留任何 store。因为清空意味着推测执行的指令全部作废，即使 store 已提交也不应该留在队列中（它们已经在内存中生效了，不需要再执行）。你的 `commit_store` 信号已经保证了 store 在提交时执行，因此 LSB 中的 store 条目在提交后可以安全地清除。
- 如果你确实需要保留某些 store（例如，为了在清空后继续使用），应该使用**真实的队列压缩**（把所有有效项移到队列头部），而不是保持位置不变。

---

## 2. LSB 执行使能条件：可能造成不必要的延迟
**文件：`ReservationStationLSB.scala`**
```scala
val headReadyPrev = operands_ready_prev(headIdx)
val ready_gate = headReadyPrev || !io.cdb.valid || headIsStore
val canExecHead = mem(headIdx).valid && headReadyNow && ready_gate ...
```

### 问题：
- `ready_gate` 要求 `headReadyPrev` 为 true，除非 CDB 当前无效（`!io.cdb.valid`）或者是 store。这意味着当一个 load 的源操作数刚好在本周期通过 CDB 广播变得就绪（`headReadyNow` 为 true 但 `headReadyPrev` 为 false）时，**这个 load 要等到下一个周期才能执行**。
- 在复杂的数据依赖链中，这种一周期延迟可能造成死锁：如果后续指令依赖这个 load 的结果，而 load 本身又因为 `ready_gate` 被卡住，就可能形成循环等待。

### 后果：
虽然这种延迟通常只降低性能，但在某些资源紧张的情况下（如 ROB 满、RS 满），可能会形成协议死锁——所有指令都在等待某个条件，而这个条件永远不会满足。

### 修复建议：
- 去掉 `ready_gate` 中的 `headReadyPrev` 依赖，直接使用 `headReadyNow`。即：
  ```scala
  val canExecHead = mem(headIdx).valid && headReadyNow && (!headIsStore || storeAuthorizedNow)
  ```
- 如果你担心 CDB 广播导致同一周期内多个条目同时就绪，可以通过更细粒度的仲裁来解决，而不是靠 `ready_prev` 来延迟。

---

## 3. Memory 模块的 store 缓冲：可能丢失 store
**文件：`Memory.scala`**

### 问题分析：
- 当内存正在处理一个 load 时（`memInput.valid` 为 true），后续到达的 store 会被存入 `storePending`，但**只有一个槽位**。
- 如果连续出现多个 store，第二个及之后的 store 会被无条件丢弃（因为 `storePending.valid` 已经被占用了，不会再次存入）。
- 这些被丢弃的 store 将永远不会被执行，导致内存状态错误。

### 后果：
在大量 store 密集的代码段（如循环内的数组赋值）中，许多 store 会丢失，导致最终结果完全错误。

### 修复建议：
- 要么在 LSB 中添加背压机制，让 LSB 在内存忙时不再发射新的 store（你已经有了 `mem_ready` 信号，但注意 `mem_ready` 只有在内存完全空闲时才为 true，当有 load 在飞时，`mem_ready` 为 false，LSB 不会发射新的 store，因此 store 实际上不会堆积在 LSB 中——这是好的）。
- 但你的 LSB 可能会在 `mem_ready` 为 false 时，仍然保持 store 在队列中（`exec_valid` 被阻塞），这没问题。问题在于：如果 store 已经发射到内存，而内存因为正在处理 load 无法立即执行，你将这个 store 存入 `storePending`，同时内存还在处理 load。此时如果又有新的 store 从 LSB 发射过来（`io.memAccess.valid`），由于 `memInput.valid` 仍然为 true，且 `storePending.valid` 已经被占用，新 store 就被忽略了。

**关键点**：当 `storePending` 被占用时，你应该让 LSB 知道内存无法接收新的请求。但目前 `mem_ready` 只检查 `!memInput.valid && !storePending.valid`，如果 `storePending` 被占用，`mem_ready` 就为 false，LSB 就不会发射新请求。所以理论上不会出现上面描述的“连续多个 store 被丢弃”的情况。但是，如果第一个 store 进入 `storePending` 后，`mem_ready` 变为 false，LSB 就不会再发射，所以不会有多余的 store 过来。因此这个问题可能并不严重，但需要确认在 `mem_ready` 计算中确实考虑了 `storePending`。你已经做到了：
  ```scala
  io.memValue.ready := !memInput.valid && !storePending.valid
  ```
  但请注意，`io.memValue.ready` 是输出给 LSB 的 `mem_ready` 吗？在 `Core.scala` 中：
  ```scala
  lsb.io.mem_ready := mem.io.memValue.ready
  ```
  所以正确。那么当 `storePending` 被占用时，`mem_ready` 为 false，LSB 不会发射新请求，也就不会有新 store 过来。所以原代码中的缓冲槽可能只用于一个待处理的 store，且由于背压机制，不会有多余的 store 到来。因此这部分暂时安全。

但仍有隐患：如果 `mem_ready` 的更新有延迟（组合逻辑？），可能在一个周期内同时收到两个 store？不可能，因为 LSB 每周期最多发射一个执行请求。

---

## 4. CDB 广播 store 结果可能错误唤醒指令
**文件：`CommonDataBus.scala`** 和 **`ReservationStations.scala` / `ReservationStationLSB.scala`**

- store 指令在内存执行后，通过 `cdb.io.lsb.valid` 广播一个 `CDBData`，其中 `value` 被设为 0（在 `Memory.scala` 中 `memOutput.value := 0.U`）。
- 这个广播会进入 RS 和 LSB 的 CDB 监听逻辑，唤醒任何等待该 tag 的指令。
- 但 store 的结果（0）对任何等待它的指令都是无意义的，不应该被用于唤醒。如果某个算术指令错误地依赖了 store 的 tag（正常不应该，因为 store 不写寄存器），那么它会被错误地唤醒，得到值 0，导致计算错误。

### 后果：
在复杂程序中，可能会出现某个指令的 tag 意外与 store 的 tag 相同（因为 tag 是循环使用的），导致它被提前唤醒且使用错误的值。

### 修复建议：
- 在 CDB 广播时，为 store 结果打上一个特殊的标记（例如 `index` 超出 ROB 范围），让接收方忽略它。或者在 RS/LSB 的监听逻辑中，根据指令类型过滤掉 store 的结果。
- 最简单的做法：不让 store 进入 CDB。因为 store 的结果不需要广播给任何单元（它只影响内存，不写寄存器）。你可以修改 `Memory.scala`，只在 load 完成时才设置 `mValidReg`，store 完成后不产生任何 valid 输出。目前你的代码中 store 执行后也会置 `mValidReg` 并广播 0，这是不必要的。

---

## 5. ROB 的 commit 与写回交互：可能丢失写回
**文件：`ReorderBuffer.scala`**

### 问题：
- 在分支误预测时，你设置了 `clearReg := true.B`，并且清空了整个 ROB。但在清空前，你尝试将当前提交指令的结果写回寄存器：
  ```scala
  when(headEntry.rd =/= 0.U && !isStore) {
    writebackValidReg := true.B
    writebackIndexReg := headEntry.rd
    writebackTagReg := head
    writebackValueReg := headEntry.value
  }
  ```
- 但是，`writebackValidReg` 只是一个寄存器，它会在下一个周期被复位为 false。而清空操作是立即发生的（`head := 0.U` 等）。这意味着在清空的同一周期，写回信号可能会被发送到寄存器文件，但随后寄存器文件也会收到 `io.clear` 信号（在 `RegisterFile.scala` 中，`when(io.clear)` 会清掉所有 tag）。这可能导致写回被清除，或者顺序混乱。

### 后果：
分支误预测时，正确路径上的指令结果可能无法正确写回寄存器文件，导致架构状态错误。

### 修复建议：
- 确保清空操作不会影响刚刚发生的写回。通常的做法是：在清空 ROB 的同一周期，仍然允许写回到寄存器文件，但寄存器文件的清除信号应该**晚一拍**生效，或者写回操作具有比清除更高的优先级。在你的 `RegisterFile` 中，写回和清除是同时发生的（`when(io.clear)` 和 `when(io.writeback_valid)` 是并行条件），且写回发生在 `io.clear` 的 same-cycle？实际上你的 `RegisterFile` 中写回和清除是独立的 `when` 块，它们在同一周期都会执行。如果 `io.clear` 为 true，那么 `when(io.clear)` 块会执行，将所有 tag_valid 清 false；同时 `when(io.writeback_valid)` 块也会执行，更新值并清除 tag。这可能导致竞争：如果写回的是刚清空的 tag，可能无法正确清除 tag。但值会被更新。总体上，只要写回发生在清除之前（组合逻辑顺序），应该没问题。但为了安全，可以给清除一个更高的优先级，或者确保写回在清除后仍然有效。

目前看风险较低，但需要仔细检查波形。

---

## 6. 寄存器文件的 tag 更新：可能产生悬垂 tag
**文件：`RegisterFile.scala`**
```scala
when(!io.clear && io.destination_valid && io.destination =/= 0.U) {
  regs(io.destination).tag := io.tail
  regs(io.destination).tag_valid := true.B
}
```
- 这里 `io.tail` 来自 ROB，是当前分配的 ROB 索引。但如果在同一周期，该 ROB 条目被提交并写回（可能吗？），则可能产生 tag_valid 被设置后立即被清除的情况。不过由于顺序写，应该 OK。
- 更严重的问题是：当 ROB 清空时，`io.tail` 会被重置，但寄存器文件中的 tag 仍然指向旧的、可能已经被覆盖的 ROB 索引。这可能导致后续指令等待一个永远不会广播的值。但你的 RS 中会通过 `rob_values` 和 `rf_regs.tag_valid` 来检测，如果 `rob_values(tag).valid` 为 false，则会 fallback 到寄存器值。所以这不算致命。

---

## 7. 指令获取的 epoch 机制：可能漏掉响应
**文件：`InstructionFetch.scala`**

- 你使用了 epoch 来区分重定向前后的请求，这个机制是正确的。但注意在 `when(io.clear || io.resetValid)` 中，`epoch := ~epoch`，同时 `outstanding := false.B`。
- 如果在同一个周期，刚好有一个指令响应返回（`io.mem_iout_valid` 为 true），且它匹配旧的 epoch，但由于 `outstanding` 已经被清 false，`respMatches` 条件中的 `(outstanding || issuing)` 可能为 false（因为 `issuing` 也可能为 false），导致这个响应被丢弃。这可能导致指令丢失。

### 后果：
偶尔丢失指令会导致 PC 错乱，程序跑飞。

### 修复建议：
- 在清空时，应该允许同一周期内已经发出的请求的响应返回。通常的做法是：在清空时，不立即清除 `outstanding`，而是让 `outstanding` 继续存在直到响应返回，但通过 epoch 匹配来忽略它们（你已经做到了）。但你在清空时设置了 `outstanding := false.B`，这可能会过早地关闭响应窗口。
- 应该让 `outstanding` 在清空时保持不变，直到收到响应才清除。清空操作只改变 epoch，这样任何尚未返回的旧请求都会因为 epoch 不匹配而被忽略。这样更安全。

---

## 8. LSB 的 operands_ready_prev 使用不当
**文件：`ReservationStationLSB.scala`**
- `operands_ready_prev` 在每个周期被赋值为 `operands_ready_now`，然后用于下一周期的 `ready_gate`。这意味着 `ready_gate` 依赖于上一个周期的就绪状态。这种跨周期依赖容易导致状态机出现不必要的延迟，甚至死锁。

如前所述，建议移除 `headReadyPrev` 的依赖。

---

## 总结：最可能出问题的几个点
1. **LSB 清除逻辑**：分支误预测后保留 store 导致队列混乱 → **首要怀疑对象**。
2. **LSB 执行使能条件**：load 因 `ready_gate` 被延迟一周期，可能造成死锁 → **次要怀疑对象**。
3. **CDB 广播 store 结果**：可能错误唤醒等待指令 → **中等风险**。
4. **IF 的 epoch 处理**：可能丢失指令响应 → **较低风险，但仍可能**。

### 调试建议
- 针对复杂测试点，开启关键信号的波形（或打印），重点关注：
  - 分支误预测时的 LSB 状态变化。
  - load 指令的执行时机与 `ready_gate` 的关系。
  - 任何长时间未被执行的指令（观察 RS/LSB 中条目停留的周期数）。
- 如果可能，使用 RISC-V 模拟器（如 Spike）作为 golden model，对比你的 CPU 的提交结果，找出第一个结果不一致的指令，反向追溯其数据依赖，往往能发现根本问题。
