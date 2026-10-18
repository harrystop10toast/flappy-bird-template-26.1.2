package com.example.gamebox.gb;

/**
 * The Game Boy memory bus / console. Owns the CPU, PPU, work RAM, high RAM, the
 * cartridge, the joypad, the DIV/TIMA timer and the interrupt flags, maps the
 * 16-bit address space, handles the 0xFF46 OAM DMA, and clocks the whole system
 * one frame at a time.
 *
 * <h2>Memory map</h2>
 * <pre>
 *   0x0000-0x7FFF  cartridge ROM (banked)
 *   0x8000-0x9FFF  VRAM (PPU)
 *   0xA000-0xBFFF  external cartridge RAM (banked)
 *   0xC000-0xDFFF  work RAM
 *   0xE000-0xFDFF  echo RAM (mirror of 0xC000-0xDDFF)
 *   0xFE00-0xFE9F  OAM (PPU)
 *   0xFEA0-0xFEFF  unusable
 *   0xFF00-0xFF7F  I/O registers
 *   0xFF80-0xFFFE  high RAM
 *   0xFFFF         interrupt enable (IE)
 * </pre>
 *
 * <p>The APU (sound, 0xFF10-0xFF3F) is not emulated — GameBox is a visual port —
 * so those writes are ignored and reads return 0xFF, which games tolerate.
 */
public final class GbBus {

    private final GbCpu cpu;
    private final GbPpu ppu;
    private final GbJoypad joypad = new GbJoypad();

    private GbCartridge cart;

    private final int[] wram = new int[0x2000]; // 0xC000-0xDFFF
    private final int[] hram = new int[0x7F];    // 0xFF80-0xFFFE

    // Interrupt registers.
    private int ie;      // 0xFFFF
    private int iflag;   // 0xFF0F

    // Timer registers.
    private int div;     // 0xFF04 (upper 8 bits of the 16-bit divider)
    private int divCounter; // full 16-bit internal counter
    private int tima;    // 0xFF05
    private int tma;     // 0xFF06
    private int tac;     // 0xFF07

    public GbBus() {
        this.cpu = new GbCpu(this);
        this.ppu = new GbPpu(this);
    }

    public void insert(GbCartridge c) {
        this.cart = c;
    }

    public GbCartridge cartridge() {
        return cart;
    }

    public GbCpu cpu() {
        return cpu;
    }

    public GbPpu ppu() {
        return ppu;
    }

    public GbJoypad joypad() {
        return joypad;
    }

    public void reset() {
        cpu.reset();
        ppu.reset();
        java.util.Arrays.fill(wram, 0);
        java.util.Arrays.fill(hram, 0);
        ie = 0;
        iflag = 0xE1;
        div = 0;
        divCounter = 0xABCC;
        tima = 0;
        tma = 0;
        tac = 0;
    }

    // ─── Interrupt interface (used by CPU + PPU) ──────────────────────────────

    public int ie() {
        return ie;
    }

    public int iflags() {
        return iflag;
    }

    /** Raise interrupt {@code i} (0 VBlank, 1 STAT, 2 Timer, 3 Serial, 4 Joypad). */
    public void requestInterrupt(int i) {
        iflag |= (1 << i);
    }

    public void clearInterrupt(int i) {
        iflag &= ~(1 << i);
    }

    // ─── Memory access ────────────────────────────────────────────────────────

