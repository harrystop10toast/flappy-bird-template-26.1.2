package com.example.gamebox.nes;

/**
 * NMOS 6502 CPU (the NES 2A03 core, minus decimal mode, which the 2A03 lacks).
 * Implements the full official opcode set with correct addressing modes, flag
 * behaviour, cycle counts (including the +1 page-cross penalties and branch
 * penalties), and NMI / IRQ / BRK handling. Unofficial opcodes are treated as
 * NOPs of the right size — SMB1 does not use them.
 *
 * <p>{@link #step()} executes one instruction and returns the cycles it took.
 */
public final class Cpu {

    private final Bus bus;

    // Registers
    private int a, xr, yr, sp;
    private int pc;
    private int status; // NV-BDIZC

    // Flag bits
    private static final int C = 0x01;
    private static final int Z = 0x02;
    private static final int I = 0x04;
    private static final int D = 0x08;
    private static final int B = 0x10;
    private static final int U = 0x20;
    private static final int V = 0x40;
    private static final int N = 0x80;

    private int cycles;      // cycles for the current instruction
    private boolean pageCrossed;

    private boolean nmiPending;
    private boolean irqPending;

    public Cpu(Bus bus) {
        this.bus = bus;
    }

    public void reset() {
        a = xr = yr = 0;
        sp = 0xFD;
        status = U | I;
        pc = read16(0xFFFC);
        nmiPending = false;
        irqPending = false;
    }

    public void requestNmi() {
        nmiPending = true;
    }

    public void requestIrq() {
        irqPending = true;
    }

    private int read(int addr) {
        return bus.cpuRead(addr & 0xFFFF) & 0xFF;
    }

    private void write(int addr, int value) {
        bus.cpuWrite(addr & 0xFFFF, value & 0xFF);
    }

    private int read16(int addr) {
        int lo = read(addr);
        int hi = read(addr + 1);
        return (hi << 8) | lo;
    }

    /** 6502 bug: indirect JMP wraps within the page for the high byte. */
    private int read16Bug(int addr) {
        int lo = read(addr);
        int hi = read((addr & 0xFF00) | ((addr + 1) & 0x00FF));
        return (hi << 8) | lo;
    }

    private void push(int value) {
        write(0x0100 | sp, value);
        sp = (sp - 1) & 0xFF;
    }

    private int pull() {
        sp = (sp + 1) & 0xFF;
        return read(0x0100 | sp);
    }

    private void push16(int value) {
        push((value >> 8) & 0xFF);
        push(value & 0xFF);
    }

    private int pull16() {
        int lo = pull();
        int hi = pull();
        return (hi << 8) | lo;
    }

    private void setZN(int value) {
        value &= 0xFF;
        if (value == 0) status |= Z; else status &= ~Z;
        if ((value & 0x80) != 0) status |= N; else status &= ~N;
    }

    private void setFlag(int flag, boolean on) {
        if (on) status |= flag; else status &= ~flag;
    }

    private boolean flag(int flag) {
        return (status & flag) != 0;
    }

    // ─── Interrupts ────────────────────────────────────────────────────────────

    private int nmi() {
        push16(pc);
        push((status & ~B) | U);
        setFlag(I, true);
        pc = read16(0xFFFA);
        return 7;
    }

    private int irq() {
        push16(pc);
        push((status & ~B) | U);
        setFlag(I, true);
        pc = read16(0xFFFE);
        return 7;
    }

    // ─── Addressing ──────────────────────────────────────────────────────────

    private int imm() {
        return pc++;
    }

    private int zp() {
        return read(pc++) & 0xFF;
    }

    private int zpx() {
        return (read(pc++) + xr) & 0xFF;
    }

    private int zpy() {
        return (read(pc++) + yr) & 0xFF;
    }

    private int abs() {
        int addr = read16(pc);
        pc += 2;
        return addr;
    }

    private int absx() {
        int base = read16(pc);
        pc += 2;
        int addr = (base + xr) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
        return addr;
    }

    private int absy() {
        int base = read16(pc);
        pc += 2;
        int addr = (base + yr) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
        return addr;
    }

