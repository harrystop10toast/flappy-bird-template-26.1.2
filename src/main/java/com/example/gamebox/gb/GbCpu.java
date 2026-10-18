package com.example.gamebox.gb;

/**
 * The Sharp <b>LR35902</b> CPU used by the Game Boy — a close relative of the
 * Z80 / Intel 8080. This is a complete interpreter: all 256 base opcodes and all
 * 256 {@code 0xCB}-prefixed opcodes are implemented, with per-instruction cycle
 * counts (in <b>T-cycles</b>, i.e. 4 per machine cycle) and full interrupt
 * dispatch (V-Blank, LCD STAT, Timer, Serial, Joypad).
 *
 * <h2>Crash safety</h2>
 * Every opcode is handled; there is no "unknown opcode" throw. Memory access is
 * delegated to {@link GbBus}, which masks all addresses. The result is that a
 * misbehaving ROM burns cycles rather than taking down the client.
 */
public final class GbCpu {

    private final GbBus bus;

    // 8-bit registers (stored as ints, masked to 0xFF on write paths).
    private int a, b, c, d, e, h, l;
    private int sp, pc;

    // Flags (bit 7 Z, 6 N, 5 H, 4 C) stored individually.
    private boolean fZ, fN, fH, fC;

    // Interrupt master enable + the one-instruction EI delay.
    private boolean ime;
    private boolean imePending;

    private boolean halted;
    private boolean haltBug;

    public GbCpu(GbBus bus) {
        this.bus = bus;
    }

    /** Post-boot register state (DMG, as left by the official boot ROM). */
    public void reset() {
        a = 0x01; fZ = true; fN = false; fH = true; fC = true;
        b = 0x00; c = 0x13;
        d = 0x00; e = 0xD8;
        h = 0x01; l = 0x4D;
        sp = 0xFFFE;
        pc = 0x0100;
        ime = false;
        imePending = false;
        halted = false;
        haltBug = false;
    }

    // ─── Flag helpers ─────────────────────────────────────────────────────────

    private int getF() {
        return (fZ ? 0x80 : 0) | (fN ? 0x40 : 0) | (fH ? 0x20 : 0) | (fC ? 0x10 : 0);
    }

    private void setF(int v) {
        fZ = (v & 0x80) != 0;
        fN = (v & 0x40) != 0;
        fH = (v & 0x20) != 0;
        fC = (v & 0x10) != 0;
    }

    private int af() { return (a << 8) | getF(); }
    private int bc() { return (b << 8) | c; }
    private int de() { return (d << 8) | e; }
    private int hl() { return (h << 8) | l; }

    private void setAf(int v) { a = (v >> 8) & 0xFF; setF(v & 0xFF); }
    private void setBc(int v) { b = (v >> 8) & 0xFF; c = v & 0xFF; }
    private void setDe(int v) { d = (v >> 8) & 0xFF; e = v & 0xFF; }
    private void setHl(int v) { h = (v >> 8) & 0xFF; l = v & 0xFF; }

    // ─── Memory helpers ───────────────────────────────────────────────────────

    private int read(int addr) {
        return bus.read(addr & 0xFFFF) & 0xFF;
    }

    private void write(int addr, int v) {
        bus.write(addr & 0xFFFF, v & 0xFF);
    }

    private int fetch() {
        int v = read(pc);
        if (haltBug) {
            haltBug = false; // HALT bug: PC fails to increment once
        } else {
            pc = (pc + 1) & 0xFFFF;
        }
        return v;
    }

    private int fetch16() {
        int lo = fetch();
        int hi = fetch();
        return (hi << 8) | lo;
    }

