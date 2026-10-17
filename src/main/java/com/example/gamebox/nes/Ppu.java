package com.example.gamebox.nes;

/**
 * NTSC 2C02 PPU. Scanline/cycle accurate enough to run NROM games such as
 * Super Mario Bros. 1: it implements the "loopy" v/t/x/w scroll registers,
 * background tile fetching, 8-sprite-per-scanline evaluation with sprite-0 hit,
 * and raises NMI at the start of vblank.
 *
 * <p>Produces a 256x240 ARGB framebuffer ({@link #framebuffer()}), refreshed once
 * per frame when {@link #frameReady()} flips true.
 *
 * <p>Timing model: {@link #step()} advances one PPU cycle. The console calls it
 * 3x per CPU cycle. 341 cycles * 262 scanlines per frame.
 */
public final class Ppu {

    public static final int WIDTH = 256;
    public static final int HEIGHT = 240;

    private final Bus bus;

    // ─── Registers ─────────────────────────────────────────────────────────────
    private int ctrl;     // $2000
    private int mask;     // $2001
    private int status;   // $2002
    private int oamAddr;  // $2003

    // Loopy scroll registers
    private int v;        // current VRAM address (15 bit)
    private int t;        // temporary VRAM address (15 bit)
    private int x;        // fine X scroll (3 bit)
    private boolean w;    // write toggle

    private int readBuffer; // $2007 read buffer

    // ─── Memory ──────────────────────────────────────────────────────────────
    private final int[] vram = new int[0x800];     // 2 KB nametable RAM
    private final int[] palette = new int[0x20];
    private final int[] oam = new int[0x100];       // 256 bytes OAM

    // ─── Timing ──────────────────────────────────────────────────────────────
    private int scanline; // -1(261)..260 ; we use 0..261 with 261 as pre-render
    private int cycle;     // 0..340
    private boolean oddFrame;

    private volatile boolean nmiOutput;
    private boolean frameComplete;

    // ─── Background shift registers / latches ──────────────────────────────────
    private int bgNextTileId;
    private int bgNextTileAttr;
    private int bgNextTileLsb;
    private int bgNextTileMsb;
    private int bgShifterPatternLo;
    private int bgShifterPatternHi;
    private int bgShifterAttribLo;
    private int bgShifterAttribHi;

    // ─── Sprites ───────────────────────────────────────────────────────────────
    private final int[] spriteScanline = new int[8 * 4];
    private int spriteCount;
    private final int[] spriteShiftLo = new int[8];
    private final int[] spriteShiftHi = new int[8];
    private final int[] spriteX = new int[8];
    private final int[] spriteAttr = new int[8];
    private boolean spriteZeroHitPossible;
    private boolean spriteZeroBeingRendered;

    private final int[] framebuffer = new int[WIDTH * HEIGHT];

    public Ppu(Bus bus) {
        this.bus = bus;
    }

    public int[] framebuffer() {
        return framebuffer;
    }

    public boolean frameReady() {
        if (frameComplete) {
            frameComplete = false;
            return true;
        }
        return false;
    }

    public boolean nmiOutput() {
        return nmiOutput;
    }

    public void clearNmi() {
        nmiOutput = false;
    }

    // ─── CPU-facing register ports ($2000-$2007) ──────────────────────────────

    public int readRegister(int reg) {
        switch (reg & 7) {
            case 2: { // PPUSTATUS
                int data = (status & 0xE0) | (readBuffer & 0x1F);
                status &= ~0x80; // clear vblank
                w = false;
                return data;
            }
            case 4: // OAMDATA
                return oam[oamAddr & 0xFF];
            case 7: { // PPUDATA
                int data = readBuffer;
                readBuffer = ppuRead(v);
                if ((v & 0x3FFF) >= 0x3F00) {
                    data = readBuffer; // palette reads are immediate
                }
                v = (v + vramIncrement()) & 0x7FFF;
                return data;
            }
            default:
                return 0;
        }
    }