    private int indx() {
        int ptr = (read(pc++) + xr) & 0xFF;
        int lo = read(ptr);
        int hi = read((ptr + 1) & 0xFF);
        return ((hi << 8) | lo) & 0xFFFF;
    }

    private int indy() {
        int ptr = read(pc++) & 0xFF;
        int lo = read(ptr);
        int hi = read((ptr + 1) & 0xFF);
        int base = (hi << 8) | lo;
        int addr = (base + yr) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
        return addr;
    }

    // ─── Step ────────────────────────────────────────────────────────────────

    public int step() {
        if (nmiPending) {
            nmiPending = false;
            return nmi();
        }
        if (irqPending && !flag(I)) {
            irqPending = false;
            return irq();
        }
        irqPending = false;

        pageCrossed = false;
        int opcode = read(pc++);
        cycles = CYCLES[opcode];
        execute(opcode);
        return cycles;
    }

    private void branch(boolean cond) {
        int offset = (byte) read(pc++);
        if (cond) {
            int old = pc;
            pc = (pc + offset) & 0xFFFF;
            cycles++;
            if ((old & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
        }
    }

    private void addPageCrossCycle() {
        if (pageCrossed) {
            cycles++;
        }
    }

    // ─── Core ALU ops ────────────────────────────────────────────────────────

    private void adc(int value) {
        int sum = a + value + (flag(C) ? 1 : 0);
        setFlag(C, sum > 0xFF);
        setFlag(V, (~(a ^ value) & (a ^ sum) & 0x80) != 0);
        a = sum & 0xFF;
        setZN(a);
    }

    private void sbc(int value) {
        adc(value ^ 0xFF);
    }

    private void cmp(int reg, int value) {
        int diff = (reg - value) & 0x1FF;
        setFlag(C, reg >= value);
        setZN(diff & 0xFF);
    }

    private int asl(int value) {
        setFlag(C, (value & 0x80) != 0);
        value = (value << 1) & 0xFF;
        setZN(value);
        return value;
    }

    private int lsr(int value) {
        setFlag(C, (value & 0x01) != 0);
        value = (value >> 1) & 0xFF;
        setZN(value);
        return value;
    }

    private int rol(int value) {
        int carry = flag(C) ? 1 : 0;
        setFlag(C, (value & 0x80) != 0);
        value = ((value << 1) | carry) & 0xFF;
        setZN(value);
        return value;
    }

    private int ror(int value) {
        int carry = flag(C) ? 0x80 : 0;
        setFlag(C, (value & 0x01) != 0);
        value = ((value >> 1) | carry) & 0xFF;
        setZN(value);
        return value;
    }

    @SuppressWarnings("fallthrough")
    private void execute(int op) {
        switch (op) {
            // ─── Loads ──
            case 0xA9: a = read(imm()); setZN(a); break;
            case 0xA5: a = read(zp()); setZN(a); break;
            case 0xB5: a = read(zpx()); setZN(a); break;
            case 0xAD: a = read(abs()); setZN(a); break;
            case 0xBD: a = read(absx()); addPageCrossCycle(); setZN(a); break;
            case 0xB9: a = read(absy()); addPageCrossCycle(); setZN(a); break;
            case 0xA1: a = read(indx()); setZN(a); break;
            case 0xB1: a = read(indy()); addPageCrossCycle(); setZN(a); break;

            case 0xA2: xr = read(imm()); setZN(xr); break;
            case 0xA6: xr = read(zp()); setZN(xr); break;
            case 0xB6: xr = read(zpy()); setZN(xr); break;
            case 0xAE: xr = read(abs()); setZN(xr); break;
            case 0xBE: xr = read(absy()); addPageCrossCycle(); setZN(xr); break;

            case 0xA0: yr = read(imm()); setZN(yr); break;
            case 0xA4: yr = read(zp()); setZN(yr); break;
            case 0xB4: yr = read(zpx()); setZN(yr); break;
            case 0xAC: yr = read(abs()); setZN(yr); break;
            case 0xBC: yr = read(absx()); addPageCrossCycle(); setZN(yr); break;

            // ─── Stores ──
            case 0x85: write(zp(), a); break;
            case 0x95: write(zpx(), a); break;
            case 0x8D: write(abs(), a); break;
            case 0x9D: write(absx(), a); break;
            case 0x99: write(absy(), a); break;
            case 0x81: write(indx(), a); break;
            case 0x91: write(indy(), a); break;

            case 0x86: write(zp(), xr); break;
            case 0x96: write(zpy(), xr); break;
            case 0x8E: write(abs(), xr); break;

            case 0x84: write(zp(), yr); break;
            case 0x94: write(zpx(), yr); break;
            case 0x8C: write(abs(), yr); break;

            // ─── Register transfers ──
            case 0xAA: xr = a; setZN(xr); break;        // TAX
            case 0xA8: yr = a; setZN(yr); break;        // TAY
            case 0x8A: a = xr; setZN(a); break;         // TXA
            case 0x98: a = yr; setZN(a); break;         // TYA
            case 0xBA: xr = sp; setZN(xr); break;       // TSX
            case 0x9A: sp = xr; break;                   // TXS

            // ─── Stack ──
            case 0x48: push(a); break;                                   // PHA
            case 0x68: a = pull(); setZN(a); break;                      // PLA
            case 0x08: push(status | B | U); break;                      // PHP
            case 0x28: status = (pull() & ~B) | U; break;                // PLP

            // ─── Logic ──
            case 0x29: a &= read(imm()); setZN(a); break;
            case 0x25: a &= read(zp()); setZN(a); break;
            case 0x35: a &= read(zpx()); setZN(a); break;
            case 0x2D: a &= read(abs()); setZN(a); break;
            case 0x3D: a &= read(absx()); addPageCrossCycle(); setZN(a); break;
            case 0x39: a &= read(absy()); addPageCrossCycle(); setZN(a); break;
            case 0x21: a &= read(indx()); setZN(a); break;
            case 0x31: a &= read(indy()); addPageCrossCycle(); setZN(a); break;

            case 0x09: a |= read(imm()); setZN(a); break;
            case 0x05: a |= read(zp()); setZN(a); break;
            case 0x15: a |= read(zpx()); setZN(a); break;
            case 0x0D: a |= read(abs()); setZN(a); break;
            case 0x1D: a |= read(absx()); addPageCrossCycle(); setZN(a); break;
            case 0x19: a |= read(absy()); addPageCrossCycle(); setZN(a); break;
            case 0x01: a |= read(indx()); setZN(a); break;
            case 0x11: a |= read(indy()); addPageCrossCycle(); setZN(a); break;

            case 0x49: a ^= read(imm()); setZN(a); break;
            case 0x45: a ^= read(zp()); setZN(a); break;
            case 0x55: a ^= read(zpx()); setZN(a); break;
            case 0x4D: a ^= read(abs()); setZN(a); break;
            case 0x5D: a ^= read(absx()); addPageCrossCycle(); setZN(a); break;
            case 0x59: a ^= read(absy()); addPageCrossCycle(); setZN(a); break;
            case 0x41: a ^= read(indx()); setZN(a); break;
            case 0x51: a ^= read(indy()); addPageCrossCycle(); setZN(a); break;

            // ─── BIT ──
            case 0x24: { int m = read(zp()); setFlag(Z, (a & m) == 0); setFlag(N, (m & 0x80) != 0); setFlag(V, (m & 0x40) != 0); break; }
            case 0x2C: { int m = read(abs()); setFlag(Z, (a & m) == 0); setFlag(N, (m & 0x80) != 0); setFlag(V, (m & 0x40) != 0); break; }

            // ─── Arithmetic ──
            case 0x69: adc(read(imm())); break;
            case 0x65: adc(read(zp())); break;
            case 0x75: adc(read(zpx())); break;
            case 0x6D: adc(read(abs())); break;
            case 0x7D: adc(read(absx())); addPageCrossCycle(); break;
            case 0x79: adc(read(absy())); addPageCrossCycle(); break;
            case 0x61: adc(read(indx())); break;
            case 0x71: adc(read(indy())); addPageCrossCycle(); break;

            case 0xE9: sbc(read(imm())); break;
            case 0xEB: sbc(read(imm())); break; // unofficial alias
            case 0xE5: sbc(read(zp())); break;
            case 0xF5: sbc(read(zpx())); break;
            case 0xED: sbc(read(abs())); break;
            case 0xFD: sbc(read(absx())); addPageCrossCycle(); break;
            case 0xF9: sbc(read(absy())); addPageCrossCycle(); break;
            case 0xE1: sbc(read(indx())); break;
            case 0xF1: sbc(read(indy())); addPageCrossCycle(); break;

            case 0xC9: cmp(a, read(imm())); break;
            case 0xC5: cmp(a, read(zp())); break;
            case 0xD5: cmp(a, read(zpx())); break;
            case 0xCD: cmp(a, read(abs())); break;
            case 0xDD: cmp(a, read(absx())); addPageCrossCycle(); break;
            case 0xD9: cmp(a, read(absy())); addPageCrossCycle(); break;
            case 0xC1: cmp(a, read(indx())); break;
            case 0xD1: cmp(a, read(indy())); addPageCrossCycle(); break;

            case 0xE0: cmp(xr, read(imm())); break;
            case 0xE4: cmp(xr, read(zp())); break;
            case 0xEC: cmp(xr, read(abs())); break;

            case 0xC0: cmp(yr, read(imm())); break;
            case 0xC4: cmp(yr, read(zp())); break;
            case 0xCC: cmp(yr, read(abs())); break;

            // ─── INC / DEC ──
            case 0xE6: { int ad = zp(); int m = (read(ad) + 1) & 0xFF; write(ad, m); setZN(m); break; }
            case 0xF6: { int ad = zpx(); int m = (read(ad) + 1) & 0xFF; write(ad, m); setZN(m); break; }
            case 0xEE: { int ad = abs(); int m = (read(ad) + 1) & 0xFF; write(ad, m); setZN(m); break; }
            case 0xFE: { int ad = absx(); int m = (read(ad) + 1) & 0xFF; write(ad, m); setZN(m); break; }

            case 0xC6: { int ad = zp(); int m = (read(ad) - 1) & 0xFF; write(ad, m); setZN(m); break; }
            case 0xD6: { int ad = zpx(); int m = (read(ad) - 1) & 0xFF; write(ad, m); setZN(m); break; }
            case 0xCE: { int ad = abs(); int m = (read(ad) - 1) & 0xFF; write(ad, m); setZN(m); break; }
            case 0xDE: { int ad = absx(); int m = (read(ad) - 1) & 0xFF; write(ad, m); setZN(m); break; }

            case 0xE8: xr = (xr + 1) & 0xFF; setZN(xr); break; // INX
            case 0xC8: yr = (yr + 1) & 0xFF; setZN(yr); break; // INY
            case 0xCA: xr = (xr - 1) & 0xFF; setZN(xr); break; // DEX
            case 0x88: yr = (yr - 1) & 0xFF; setZN(yr); break; // DEY

            // ─── Shifts (accumulator + memory) ──
            case 0x0A: a = asl(a); break;
            case 0x06: { int ad = zp(); write(ad, asl(read(ad))); break; }
            case 0x16: { int ad = zpx(); write(ad, asl(read(ad))); break; }
            case 0x0E: { int ad = abs(); write(ad, asl(read(ad))); break; }
            case 0x1E: { int ad = absx(); write(ad, asl(read(ad))); break; }

            case 0x4A: a = lsr(a); break;
            case 0x46: { int ad = zp(); write(ad, lsr(read(ad))); break; }
            case 0x56: { int ad = zpx(); write(ad, lsr(read(ad))); break; }
            case 0x4E: { int ad = abs(); write(ad, lsr(read(ad))); break; }
            case 0x5E: { int ad = absx(); write(ad, lsr(read(ad))); break; }

            case 0x2A: a = rol(a); break;
            case 0x26: { int ad = zp(); write(ad, rol(read(ad))); break; }
            case 0x36: { int ad = zpx(); write(ad, rol(read(ad))); break; }
            case 0x2E: { int ad = abs(); write(ad, rol(read(ad))); break; }
            case 0x3E: { int ad = absx(); write(ad, rol(read(ad))); break; }

            case 0x6A: a = ror(a); break;
            case 0x66: { int ad = zp(); write(ad, ror(read(ad))); break; }
            case 0x76: { int ad = zpx(); write(ad, ror(read(ad))); break; }
            case 0x6E: { int ad = abs(); write(ad, ror(read(ad))); break; }
            case 0x7E: { int ad = absx(); write(ad, ror(read(ad))); break; }

            // ─── Jumps / calls ──
            case 0x4C: pc = abs(); break;                             // JMP abs
            case 0x6C: { int ptr = abs(); pc = read16Bug(ptr); break; } // JMP (ind)
            case 0x20: { int addr = read16(pc); push16((pc + 1) & 0xFFFF); pc = addr; break; } // JSR
            case 0x60: pc = (pull16() + 1) & 0xFFFF; break;            // RTS
            case 0x40: status = (pull() & ~B) | U; pc = pull16(); break; // RTI

            // ─── Branches ──
            case 0x10: branch(!flag(N)); break; // BPL
            case 0x30: branch(flag(N)); break;  // BMI
            case 0x50: branch(!flag(V)); break; // BVC
            case 0x70: branch(flag(V)); break;  // BVS
            case 0x90: branch(!flag(C)); break; // BCC
            case 0xB0: branch(flag(C)); break;  // BCS
            case 0xD0: branch(!flag(Z)); break; // BNE
            case 0xF0: branch(flag(Z)); break;  // BEQ

            // ─── Flag ops ──
            case 0x18: setFlag(C, false); break; // CLC
            case 0x38: setFlag(C, true); break;  // SEC
            case 0x58: setFlag(I, false); break; // CLI
            case 0x78: setFlag(I, true); break;  // SEI
            case 0xB8: setFlag(V, false); break; // CLV
            case 0xD8: setFlag(D, false); break; // CLD
            case 0xF8: setFlag(D, true); break;  // SED

            // ─── BRK ──
            case 0x00: {
                pc = (pc + 1) & 0xFFFF;
                push16(pc);
                push(status | B | U);
                setFlag(I, true);
                pc = read16(0xFFFE);
                break;
            }

            // ─── NOPs (official 0xEA + common unofficial multi-byte NOPs) ──
            case 0xEA: break;
            case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA: break;
            case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2: pc++; break; // 2-byte NOP imm
            case 0x04: case 0x44: case 0x64: pc++; break;                       // 2-byte NOP zp
            case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4: pc++; break; // NOP zpx
            case 0x0C: pc += 2; break;                                          // NOP abs
            case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:   // NOP absx
                absx(); addPageCrossCycle(); break;

            default:
                // Unknown/unofficial opcode: treat as a 1-byte NOP so execution
                // doesn't derail. SMB1 never hits this path.
                break;
        }
    }

    // Base cycle counts per opcode (page-cross/branch extras added at runtime).
    private static final int[] CYCLES = {
        7,6,2,8,3,3,5,5,3,2,2,2,4,4,6,6,
        2,5,2,8,4,4,6,6,2,4,2,7,4,4,7,7,
        6,6,2,8,3,3,5,5,4,2,2,2,4,4,6,6,
        2,5,2,8,4,4,6,6,2,4,2,7,4,4,7,7,
        6,6,2,8,3,3,5,5,3,2,2,2,3,4,6,6,
        2,5,2,8,4,4,6,6,2,4,2,7,4,4,7,7,
        6,6,2,8,3,3,5,5,4,2,2,2,5,4,6,6,
        2,5,2,8,4,4,6,6,2,4,2,7,4,4,7,7,
        2,6,2,6,3,3,3,3,2,2,2,2,4,4,4,4,
        2,6,2,6,4,4,4,4,2,5,2,5,5,5,5,5,
        2,6,2,6,3,3,3,3,2,2,2,2,4,4,4,4,
        2,5,2,5,4,4,4,4,2,4,2,4,4,4,4,4,
        2,6,2,8,3,3,5,5,2,2,2,2,4,4,6,6,
        2,5,2,8,4,4,6,6,2,4,2,7,4,4,7,7,
        2,6,2,8,3,3,5,5,2,2,2,2,4,4,6,6,
        2,5,2,8,4,4,6,6,2,4,2,7,4,4,7,7,
    };

    // Accessors for debugging / smoke test.
    public int pc() { return pc; }
    public int a() { return a; }
    public int sp() { return sp; }
    public int status() { return status; }
}