    private void push(int v) {
        sp = (sp - 1) & 0xFFFF;
        write(sp, (v >> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        write(sp, v & 0xFF);
    }

    private int pop() {
        int lo = read(sp); sp = (sp + 1) & 0xFFFF;
        int hi = read(sp); sp = (sp + 1) & 0xFFFF;
        return (hi << 8) | lo;
    }

    /**
     * Executes one instruction (handling pending interrupts first) and returns
     * the number of T-cycles it consumed.
     */
    public int step() {
        // Service interrupts before fetching the next opcode.
        int serviced = handleInterrupts();
        if (serviced > 0) {
            return serviced;
        }

        if (halted) {
            return 4; // idle until an interrupt is pending
        }

        boolean eiJustRan = imePending;
        int opcode = fetch();
        int cycles = execute(opcode);

        // EI enables IME *after* the instruction following it.
        if (eiJustRan) {
            ime = true;
            imePending = false;
        }
        return cycles;
    }

    /** If IME and a pending+enabled interrupt exist, dispatch it (20 T-cycles). */
    private int handleInterrupts() {
        int ie = bus.ie();
        int ifr = bus.iflags();
        int pending = ie & ifr & 0x1F;

        if (pending != 0) {
            halted = false; // any pending interrupt wakes HALT, even if IME=0
        }
        if (!ime || pending == 0) {
            return 0;
        }

        for (int i = 0; i < 5; i++) {
            int mask = 1 << i;
            if ((pending & mask) != 0) {
                ime = false;
                bus.clearInterrupt(i);
                push(pc);
                pc = 0x40 + i * 8; // 0x40,0x48,0x50,0x58,0x60
                return 20;
            }
        }
        return 0;
    }

    // ─── ALU ──────────────────────────────────────────────────────────────────

    private void addA(int v) {
        int r = a + v;
        fH = ((a & 0xF) + (v & 0xF)) > 0xF;
        fC = r > 0xFF;
        a = r & 0xFF;
        fZ = a == 0;
        fN = false;
    }

    private void adcA(int v) {
        int carry = fC ? 1 : 0;
        int r = a + v + carry;
        fH = ((a & 0xF) + (v & 0xF) + carry) > 0xF;
        fC = r > 0xFF;
        a = r & 0xFF;
        fZ = a == 0;
        fN = false;
    }

    private void subA(int v) {
        int r = a - v;
        fH = (a & 0xF) - (v & 0xF) < 0;
        fC = r < 0;
        a = r & 0xFF;
        fZ = a == 0;
        fN = true;
    }

    private void sbcA(int v) {
        int carry = fC ? 1 : 0;
        int r = a - v - carry;
        fH = (a & 0xF) - (v & 0xF) - carry < 0;
        fC = r < 0;
        a = r & 0xFF;
        fZ = a == 0;
        fN = true;
    }

    private void andA(int v) {
        a &= v; fZ = a == 0; fN = false; fH = true; fC = false;
    }

    private void xorA(int v) {
        a ^= v; a &= 0xFF; fZ = a == 0; fN = false; fH = false; fC = false;
    }

    private void orA(int v) {
        a |= v; a &= 0xFF; fZ = a == 0; fN = false; fH = false; fC = false;
    }

    private void cpA(int v) {
        int r = a - v;
        fH = (a & 0xF) - (v & 0xF) < 0;
        fC = r < 0;
        fZ = (r & 0xFF) == 0;
        fN = true;
    }

    private int inc8(int v) {
        int r = (v + 1) & 0xFF;
        fH = (v & 0xF) == 0xF;
        fZ = r == 0;
        fN = false;
        return r;
    }

    private int dec8(int v) {
        int r = (v - 1) & 0xFF;
        fH = (v & 0xF) == 0x0;
        fZ = r == 0;
        fN = true;
        return r;
    }

    private void addHl(int v) {
        int hl = hl();
        int r = hl + v;
        fH = ((hl & 0x0FFF) + (v & 0x0FFF)) > 0x0FFF;
        fC = r > 0xFFFF;
        fN = false;
        setHl(r & 0xFFFF);
    }

    private int addSpOffset(int sp0) {
        int off = (byte) fetch(); // signed
        int r = sp0 + off;
        fZ = false;
        fN = false;
        fH = ((sp0 & 0xF) + (off & 0xF)) > 0xF;
        fC = ((sp0 & 0xFF) + (off & 0xFF)) > 0xFF;
        return r & 0xFFFF;
    }

    private void daa() {
        int adjust = 0;
        if (fN) {
            if (fH) adjust |= 0x06;
            if (fC) adjust |= 0x60;
            a = (a - adjust) & 0xFF;
        } else {
            if (fH || (a & 0x0F) > 9) adjust |= 0x06;
            if (fC || a > 0x99) { adjust |= 0x60; fC = true; }
            a = (a + adjust) & 0xFF;
        }
        fZ = a == 0;
        fH = false;
    }

    // ─── Register file access by index (0=B..7=A, 6=(HL)) ──────────────────────

    private int getReg(int idx) {
        switch (idx) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return h;
            case 5: return l;
            case 6: return read(hl());
            case 7: return a;
            default: return 0;
        }
    }

    private void setReg(int idx, int v) {
        v &= 0xFF;
        switch (idx) {
            case 0: b = v; break;
            case 1: c = v; break;
            case 2: d = v; break;
            case 3: e = v; break;
            case 4: h = v; break;
            case 5: l = v; break;
            case 6: write(hl(), v); break;
            case 7: a = v; break;
            default: break;
        }
    }

    // ─── Base opcode table ──────────────────────────────────────────────────

    @SuppressWarnings("fallthrough")
    private int execute(int op) {
        switch (op) {
            case 0x00: return 4;                              // NOP
            case 0x10: fetch(); return 4;                     // STOP (treat as NOP; skip operand)
            case 0x76:                                        // HALT
                if (!ime && (bus.ie() & bus.iflags() & 0x1F) != 0) {
                    haltBug = true;
                } else {
                    halted = true;
                }
                return 4;

            // ── 16-bit loads ──
            case 0x01: setBc(fetch16()); return 12;
            case 0x11: setDe(fetch16()); return 12;
            case 0x21: setHl(fetch16()); return 12;
            case 0x31: sp = fetch16(); return 12;
            case 0x08: { int addr = fetch16(); write(addr, sp & 0xFF); write(addr + 1, (sp >> 8) & 0xFF); return 20; }
            case 0xF9: sp = hl(); return 8;
            case 0xF8: setHl(addSpOffset(sp)); return 12;     // LD HL,SP+r8
            case 0xE8: sp = addSpOffset(sp); return 16;       // ADD SP,r8

            // ── 8-bit immediate loads ──
            case 0x06: b = fetch(); return 8;
            case 0x0E: c = fetch(); return 8;
            case 0x16: d = fetch(); return 8;
            case 0x1E: e = fetch(); return 8;
            case 0x26: h = fetch(); return 8;
            case 0x2E: l = fetch(); return 8;
            case 0x36: write(hl(), fetch()); return 12;
            case 0x3E: a = fetch(); return 8;

            // ── indirect loads to/from A ──
            case 0x02: write(bc(), a); return 8;
            case 0x12: write(de(), a); return 8;
            case 0x0A: a = read(bc()); return 8;
            case 0x1A: a = read(de()); return 8;
            case 0x22: write(hl(), a); setHl((hl() + 1) & 0xFFFF); return 8; // LD (HL+),A
            case 0x32: write(hl(), a); setHl((hl() - 1) & 0xFFFF); return 8; // LD (HL-),A
            case 0x2A: a = read(hl()); setHl((hl() + 1) & 0xFFFF); return 8; // LD A,(HL+)
            case 0x3A: a = read(hl()); setHl((hl() - 1) & 0xFFFF); return 8; // LD A,(HL-)

            case 0xE0: write(0xFF00 + fetch(), a); return 12; // LDH (a8),A
            case 0xF0: a = read(0xFF00 + fetch()); return 12; // LDH A,(a8)
            case 0xE2: write(0xFF00 + c, a); return 8;        // LD (C),A
            case 0xF2: a = read(0xFF00 + c); return 8;        // LD A,(C)
            case 0xEA: write(fetch16(), a); return 16;        // LD (a16),A
            case 0xFA: a = read(fetch16()); return 16;        // LD A,(a16)

            // ── 16-bit inc/dec ──
            case 0x03: setBc((bc() + 1) & 0xFFFF); return 8;
            case 0x13: setDe((de() + 1) & 0xFFFF); return 8;
            case 0x23: setHl((hl() + 1) & 0xFFFF); return 8;
            case 0x33: sp = (sp + 1) & 0xFFFF; return 8;
            case 0x0B: setBc((bc() - 1) & 0xFFFF); return 8;
            case 0x1B: setDe((de() - 1) & 0xFFFF); return 8;
            case 0x2B: setHl((hl() - 1) & 0xFFFF); return 8;
            case 0x3B: sp = (sp - 1) & 0xFFFF; return 8;

            // ── 8-bit inc/dec ──
            case 0x04: b = inc8(b); return 4;
            case 0x0C: c = inc8(c); return 4;
            case 0x14: d = inc8(d); return 4;
            case 0x1C: e = inc8(e); return 4;
            case 0x24: h = inc8(h); return 4;
            case 0x2C: l = inc8(l); return 4;
            case 0x34: write(hl(), inc8(read(hl()))); return 12;
            case 0x3C: a = inc8(a); return 4;
            case 0x05: b = dec8(b); return 4;
            case 0x0D: c = dec8(c); return 4;
            case 0x15: d = dec8(d); return 4;
            case 0x1D: e = dec8(e); return 4;
            case 0x25: h = dec8(h); return 4;
            case 0x2D: l = dec8(l); return 4;
            case 0x35: write(hl(), dec8(read(hl()))); return 12;
            case 0x3D: a = dec8(a); return 4;

            // ── 16-bit ADD HL ──
            case 0x09: addHl(bc()); return 8;
            case 0x19: addHl(de()); return 8;
            case 0x29: addHl(hl()); return 8;
            case 0x39: addHl(sp); return 8;

            // ── rotates on A ──
            case 0x07: { int c7 = (a >> 7) & 1; a = ((a << 1) | c7) & 0xFF; fZ = false; fN = false; fH = false; fC = c7 != 0; return 4; } // RLCA
            case 0x0F: { int c0 = a & 1; a = ((a >> 1) | (c0 << 7)) & 0xFF; fZ = false; fN = false; fH = false; fC = c0 != 0; return 4; } // RRCA
            case 0x17: { int c7 = (a >> 7) & 1; a = ((a << 1) | (fC ? 1 : 0)) & 0xFF; fZ = false; fN = false; fH = false; fC = c7 != 0; return 4; } // RLA
            case 0x1F: { int c0 = a & 1; a = ((a >> 1) | (fC ? 0x80 : 0)) & 0xFF; fZ = false; fN = false; fH = false; fC = c0 != 0; return 4; } // RRA

            case 0x27: daa(); return 4;                       // DAA
            case 0x2F: a = (~a) & 0xFF; fN = true; fH = true; return 4; // CPL
            case 0x37: fC = true; fN = false; fH = false; return 4;     // SCF
            case 0x3F: fC = !fC; fN = false; fH = false; return 4;      // CCF

            // ── jumps ──
            case 0xC3: pc = fetch16(); return 16;             // JP a16
            case 0xE9: pc = hl(); return 4;                   // JP (HL)
            case 0xC2: { int addr = fetch16(); if (!fZ) { pc = addr; return 16; } return 12; }
            case 0xCA: { int addr = fetch16(); if (fZ)  { pc = addr; return 16; } return 12; }
            case 0xD2: { int addr = fetch16(); if (!fC) { pc = addr; return 16; } return 12; }
            case 0xDA: { int addr = fetch16(); if (fC)  { pc = addr; return 16; } return 12; }
            case 0x18: { int off = (byte) fetch(); pc = (pc + off) & 0xFFFF; return 12; } // JR r8
            case 0x20: { int off = (byte) fetch(); if (!fZ) { pc = (pc + off) & 0xFFFF; return 12; } return 8; }
            case 0x28: { int off = (byte) fetch(); if (fZ)  { pc = (pc + off) & 0xFFFF; return 12; } return 8; }
            case 0x30: { int off = (byte) fetch(); if (!fC) { pc = (pc + off) & 0xFFFF; return 12; } return 8; }
            case 0x38: { int off = (byte) fetch(); if (fC)  { pc = (pc + off) & 0xFFFF; return 12; } return 8; }

            // ── calls / returns / rst ──
            case 0xCD: { int addr = fetch16(); push(pc); pc = addr; return 24; } // CALL a16
            case 0xC4: { int addr = fetch16(); if (!fZ) { push(pc); pc = addr; return 24; } return 12; }
            case 0xCC: { int addr = fetch16(); if (fZ)  { push(pc); pc = addr; return 24; } return 12; }
            case 0xD4: { int addr = fetch16(); if (!fC) { push(pc); pc = addr; return 24; } return 12; }
            case 0xDC: { int addr = fetch16(); if (fC)  { push(pc); pc = addr; return 24; } return 12; }
            case 0xC9: pc = pop(); return 16;                 // RET
            case 0xD9: pc = pop(); ime = true; return 16;     // RETI
            case 0xC0: if (!fZ) { pc = pop(); return 20; } return 8;
            case 0xC8: if (fZ)  { pc = pop(); return 20; } return 8;
            case 0xD0: if (!fC) { pc = pop(); return 20; } return 8;
            case 0xD8: if (fC)  { pc = pop(); return 20; } return 8;
            case 0xC7: push(pc); pc = 0x00; return 16;
            case 0xCF: push(pc); pc = 0x08; return 16;
            case 0xD7: push(pc); pc = 0x10; return 16;
            case 0xDF: push(pc); pc = 0x18; return 16;
            case 0xE7: push(pc); pc = 0x20; return 16;
            case 0xEF: push(pc); pc = 0x28; return 16;
            case 0xF7: push(pc); pc = 0x30; return 16;
            case 0xFF: push(pc); pc = 0x38; return 16;

            // ── stack push/pop ──
            case 0xC1: setBc(pop()); return 12;
            case 0xD1: setDe(pop()); return 12;
            case 0xE1: setHl(pop()); return 12;
            case 0xF1: setAf(pop() & 0xFFF0); return 12;      // low nibble of F always 0
            case 0xC5: push(bc()); return 16;
            case 0xD5: push(de()); return 16;
            case 0xE5: push(hl()); return 16;
            case 0xF5: push(af()); return 16;

            // ── interrupts ──
            case 0xF3: ime = false; imePending = false; return 4; // DI
            case 0xFB: imePending = true; return 4;               // EI
            case 0xCB: return 4 + executeCb(fetch());             // CB prefix

            default:
                break;
        }

        // ── LD r,r' block: 0x40-0x7F (0x76 HALT handled above) ──
        if (op >= 0x40 && op <= 0x7F) {
            int dst = (op >> 3) & 7;
            int src = op & 7;
            int v = getReg(src);
            setReg(dst, v);
            int cost = (dst == 6 || src == 6) ? 8 : 4;
            return cost;
        }

        // ── ALU A,r block: 0x80-0xBF ──
        if (op >= 0x80 && op <= 0xBF) {
            int src = op & 7;
            int v = getReg(src);
            int cost = (src == 6) ? 8 : 4;
            switch ((op >> 3) & 7) {
                case 0: addA(v); break;
                case 1: adcA(v); break;
                case 2: subA(v); break;
                case 3: sbcA(v); break;
                case 4: andA(v); break;
                case 5: xorA(v); break;
                case 6: orA(v); break;
                case 7: cpA(v); break;
                default: break;
            }
            return cost;
        }

        // ── ALU A,d8 immediates ──
        switch (op) {
            case 0xC6: addA(fetch()); return 8;
            case 0xCE: adcA(fetch()); return 8;
            case 0xD6: subA(fetch()); return 8;
            case 0xDE: sbcA(fetch()); return 8;
            case 0xE6: andA(fetch()); return 8;
            case 0xEE: xorA(fetch()); return 8;
            case 0xF6: orA(fetch()); return 8;
            case 0xFE: cpA(fetch()); return 8;
            default:
                // Illegal/undefined opcodes (0xD3,0xDB,0xDD,0xE3,0xE4,0xEB,0xEC,
                // 0xED,0xF4,0xFC,0xFD) — behave as NOP so we never crash.
                return 4;
        }
    }

    // ─── CB-prefixed opcode table (fully regular) ──────────────────────────────

    private int executeCb(int op) {
        int src = op & 7;
        int type = (op >> 3) & 7;
        int block = (op >> 6) & 3;
        int cost = (src == 6) ? 16 : 8;

        if (block == 0) {
            // Rotate/shift/swap group.
            int v = getReg(src);
            int r;
            switch (type) {
                case 0: r = rlc(v); break;
                case 1: r = rrc(v); break;
                case 2: r = rl(v); break;
                case 3: r = rr(v); break;
                case 4: r = sla(v); break;
                case 5: r = sra(v); break;
                case 6: r = swap(v); break;
                case 7: r = srl(v); break;
                default: r = v; break;
            }
            setReg(src, r);
            return cost;
        } else if (block == 1) {
            // BIT b,r — does not write back; (HL) form costs 12, not 16.
            int v = getReg(src);
            fZ = (v & (1 << type)) == 0;
            fN = false;
            fH = true;
            return (src == 6) ? 12 : 8;
        } else if (block == 2) {
            // RES b,r
            setReg(src, getReg(src) & ~(1 << type));
            return cost;
        } else {
            // SET b,r
            setReg(src, getReg(src) | (1 << type));
            return cost;
        }
    }

    private int rlc(int v) {
        int c7 = (v >> 7) & 1;
        int r = ((v << 1) | c7) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = c7 != 0;
        return r;
    }

    private int rrc(int v) {
        int c0 = v & 1;
        int r = ((v >> 1) | (c0 << 7)) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = c0 != 0;
        return r;
    }

    private int rl(int v) {
        int c7 = (v >> 7) & 1;
        int r = ((v << 1) | (fC ? 1 : 0)) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = c7 != 0;
        return r;
    }

    private int rr(int v) {
        int c0 = v & 1;
        int r = ((v >> 1) | (fC ? 0x80 : 0)) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = c0 != 0;
        return r;
    }

    private int sla(int v) {
        int c7 = (v >> 7) & 1;
        int r = (v << 1) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = c7 != 0;
        return r;
    }

    private int sra(int v) {
        int c0 = v & 1;
        int r = ((v >> 1) | (v & 0x80)) & 0xFF; // keep sign bit
        fZ = r == 0; fN = false; fH = false; fC = c0 != 0;
        return r;
    }

    private int srl(int v) {
        int c0 = v & 1;
        int r = (v >> 1) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = c0 != 0;
        return r;
    }

    private int swap(int v) {
        int r = ((v >> 4) | (v << 4)) & 0xFF;
        fZ = r == 0; fN = false; fH = false; fC = false;
        return r;
    }

    // ─── Exposed for a headless test harness ──────────────────────────────────

    public int pc() { return pc; }
    public int sp() { return sp; }
    public int regA() { return a; }
    public boolean isHalted() { return halted; }
}