    public void writeRegister(int reg, int value) {
        value &= 0xFF;
        switch (reg & 7) {
            case 0: { // PPUCTRL
                boolean wasNmiOff = (ctrl & 0x80) == 0;
                ctrl = value;
                t = (t & 0xF3FF) | ((value & 0x03) << 10);
                // Enabling NMI while the vblank flag is still set fires an NMI
                // immediately — SMB's init relies on this.
                if (wasNmiOff && (ctrl & 0x80) != 0 && (status & 0x80) != 0) {
                    nmiOutput = true;
                }
                break;
            }
            case 1: // PPUMASK
                mask = value;
                break;
            case 3: // OAMADDR
                oamAddr = value;
                break;
            case 4: // OAMDATA
                oam[oamAddr & 0xFF] = value;
                oamAddr = (oamAddr + 1) & 0xFF;
                break;
            case 5: // PPUSCROLL
                if (!w) {
                    x = value & 0x07;
                    t = (t & 0xFFE0) | (value >> 3);
                    w = true;
                } else {
                    t = (t & 0x8FFF) | ((value & 0x07) << 12);
                    t = (t & 0xFC1F) | ((value & 0xF8) << 2);
                    w = false;
                }
                break;
            case 6: // PPUADDR
                if (!w) {
                    t = (t & 0x00FF) | ((value & 0x3F) << 8);
                    w = true;
                } else {
                    t = (t & 0xFF00) | value;
                    v = t;
                    w = false;
                }
                break;
            case 7: // PPUDATA
                ppuWrite(v, value);
                v = (v + vramIncrement()) & 0x7FFF;
                break;
            default:
                break;
        }
    }

    /** DMA writes a full page into OAM starting at the current oamAddr. */
    public void oamDmaWrite(int index, int value) {
        oam[(oamAddr + index) & 0xFF] = value & 0xFF;
    }

    private int vramIncrement() {
        return (ctrl & 0x04) != 0 ? 32 : 1;
    }

    // ─── PPU bus ───────────────────────────────────────────────────────────────

    private int ppuRead(int addr) {
        addr &= 0x3FFF;
        if (addr < 0x2000) {
            return bus.cartridge().ppuRead(addr);
        } else if (addr < 0x3F00) {
            return vram[mirrorNametable(addr)];
        } else {
            return palette[paletteIndex(addr)] & 0x3F;
        }
    }

    private void ppuWrite(int addr, int value) {
        addr &= 0x3FFF;
        value &= 0xFF;
        if (addr < 0x2000) {
            bus.cartridge().ppuWrite(addr, value);
        } else if (addr < 0x3F00) {
            vram[mirrorNametable(addr)] = value;
        } else {
            palette[paletteIndex(addr)] = value;
        }
    }

    private int paletteIndex(int addr) {
        int i = addr & 0x1F;
        if (i == 0x10) i = 0x00;
        else if (i == 0x14) i = 0x04;
        else if (i == 0x18) i = 0x08;
        else if (i == 0x1C) i = 0x0C;
        return i;
    }

    private int mirrorNametable(int addr) {
        int a = (addr - 0x2000) & 0x0FFF;
        int table = a / 0x400;
        int offset = a & 0x3FF;
        int phys;
        switch (bus.cartridge().mirroring()) {
            case VERTICAL:
                phys = (table & 1) * 0x400 + offset;
                break;
            case HORIZONTAL:
                phys = ((table >> 1) & 1) * 0x400 + offset;
                break;
            case FOUR_SCREEN:
            default:
                phys = (table & 1) * 0x400 + offset; // treat like vertical (no extra RAM)
                break;
        }
        return phys & 0x7FF;
    }

    // ─── Rendering helpers (loopy) ──────────────────────────────────────────────

    private boolean renderingEnabled() {
        return (mask & 0x18) != 0;
    }

