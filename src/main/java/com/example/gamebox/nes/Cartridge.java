package com.example.gamebox.nes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An iNES cartridge limited to <b>mapper 0 (NROM)</b> — which is exactly what
 * Super Mario Bros. 1 uses. Parses the 16-byte iNES header, loads the PRG-ROM
 * (16 KB or 32 KB) and CHR-ROM (8 KB, or 8 KB of CHR-RAM if the cart has none),
 * and exposes nametable mirroring.
 *
 * <h2>iNES header</h2>
 * <pre>
 *   0-3  "NES\x1A"
 *   4    PRG-ROM size in 16 KB units
 *   5    CHR-ROM size in 8 KB units (0 => 8 KB CHR-RAM)
 *   6    flags6: bit0 mirroring (0=horizontal,1=vertical), bit3 four-screen,
 *               bits4-7 mapper low nibble
 *   7    flags7: bits4-7 mapper high nibble
 * </pre>
 */
public final class Cartridge {

    public enum Mirroring { HORIZONTAL, VERTICAL, FOUR_SCREEN }

    private static final int PRG_BANK = 16 * 1024;
    private static final int CHR_BANK = 8 * 1024;

    private final byte[] prg;   // 16 KB or 32 KB
    private final byte[] chr;   // 8 KB (ROM or RAM)
    private final boolean chrIsRam;
    private final Mirroring mirroring;
    private final int mapper;

    private Cartridge(byte[] prg, byte[] chr, boolean chrIsRam, Mirroring mirroring, int mapper) {
        this.prg = prg;
        this.chr = chr;
        this.chrIsRam = chrIsRam;
        this.mirroring = mirroring;
        this.mapper = mapper;
    }

    public static Cartridge fromFile(Path path) throws IOException {
        return fromBytes(Files.readAllBytes(path));
    }

    public static Cartridge fromBytes(byte[] rom) {
        if (rom.length < 16
                || rom[0] != 'N' || rom[1] != 'E' || rom[2] != 'S' || (rom[3] & 0xFF) != 0x1A) {
            throw new IllegalArgumentException("Not an iNES file (bad magic)");
        }
        int prgBanks = rom[4] & 0xFF;
        int chrBanks = rom[5] & 0xFF;
        int flags6 = rom[6] & 0xFF;
        int flags7 = rom[7] & 0xFF;

        int mapper = (flags7 & 0xF0) | ((flags6 & 0xF0) >> 4);
        if (mapper != 0) {
            throw new IllegalArgumentException("Only mapper 0 (NROM) is supported; got mapper " + mapper);
        }

        boolean trainer = (flags6 & 0x04) != 0;
        Mirroring mirroring;
        if ((flags6 & 0x08) != 0) {
            mirroring = Mirroring.FOUR_SCREEN;
        } else {
            mirroring = (flags6 & 0x01) != 0 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
        }

        int offset = 16 + (trainer ? 512 : 0);

        int prgSize = prgBanks * PRG_BANK;
        byte[] prg = new byte[prgSize];
        System.arraycopy(rom, offset, prg, 0, Math.min(prgSize, rom.length - offset));
        offset += prgSize;

        byte[] chr;
        boolean chrIsRam;
        if (chrBanks == 0) {
            chr = new byte[CHR_BANK];
            chrIsRam = true;
        } else {
            int chrSize = chrBanks * CHR_BANK;
            chr = new byte[chrSize];
            System.arraycopy(rom, offset, chr, 0, Math.min(chrSize, rom.length - offset));
            chrIsRam = false;
        }

        return new Cartridge(prg, chr, chrIsRam, mirroring, mapper);
    }

    // ─── CPU address space $8000-$FFFF ─────────────────────────────────────────

    /** Reads PRG-ROM for a CPU address in $8000-$FFFF (NROM: 16 KB mirrored, or 32 KB). */
    public int cpuRead(int addr) {
        int a = addr - 0x8000;
        if (prg.length == PRG_BANK) {
            a &= 0x3FFF; // mirror the single 16 KB bank into both halves
        } else {
            a &= 0x7FFF;
        }
        return prg[a] & 0xFF;
    }

    public void cpuWrite(int addr, int value) {
        // NROM has no PRG-RAM write mapping (SMB1 does not need it).
    }

    // ─── PPU pattern tables $0000-$1FFF ────────────────────────────────────────

    public int ppuRead(int addr) {
        return chr[addr & (chr.length - 1)] & 0xFF;
    }

    public void ppuWrite(int addr, int value) {
        if (chrIsRam) {
            chr[addr & (chr.length - 1)] = (byte) value;
        }
    }

    public Mirroring mirroring() {
        return mirroring;
    }

    public int mapper() {
        return mapper;
    }

    public int prgSize() {
        return prg.length;
    }

    public int chrSize() {
        return chr.length;
    }
}