    public int read(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x8000) {
            return cart != null ? cart.readRom(addr) : 0xFF;
        } else if (addr < 0xA000) {
            return ppu.readVram(addr);
        } else if (addr < 0xC000) {
            return cart != null ? cart.readRam(addr) : 0xFF;
        } else if (addr < 0xE000) {
            return wram[(addr - 0xC000) & 0x1FFF];
        } else if (addr < 0xFE00) {
            return wram[(addr - 0xE000) & 0x1FFF]; // echo RAM
        } else if (addr < 0xFEA0) {
            return ppu.readOam(addr);
        } else if (addr < 0xFF00) {
            return 0xFF; // unusable
        } else if (addr < 0xFF80) {
            return readIo(addr);
        } else if (addr < 0xFFFF) {
            return hram[(addr - 0xFF80) & 0x7F];
        } else {
            return ie;
        }
    }

    public void write(int addr, int v) {
        addr &= 0xFFFF;
        v &= 0xFF;
        if (addr < 0x8000) {
            if (cart != null) {
                cart.writeRom(addr, v);
            }
        } else if (addr < 0xA000) {
            ppu.writeVram(addr, v);
        } else if (addr < 0xC000) {
            if (cart != null) {
                cart.writeRam(addr, v);
            }
        } else if (addr < 0xE000) {
            wram[(addr - 0xC000) & 0x1FFF] = v;
        } else if (addr < 0xFE00) {
            wram[(addr - 0xE000) & 0x1FFF] = v; // echo RAM
        } else if (addr < 0xFEA0) {
            ppu.writeOam(addr, v);
        } else if (addr < 0xFF00) {
            // unusable — ignore
        } else if (addr < 0xFF80) {
            writeIo(addr, v);
        } else if (addr < 0xFFFF) {
            hram[(addr - 0xFF80) & 0x7F] = v;
        } else {
            ie = v;
        }
    }

    // ─── I/O registers 0xFF00-0xFF7F ──────────────────────────────────────────

    private int readIo(int addr) {
        switch (addr) {
            case 0xFF00: return joypad.read();
            case 0xFF04: return div;
            case 0xFF05: return tima;
            case 0xFF06: return tma;
            case 0xFF07: return tac | 0xF8;
            case 0xFF0F: return iflag | 0xE0;
            default:
                if (addr >= 0xFF40 && addr <= 0xFF4B) {
                    return ppu.readReg(addr);
                }
                // APU + unmapped I/O.
                return 0xFF;
        }
    }

    private void writeIo(int addr, int v) {
        switch (addr) {
            case 0xFF00: joypad.write(v); return;
            case 0xFF04: div = 0; divCounter = 0; return; // any write resets DIV
            case 0xFF05: tima = v; return;
            case 0xFF06: tma = v; return;
            case 0xFF07: tac = v & 0x07; return;
            case 0xFF0F: iflag = v & 0x1F; return;
            case 0xFF46: oamDma(v); return;
            default:
                if (addr >= 0xFF40 && addr <= 0xFF4B) {
                    ppu.writeReg(addr, v);
                }
                // APU + unmapped I/O — ignored.
        }
    }

    /** 0xFF46 OAM DMA: copy 160 bytes from page {@code v}×0x100 into OAM. */
    private void oamDma(int page) {
        int base = (page & 0xFF) << 8;
        for (int i = 0; i < 0xA0; i++) {
            ppu.oamDmaWrite(i, read(base + i));
        }
    }

    // ─── Timer (DIV/TIMA) ──────────────────────────────────────────────────────

    private static final int[] TAC_BIT = {9, 3, 5, 7}; // divider bit tapped per TAC freq

    private void stepTimer(int tCycles) {
        int tacBit = TAC_BIT[tac & 0x03];
        boolean enabled = (tac & 0x04) != 0;

        for (int i = 0; i < tCycles; i++) {
            int old = divCounter;
            divCounter = (divCounter + 1) & 0xFFFF;
            div = (divCounter >> 8) & 0xFF;

            if (enabled) {
                // TIMA increments on the falling edge of the tapped divider bit.
                boolean oldBit = ((old >> tacBit) & 1) != 0;
                boolean newBit = ((divCounter >> tacBit) & 1) != 0;
                if (oldBit && !newBit) {
                    tima = (tima + 1) & 0xFF;
                    if (tima == 0) {
                        tima = tma;
                        requestInterrupt(2); // Timer interrupt
                    }
                }
            }
        }
    }

    // ─── Clocking ────────────────────────────────────────────────────────────

    /**
     * Runs the console until the PPU completes one frame (sets frameReady), then
     * returns. A guard caps the number of CPU steps so a pathological ROM can
     * never spin here forever.
     */
    public void runFrame() {
        if (cart == null) {
            return;
        }
        ppu.clearFrameReady();
        int guard = 0;
        while (!ppu.frameReady() && guard++ < 400_000) {
            int t = cpu.step();      // T-cycles
            if (t <= 0) {
                t = 4;
            }
            stepTimer(t);
            ppu.step(t);
        }
    }
}
