package com.example.gamebox.gb;

/**
 * The Game Boy (DMG) Picture Processing Unit. Owns VRAM (8 KB) and OAM (160 B),
 * the LCD control/status registers, and renders a <b>160×144</b> frame into an
 * ARGB {@code int[]} one scanline at a time. The scanline is produced at the
 * start of mode-3 for simplicity (DMG games that mid-scanline-trick aside, this
 * is correct for the vast majority of titles).
 *
 * <h2>Timing</h2>
 * A frame is 154 lines × 456 T-cycles = 70,224 T-cycles. Per visible line the
 * mode sequence is OAM-scan (mode 2, 80) → pixel-transfer (mode 3, 172) →
 * H-Blank (mode 0, 204). Lines 144–153 are V-Blank (mode 1). The PPU raises the
 * V-Blank interrupt at line 144 and the STAT interrupt on its enabled sources
 * (LYC=LY and mode changes).
 *
 * <p>The owning {@link GbBus} calls {@link #step(int)} with the T-cycles just
 * consumed by the CPU and relays interrupt requests via
 * {@link GbBus#requestInterrupt(int)}.
 */
public final class GbPpu {

    public static final int WIDTH = 160;
    public static final int HEIGHT = 144;

    private static final int OAM_CYCLES = 80;
    private static final int TRANSFER_CYCLES = 172;
    private static final int LINE_CYCLES = 456;
    private static final int VBLANK_LINE = 144;
    private static final int TOTAL_LINES = 154;

    /** Classic 4-shade DMG green palette, light→dark, as ARGB. */
    private static final int[] SHADES = {
            0xFF9BBC0F, // lightest
            0xFF8BAC0F,
            0xFF306230,
            0xFF0F380F, // darkest
    };

    private final GbBus bus;

    private final int[] vram = new int[0x2000]; // 0x8000-0x9FFF
    private final int[] oam = new int[0xA0];     // 0xFE00-0xFE9F

    // LCD registers.
    private int lcdc;     // 0xFF40
    private int stat;     // 0xFF41
    private int scy, scx; // 0xFF42, 0xFF43
    private int ly;       // 0xFF44
    private int lyc;      // 0xFF45
    private int bgp;      // 0xFF47
    private int obp0;     // 0xFF48
    private int obp1;     // 0xFF49
    private int wy, wx;   // 0xFF4A, 0xFF4B

    private int modeClock;
    private int mode;     // 0 HBlank, 1 VBlank, 2 OAM, 3 transfer
    private int windowLine; // internal window line counter

    private final int[] framebuffer = new int[WIDTH * HEIGHT];
    private volatile boolean frameReady;

    public GbPpu(GbBus bus) {
        this.bus = bus;
        reset();
    }

    public void reset() {
        lcdc = 0x91;
        stat = 0x85;
        scy = scx = 0;
        ly = 0;
        lyc = 0;
        bgp = 0xFC;
        obp0 = 0xFF;
        obp1 = 0xFF;
        wy = wx = 0;
        modeClock = 0;
        mode = 2;
        windowLine = 0;
        frameReady = false;
        java.util.Arrays.fill(framebuffer, SHADES[0]);
    }

    // ─── VRAM / OAM access ──────────────────────────────────────────────────

    public int readVram(int addr) {
        return vram[(addr - 0x8000) & 0x1FFF] & 0xFF;
    }

    public void writeVram(int addr, int v) {
        vram[(addr - 0x8000) & 0x1FFF] = v & 0xFF;
    }

    public int readOam(int addr) {
        int i = (addr - 0xFE00) & 0xFF;
        return (i < oam.length) ? (oam[i] & 0xFF) : 0xFF;
    }

    public void writeOam(int addr, int v) {
        int i = (addr - 0xFE00) & 0xFF;
        if (i < oam.length) {
            oam[i] = v & 0xFF;
        }
    }

    /** Used by the 0xFF46 OAM DMA path on the bus. */
    public void oamDmaWrite(int index, int v) {
        if (index >= 0 && index < oam.length) {
            oam[index] = v & 0xFF;
        }
    }

    // ─── Register access ──────────────────────────────────────────────────────

    public int readReg(int addr) {
        switch (addr) {
            case 0xFF40: return lcdc;
            case 0xFF41: return (stat & 0xF8) | (mode & 3) | (ly == lyc ? 0x04 : 0) | 0x80;
            case 0xFF42: return scy;
            case 0xFF43: return scx;
            case 0xFF44: return ly;
            case 0xFF45: return lyc;
            case 0xFF47: return bgp;
            case 0xFF48: return obp0;
            case 0xFF49: return obp1;
            case 0xFF4A: return wy;
            case 0xFF4B: return wx;
            default: return 0xFF;
        }
    }