    private void incrementScrollX() {
        if (!renderingEnabled()) return;
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;
            v ^= 0x0400;
        } else {
            v++;
        }
    }

    private void incrementScrollY() {
        if (!renderingEnabled()) return;
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000;
        } else {
            v &= ~0x7000;
            int y = (v & 0x03E0) >> 5;
            if (y == 29) {
                y = 0;
                v ^= 0x0800;
            } else if (y == 31) {
                y = 0;
            } else {
                y++;
            }
            v = (v & ~0x03E0) | (y << 5);
        }
    }

    private void transferAddressX() {
        if (!renderingEnabled()) return;
        v = (v & ~0x041F) | (t & 0x041F);
    }

    private void transferAddressY() {
        if (!renderingEnabled()) return;
        v = (v & ~0x7BE0) | (t & 0x7BE0);
    }

    private void loadBackgroundShifters() {
        bgShifterPatternLo = (bgShifterPatternLo & 0xFF00) | bgNextTileLsb;
        bgShifterPatternHi = (bgShifterPatternHi & 0xFF00) | bgNextTileMsb;
        bgShifterAttribLo = (bgShifterAttribLo & 0xFF00) | ((bgNextTileAttr & 0b01) != 0 ? 0xFF : 0x00);
        bgShifterAttribHi = (bgShifterAttribHi & 0xFF00) | ((bgNextTileAttr & 0b10) != 0 ? 0xFF : 0x00);
    }

    private void updateShifters() {
        if ((mask & 0x08) != 0) {
            bgShifterPatternLo = (bgShifterPatternLo << 1) & 0xFFFF;
            bgShifterPatternHi = (bgShifterPatternHi << 1) & 0xFFFF;
            bgShifterAttribLo = (bgShifterAttribLo << 1) & 0xFFFF;
            bgShifterAttribHi = (bgShifterAttribHi << 1) & 0xFFFF;
        }
        if ((mask & 0x10) != 0 && cycle >= 1 && cycle < 258) {
            for (int i = 0; i < spriteCount; i++) {
                if (spriteX[i] > 0) {
                    spriteX[i]--;
                } else {
                    spriteShiftLo[i] = (spriteShiftLo[i] << 1) & 0xFF;
                    spriteShiftHi[i] = (spriteShiftHi[i] << 1) & 0xFF;
                }
            }
        }
    }

    // ─── Main step ──────────────────────────────────────────────────────────────

    public void step() {
        if (scanline >= 0 && scanline < 240 || scanline == 261) {
            // Pre-render line
            if (scanline == 261 && cycle == 1) {
                status &= ~0x80; // clear vblank
                status &= ~0x40; // clear sprite0
                status &= ~0x20; // clear overflow
                for (int i = 0; i < 8; i++) {
                    spriteShiftLo[i] = 0;
                    spriteShiftHi[i] = 0;
                }
            }

            if ((cycle >= 2 && cycle < 258) || (cycle >= 321 && cycle < 338)) {
                updateShifters();
                switch ((cycle - 1) & 7) {
                    case 0:
                        loadBackgroundShifters();
                        bgNextTileId = ppuRead(0x2000 | (v & 0x0FFF));
                        break;
                    case 2:
                        bgNextTileAttr = ppuRead(0x23C0 | (v & 0x0C00)
                                | ((v >> 4) & 0x38) | ((v >> 2) & 0x07));
                        if ((((v & 0x03E0) >> 5) & 0x02) != 0) bgNextTileAttr >>= 4;
                        if ((v & 0x02) != 0) bgNextTileAttr >>= 2;
                        bgNextTileAttr &= 0x03;
                        break;
                    case 4: {
                        int base = ((ctrl & 0x10) != 0 ? 0x1000 : 0x0000);
                        bgNextTileLsb = ppuRead(base + bgNextTileId * 16 + ((v >> 12) & 7));
                        break;
                    }
                    case 6: {
                        int base = ((ctrl & 0x10) != 0 ? 0x1000 : 0x0000);
                        bgNextTileMsb = ppuRead(base + bgNextTileId * 16 + ((v >> 12) & 7) + 8);
                        break;
                    }
                    case 7:
                        incrementScrollX();
                        break;
                    default:
                        break;
                }
            }

            if (cycle == 256) {
                incrementScrollY();
            }
            if (cycle == 257) {
                loadBackgroundShifters();
                transferAddressX();
            }
            if (cycle == 338 || cycle == 340) {
                bgNextTileId = ppuRead(0x2000 | (v & 0x0FFF));
            }
            if (scanline == 261 && cycle >= 280 && cycle < 305) {
                transferAddressY();
            }

            // Sprite evaluation for the next scanline (do it at end of visible line)
            if (cycle == 257 && scanline >= 0) {
                evaluateSprites();
            }
        }

        if (scanline == 241 && cycle == 1) {
            status |= 0x80; // set vblank
            if ((ctrl & 0x80) != 0) {
                nmiOutput = true;
            }
            frameComplete = true;
        }

        // Compose a pixel
        if (scanline >= 0 && scanline < 240 && cycle >= 1 && cycle <= 256) {
            renderPixel();
        }

        // Advance
        cycle++;
        if (cycle > 340) {
            cycle = 0;
            scanline++;
            if (scanline > 261) {
                scanline = 0;
                oddFrame = !oddFrame;
            }
        }
    }

    private void renderPixel() {
        int bgPixel = 0;
        int bgPalette = 0;
        if ((mask & 0x08) != 0) {
            int bitMux = 0x8000 >> x;
            int p0 = (bgShifterPatternLo & bitMux) != 0 ? 1 : 0;
            int p1 = (bgShifterPatternHi & bitMux) != 0 ? 1 : 0;
            bgPixel = (p1 << 1) | p0;
            int a0 = (bgShifterAttribLo & bitMux) != 0 ? 1 : 0;
            int a1 = (bgShifterAttribHi & bitMux) != 0 ? 1 : 0;
            bgPalette = (a1 << 1) | a0;
            if ((mask & 0x02) == 0 && (cycle - 1) < 8) {
                bgPixel = 0; // left column clip for background
            }
        }

        int fgPixel = 0;
        int fgPalette = 0;
        boolean fgPriority = false;
        spriteZeroBeingRendered = false;
        if ((mask & 0x10) != 0) {
            for (int i = 0; i < spriteCount; i++) {
                if (spriteX[i] == 0) {
                    int lo = (spriteShiftLo[i] & 0x80) != 0 ? 1 : 0;
                    int hi = (spriteShiftHi[i] & 0x80) != 0 ? 1 : 0;
                    int px = (hi << 1) | lo;
                    if (px != 0) {
                        if ((mask & 0x04) == 0 && (cycle - 1) < 8) {
                            // left column clip for sprites
                        } else {
                            fgPixel = px;
                            fgPalette = (spriteAttr[i] & 0x03) + 0x04;
                            fgPriority = (spriteAttr[i] & 0x20) == 0;
                            if (i == 0) {
                                spriteZeroBeingRendered = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        int pixel;
        int pal;
        if (bgPixel == 0 && fgPixel == 0) {
            pixel = 0;
            pal = 0;
        } else if (bgPixel == 0) {
            pixel = fgPixel;
            pal = fgPalette;
        } else if (fgPixel == 0) {
            pixel = bgPixel;
            pal = bgPalette;
        } else {
            if (fgPriority) {
                pixel = fgPixel;
                pal = fgPalette;
            } else {
                pixel = bgPixel;
                pal = bgPalette;
            }
            // Sprite zero hit
            if (spriteZeroHitPossible && spriteZeroBeingRendered
                    && (mask & 0x08) != 0 && (mask & 0x10) != 0) {
                int cx = cycle - 1;
                boolean leftClip = ((mask & 0x02) == 0 || (mask & 0x04) == 0);
                if (!(leftClip && cx < 8) && cx != 255) {
                    status |= 0x40;
                }
            }
        }

        int colorIndex = ppuRead(0x3F00 + (pal << 2) + pixel) & 0x3F;
        int rgb = NTSC_PALETTE[colorIndex];
        int px = cycle - 1;
        framebuffer[scanline * WIDTH + px] = 0xFF000000 | rgb;
    }

    private void evaluateSprites() {
        for (int i = 0; i < spriteScanline.length; i++) {
            spriteScanline[i] = 0xFF;
        }
        spriteCount = 0;
        spriteZeroHitPossible = false;
        int spriteHeight = (ctrl & 0x20) != 0 ? 16 : 8;

        for (int n = 0; n < 64 && spriteCount < 9; n++) {
            int yTop = oam[n * 4];
            int diff = scanline - yTop;
            if (diff >= 0 && diff < spriteHeight) {
                if (spriteCount < 8) {
                    if (n == 0) {
                        spriteZeroHitPossible = true;
                    }
                    spriteScanline[spriteCount * 4] = oam[n * 4];
                    spriteScanline[spriteCount * 4 + 1] = oam[n * 4 + 1];
                    spriteScanline[spriteCount * 4 + 2] = oam[n * 4 + 2];
                    spriteScanline[spriteCount * 4 + 3] = oam[n * 4 + 3];
                }
                spriteCount++;
            }
        }
        if (spriteCount > 8) {
            spriteCount = 8;
            status |= 0x20; // overflow (approximate)
        }

        // Fetch sprite patterns for the next line
        int spriteHeight2 = spriteHeight;
        for (int i = 0; i < spriteCount; i++) {
            int y = spriteScanline[i * 4];
            int tile = spriteScanline[i * 4 + 1];
            int attr = spriteScanline[i * 4 + 2];
            int sx = spriteScanline[i * 4 + 3];

            int row = scanline - y;
            boolean flipV = (attr & 0x80) != 0;
            boolean flipH = (attr & 0x40) != 0;
            if (flipV) {
                row = spriteHeight2 - 1 - row;
            }

            int addr;
            if (spriteHeight2 == 8) {
                int base = (ctrl & 0x08) != 0 ? 0x1000 : 0x0000;
                addr = base + tile * 16 + row;
            } else {
                int base = (tile & 1) * 0x1000;
                int t2 = tile & 0xFE;
                if (row >= 8) {
                    t2 += 1;
                    row -= 8;
                }
                addr = base + t2 * 16 + row;
            }

            int lo = ppuRead(addr);
            int hi = ppuRead(addr + 8);
            if (flipH) {
                lo = reverseByte(lo);
                hi = reverseByte(hi);
            }
            spriteShiftLo[i] = lo;
            spriteShiftHi[i] = hi;
            spriteX[i] = sx;
            spriteAttr[i] = attr;
        }
    }

    private static int reverseByte(int b) {
        b = ((b & 0xF0) >> 4) | ((b & 0x0F) << 4);
        b = ((b & 0xCC) >> 2) | ((b & 0x33) << 2);
        b = ((b & 0xAA) >> 1) | ((b & 0x55) << 1);
        return b & 0xFF;
    }

    public void reset() {
        ctrl = 0;
        mask = 0;
        status = 0;
        v = t = x = 0;
        w = false;
        scanline = 0;
        cycle = 0;
        oddFrame = false;
    }

    // ─── NTSC palette (2C02), 64 entries of 0xRRGGBB ───────────────────────────
    private static final int[] NTSC_PALETTE = {
            0x626262, 0x001FB2, 0x2404C8, 0x5200B2, 0x730076, 0x800024, 0x730B00, 0x522800,
            0x244400, 0x005700, 0x005C00, 0x005324, 0x003C76, 0x000000, 0x000000, 0x000000,
            0xABABAB, 0x0D57FF, 0x4B30FF, 0x8A13FF, 0xBC08D6, 0xD21269, 0xC72E00, 0x9D5400,
            0x607B00, 0x209800, 0x00A300, 0x009942, 0x007DB4, 0x000000, 0x000000, 0x000000,
            0xFFFFFF, 0x53AEFF, 0x9085FF, 0xD365FF, 0xFF57FF, 0xFF5DCF, 0xFF7757, 0xFA9E00,
            0xBDC700, 0x7AE700, 0x43F611, 0x26EF7E, 0x2CD5F6, 0x4E4E4E, 0x000000, 0x000000,
            0xFFFFFF, 0xB6E1FF, 0xCED1FF, 0xE9C3FF, 0xFFBCFF, 0xFFBDF4, 0xFFC6C3, 0xFFD59A,
            0xE9E681, 0xCEF481, 0xB6FB9A, 0xA9FAC3, 0xA9F0F4, 0xB8B8B8, 0x000000, 0x000000,
    };
}
