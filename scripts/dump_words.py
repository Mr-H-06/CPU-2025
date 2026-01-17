from pathlib import Path

path = Path('src/test/resources/test2.hex')
mem = {}
addr = 0
for line in path.read_text().splitlines():
    line = line.strip()
    if not line or line.startswith('//') or line.startswith('#'):
        continue
    if line.startswith('@'):
        addr = int(line[1:], 16)
        continue
    for b in line.split():
        mem[addr] = int(b, 16)
        addr += 1

def word(a: int) -> int:
    return mem.get(a, 0) | (mem.get(a + 1, 0) << 8) | (mem.get(a + 2, 0) << 16) | (mem.get(a + 3, 0) << 24)

def decode(instr: int) -> str:
    opcode = instr & 0x7F
    rd = (instr >> 7) & 0x1F
    funct3 = (instr >> 12) & 0x7
    rs1 = (instr >> 15) & 0x1F
    rs2 = (instr >> 20) & 0x1F
    funct7 = (instr >> 25) & 0x7F

    def sign(val, bits):
        signbit = 1 << (bits - 1)
        return (val ^ signbit) - signbit

    if opcode == 0x37:  # LUI
        imm = instr & 0xFFFFF000
        return f"lui x{rd},{sign(imm,32)}"
    if opcode == 0x17:  # AUIPC
        imm = instr & 0xFFFFF000
        return f"auipc x{rd},{sign(imm,32)}"
    if opcode == 0x6F:  # JAL
        imm = ((instr >> 21) & 0x3FF) << 1
        imm |= ((instr >> 20) & 0x1) << 11
        imm |= ((instr >> 12) & 0xFF) << 12
        imm |= (instr >> 31) << 20
        imm = sign(imm, 21)
        return f"jal x{rd},{imm}"
    if opcode == 0x67:  # JALR
        imm = sign(instr >> 20, 12)
        return f"jalr x{rd},x{rs1},{imm}"
    if opcode == 0x63:  # BRANCH
        imm = ((instr >> 8) & 0xF) << 1
        imm |= ((instr >> 25) & 0x3F) << 5
        imm |= ((instr >> 7) & 0x1) << 11
        imm |= (instr >> 31) << 12
        imm = sign(imm, 13)
        branch = {0: "beq", 1: "bne", 4: "blt", 5: "bge", 6: "bltu", 7: "bgeu"}.get(funct3, "b?")
        return f"{branch} x{rs1},x{rs2},{imm}"
    if opcode == 0x03:  # LOAD
        imm = sign(instr >> 20, 12)
        load = {0: "lb", 1: "lh", 2: "lw", 4: "lbu", 5: "lhu"}.get(funct3, "l?")
        return f"{load} x{rd},{imm}(x{rs1})"
    if opcode == 0x23:  # STORE
        imm = ((instr >> 7) & 0x1F) | (((instr >> 25) & 0x7F) << 5)
        imm = sign(imm, 12)
        store = {0: "sb", 1: "sh", 2: "sw"}.get(funct3, "s?")
        return f"{store} x{rs2},{imm}(x{rs1})"
    if opcode == 0x13:  # OP-IMM
        imm = sign(instr >> 20, 12)
        op = {0: "addi", 2: "slti", 3: "sltiu", 4: "xori", 6: "ori", 7: "andi", 1: "slli", 5: "srli" if funct7 == 0 else "srai"}.get(funct3, "opimm?")
        return f"{op} x{rd},x{rs1},{imm}"
    if opcode == 0x33:  # OP
        op_map = {
            (0, 0): "add",
            (0x20, 0): "sub",
            (0, 1): "sll",
            (0, 2): "slt",
            (0, 3): "sltu",
            (0, 4): "xor",
            (0, 5): "srl",
            (0x20, 5): "sra",
            (0, 6): "or",
            (0, 7): "and",
        }
        op = op_map.get((funct7, funct3), "op?")
        return f"{op} x{rd},x{rs1},x{rs2}"
    return f"unknown 0x{instr:08x}"

addrs = list(range(0x1100, 0x1148, 4))
addrs += list(range(0x1050, 0x1080, 4))
addrs += [0x109C, 0x10A0, 0x10A4, 0x10A8, 0x10AC, 0x10B0, 0x10B4, 0x10B8, 0x10BC, 0x10C0, 0x10C4, 0x10C8, 0x10CC, 0x10D0, 0x10D4]

seen = set()
for a in addrs:
    if a in seen:
        continue
    seen.add(a)
    instr = word(a)
    print(f"{a:08x}: {instr:08x}  {decode(instr)}")