    public void writeReg(int addr, int v) {
        v &= 0xFF;
        switch (addr) {
            case 0xFF40:
                boolean wasOn = (lcdc & 0x80) != 0;
                lcdc = v;
                if (wasOn && (lcdc & 0x80) == 0) {
                    // LCD turned off: reset to line 0, mode 0.
                    ly = 0;
                    modeClock = 0;
                    mode = 0;
                    windowLine = 0;
                } else if (!wasOn && (lcdc & 0x80) != 0) {
                    // LCD turned on.
                    modeClock = 0;
                    mode = 2;
                    ly = 0;
                    windowLine = 0;
                }
                break;
            case 0xFF41: stat = (v & 0x78) | (stat & 0x07); break;
            case 0xFF42: scy = v; break;
            case 0xFF43: scx = v; break;
            case 0xFF44: ly = 0; break; // writing LY resets it
            case 0xFF45: lyc = v; checkLyc(); break;
            case 0xFF47: bgp = v; break;
            case 0xFF48: obp0 = v; break;
            case 0xFF49: obp1 = v; break;
            case 0xFF4A: wy = v; break;
            case 0xFF4B: wx = v; break;
            default: break;
        }
    }

    private boolean lcdOn() {
        return (lcdc & 0x80) != 0;
    }

    // ─── Clock / state machine ────────────────────────────────────────────────

    /** Advance the PPU by {@code tCycles} CPU T-cycles. */
    public void step(int tCycles) {
        if (!lcdOn()) {
            return;
        }
        modeClock += tCycles;

        switch (mode) {
            case 2: // OAM scan
                if (modeClock >= OAM_CYCLES) {
                    modeClock -= OAM_CYCLES;
                    mode = 3;
                }
                break;
            case 3: // pixel transfer
                if (modeClock >= TRANSFER_CYCLES) {
                    modeClock -= TRANSFER_CYCLES;
                    mode = 0;
                    renderScanline(ly);
                    if ((stat & 0x08) != 0) {
                        bus.requestInterrupt(1); // STAT: HBlank
                    }
                }
                break;
            case 0: // HBlank
                if (modeClock >= (LINE_CYCLES - OAM_CYCLES - TRANSFER_CYCLES)) {
                    modeClock -= (LINE_CYCLES - OAM_CYCLES - TRANSFER_CYCLES);
                    ly++;
                    checkLyc();
                    if (ly == VBLANK_LINE) {
                        mode = 1;
                        frameReady = true;
                        bus.requestInterrupt(0); // VBlank
                        if ((stat & 0x10) != 0) {
                            bus.requestInterrupt(1); // STAT: VBlank (mode 1)
                        }
                    } else {
                        mode = 2;
                        if ((stat & 0x20) != 0) {
                            bus.requestInterrupt(1); // STAT: OAM
                        }
                    }
                }
                break;
            case 1: // VBlank
                if (modeClock >= LINE_CYCLES) {
                    modeClock -= LINE_CYCLES;
                    ly++;
                    if (ly > TOTAL_LINES - 1) {
                        ly = 0;
                        mode = 2;
                        windowLine = 0;
                        if ((stat & 0x20) != 0) {
                            bus.requestInterrupt(1); // STAT: OAM at frame start
                        }
                    }
                    checkLyc();
                }
                break;
            default:
                break;
        }
    }

    private void checkLyc() {
        if (ly == lyc && (stat & 0x40) != 0) {
            bus.requestInterrupt(1); // STAT: LYC=LY
        }
    }

    // ─── Rendering ─────────────────────────────────────────────────────────────

    private void renderScanline(int line) {
        if (line < 0 || line >= HEIGHT) {
            return;
        }
        // Shade indices for this line (used for sprite BG-priority checks).
        int[] bgIndex = new int[WIDTH];

        if ((lcdc & 0x01) != 0) {
            renderBackground(line, bgIndex);
            if ((lcdc & 0x20) != 0) {
                renderWindow(line, bgIndex);
            }
        } else {
            // BG off: blank to lightest shade.
            for (int x = 0; x < WIDTH; x++) {
                framebuffer[line * WIDTH + x] = SHADES[0];
                bgIndex[x] = 0;
            }
        }

        if ((lcdc & 0x02) != 0) {
            renderSprites(line, bgIndex);
        }
    }

    private void renderBackground(int line, int[] bgIndex) {
        int tileMapBase = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800; // 0x9C00 / 0x9800
        boolean signed = (lcdc & 0x10) == 0;                     // tile data area
        int tileDataBase = signed ? 0x0800 : 0x0000;             // relative to VRAM
        int y = (line + scy) & 0xFF;
        int tileRow = (y >> 3) & 0x1F;

        for (int x = 0; x < WIDTH; x++) {
            int bgx = (x + scx) & 0xFF;
            int tileCol = (bgx >> 3) & 0x1F;
            int tileIndex = vram[(tileMapBase + tileRow * 32 + tileCol) & 0x1FFF] & 0xFF;

            int tileAddr;
            if (signed) {
                int signedIndex = (byte) tileIndex;
                tileAddr = tileDataBase + (signedIndex + 128) * 16;
            } else {
                tileAddr = tileDataBase + tileIndex * 16;
            }
            int lineInTile = y & 7;
            int lo = vram[(tileAddr + lineInTile * 2) & 0x1FFF] & 0xFF;
            int hi = vram[(tileAddr + lineInTile * 2 + 1) & 0x1FFF] & 0xFF;
            int bit = 7 - (bgx & 7);
            int colorId = (((hi >> bit) & 1) << 1) | ((lo >> bit) & 1);

            bgIndex[x] = colorId;
            framebuffer[line * WIDTH + x] = SHADES[(bgp >> (colorId * 2)) & 3];
        }
    }

