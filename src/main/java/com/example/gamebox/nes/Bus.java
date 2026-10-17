package com.example.gamebox.nes;

/**
 * The NES memory bus / console. Owns the CPU, PPU, 2 KB of work RAM, the
 * cartridge and the controller, maps the CPU address space, handles OAM DMA
 * ($4014), and clocks the system a frame at a time.
 *
 * <h2>CPU memory map</h2>
 * <pre>
 *   $0000-$1FFF  2 KB internal RAM (mirrored every $0800)
 *   $2000-$3FFF  PPU registers (8 bytes, mirrored every 8)
 *   $4000-$4017  APU + I/O registers ($4014 = OAM DMA, $4016 = controller 1)
 *   $4020-$FFFF  cartridge (PRG-ROM for NROM at $8000-$FFFF)
 * </pre>
 *
 * <p>APU is not emulated (GameBox is a visual port, like its DOOM); APU register
 * reads return open-bus-ish 0 and writes are ignored, which SMB1 tolerates.
 */
public final class Bus {

    private final int[] ram = new int[0x800];
    private Cartridge cartridge;
    private final Cpu cpu;
    private final Ppu ppu;
    private final Controller controller1 = new Controller();

    private long systemCycles;

    public Bus() {
        this.cpu = new Cpu(this);
        this.ppu = new Ppu(this);
    }

    public void insert(Cartridge cart) {
        this.cartridge = cart;
    }

    public Cartridge cartridge() {
        return cartridge;
    }

    public Cpu cpu() {
        return cpu;
    }

    public Ppu ppu() {
        return ppu;
    }

    public Controller controller1() {
        return controller1;
    }

    public void reset() {
        cpu.reset();
        ppu.reset();
        systemCycles = 0;
    }

    // ─── CPU bus access ─────────────────────────────────────────────────────────

    public int cpuRead(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x2000) {
            return ram[addr & 0x07FF];
        } else if (addr < 0x4000) {
            return ppu.readRegister(addr & 7);
        } else if (addr == 0x4016) {
            return controller1.read();
        } else if (addr == 0x4017) {
            return 0; // controller 2 not wired
        } else if (addr < 0x4020) {
            return 0; // APU / I/O (unemulated)
        } else {
            return cartridge.cpuRead(addr);
        }
    }

    public void cpuWrite(int addr, int value) {
        addr &= 0xFFFF;
        value &= 0xFF;
        if (addr < 0x2000) {
            ram[addr & 0x07FF] = value;
        } else if (addr < 0x4000) {
            ppu.writeRegister(addr & 7, value);
        } else if (addr == 0x4014) {
            oamDma(value);
        } else if (addr == 0x4016) {
            controller1.write(value);
        } else if (addr < 0x4020) {
            // APU / I/O — ignored (no audio in this visual port)
        } else {
            cartridge.cpuWrite(addr, value);
        }
    }

    /** $4014 OAM DMA: copy 256 bytes from CPU page $xx00 into PPU OAM. */
    private void oamDma(int page) {
        int base = page << 8;
        for (int i = 0; i < 256; i++) {
            ppu.oamDmaWrite(i, cpuRead(base + i));
        }
        // DMA stalls the CPU ~513-514 cycles; approximate by advancing the PPU.
        for (int i = 0; i < 513 * 3; i++) {
            ppu.step();
        }
    }

    // ─── Clocking ────────────────────────────────────────────────────────────

    /**
     * Runs the console until the PPU finishes one frame, then returns. The CPU is
     * stepped instruction-by-instruction; the PPU runs 3 cycles per CPU cycle.
     * NMI from the PPU is forwarded to the CPU.
     */
    public void runFrame() {
        boolean frameDone = false;
        int guard = 0;
        while (!frameDone && guard++ < 2_000_000) {
            int cpuCycles = cpu.step();
            for (int i = 0; i < cpuCycles * 3; i++) {
                ppu.step();
                if (ppu.nmiOutput()) {
                    ppu.clearNmi();
                    cpu.requestNmi();
                }
                if (ppu.frameReady()) {
                    frameDone = true;
                }
            }
            systemCycles += cpuCycles;
        }
    }
}