    private void renderWindow(int line, int[] bgIndex) {
        if (line < wy) {
            return;
        }
        int winX = wx - 7;
        if (winX >= WIDTH) {
            return;
        }
        int tileMapBase = (lcdc & 0x40) != 0 ? 0x1C00 : 0x1800;
        boolean signed = (lcdc & 0x10) == 0;
        int tileDataBase = signed ? 0x0800 : 0x0000;

        int y = windowLine;
        int tileRow = (y >> 3) & 0x1F;
        boolean drewAny = false;

        for (int x = Math.max(0, winX); x < WIDTH; x++) {
            int wx0 = x - winX;
            if (wx0 < 0) {
                continue;
            }
            drewAny = true;
            int tileCol = (wx0 >> 3) & 0x1F;
            int tileIndex = vram[(tileMapBase + tileRow * 32 + tileCol) & 0x1FFF] & 0xFF;
            int tileAddr;
            if (signed) {
                tileAddr = tileDataBase + (((byte) tileIndex) + 128) * 16;
            } else {
                tileAddr = tileDataBase + tileIndex * 16;
            }
            int lineInTile = y & 7;
            int lo = vram[(tileAddr + lineInTile * 2) & 0x1FFF] & 0xFF;
            int hi = vram[(tileAddr + lineInTile * 2 + 1) & 0x1FFF] & 0xFF;
            int bit = 7 - (wx0 & 7);
            int colorId = (((hi >> bit) & 1) << 1) | ((lo >> bit) & 1);
            bgIndex[x] = colorId;
            framebuffer[line * WIDTH + x] = SHADES[(bgp >> (colorId * 2)) & 3];
        }
        if (drewAny) {
            windowLine++;
        }
    }

    private void renderSprites(int line, int[] bgIndex) {
        int spriteHeight = (lcdc & 0x04) != 0 ? 16 : 8;
        int drawn = 0;

        // DMG: up to 10 sprites per line, OAM order; lower X has priority. We
        // iterate OAM forward and let earlier (lower index) sprites win ties by
        // only drawing a pixel if it hasn't been claimed by a higher-priority
        // sprite already (tracked via a per-line owner X array).
        int[] ownerX = new int[WIDTH];
        java.util.Arrays.fill(ownerX, 0x7FFF);

        for (int i = 0; i < 40 && drawn < 10; i++) {
            int base = i * 4;
            int spriteY = (oam[base] & 0xFF) - 16;
            int spriteX = (oam[base + 1] & 0xFF) - 8;
            int tile = oam[base + 2] & 0xFF;
            int attr = oam[base + 3] & 0xFF;

            if (line < spriteY || line >= spriteY + spriteHeight) {
                continue;
            }
            drawn++;

            boolean flipY = (attr & 0x40) != 0;
            boolean flipX = (attr & 0x20) != 0;
            boolean behindBg = (attr & 0x80) != 0;
            int palette = (attr & 0x10) != 0 ? obp1 : obp0;

            int row = line - spriteY;
            if (flipY) {
                row = spriteHeight - 1 - row;
            }
            int tileIndex = tile;
            if (spriteHeight == 16) {
                tileIndex &= 0xFE;
            }
            int tileAddr = tileIndex * 16 + row * 2;
            int lo = vram[tileAddr & 0x1FFF] & 0xFF;
            int hi = vram[(tileAddr + 1) & 0x1FFF] & 0xFF;

            for (int px = 0; px < 8; px++) {
                int sx = spriteX + px;
                if (sx < 0 || sx >= WIDTH) {
                    continue;
                }
                int bit = flipX ? px : (7 - px);
                int colorId = (((hi >> bit) & 1) << 1) | ((lo >> bit) & 1);
                if (colorId == 0) {
                    continue; // transparent
                }
                // Priority: a lower sprite-X wins. If this sprite's X is not
                // better than the current owner, skip.
                if (spriteX >= ownerX[sx]) {
                    continue;
                }
                if (behindBg && bgIndex[sx] != 0) {
                    // Sprite is behind non-zero BG — but still claim ownership so
                    // a worse sprite can't overwrite.
                    ownerX[sx] = spriteX;
                    continue;
                }
                ownerX[sx] = spriteX;
                framebuffer[line * WIDTH + sx] = SHADES[(palette >> (colorId * 2)) & 3];
            }
        }
    }

    // ─── Output ────────────────────────────────────────────────────────────────

    public int[] framebuffer() {
        return framebuffer;
    }

    public boolean frameReady() {
        return frameReady;
    }

    public void clearFrameReady() {
        frameReady = false;
    }

    public int mode() {
        return mode;
    }
}
