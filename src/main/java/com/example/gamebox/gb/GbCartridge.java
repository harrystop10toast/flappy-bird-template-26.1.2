package com.example.gamebox.gb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Game Boy (DMG) cartridge: parses the 0x150-byte header and implements the
 * common memory bank controllers — <b>ROM-only</b>, <b>MBC1</b>, <b>MBC3</b> and
 * <b>MBC5</b> — which between them cover the large majority of commercial Game
 * Boy / Game Boy Color titles.
 *
 * <h2>Design priority: never crash the client</h2>
 * Every ROM/RAM access is masked to the backing array size, so a malformed ROM
 * or an out-of-range bank can never throw {@link ArrayIndexOutOfBoundsException}.
 * An unknown cartridge type is rejected up-front with a clear message (see
 * {@link #fromBytes}) rather than mis-executing. Real-time-clock registers
 * (MBC3) are accepted and read back as plausible values but not advanced — games
 * tolerate this.
 *
 * <h2>Header (little-endian)</h2>
 * <pre>
 *   0x0134-0x0143  title
 *   0x0147         cartridge type (mapper + features)
 *   0x0148         ROM size code  (0 =>  32 KB … n => 32 KB &lt;&lt; n)
 *   0x0149         RAM size code  (0 => none, 2 => 8 KB, 3 => 32 KB, 4 => 128 KB, 5 => 64 KB)
 * </pre>
 */
public final class GbCartridge {

    /** Which bank controller a cartridge uses. */
    public enum Mapper { ROM_ONLY, MBC1, MBC2, MBC3, MBC5 }

    private static final int ROM_BANK_SIZE = 0x4000; // 16 KB
    private static final int RAM_BANK_SIZE = 0x2000; // 8 KB

    private final byte[] rom;
    private final byte[] ram;
    private final Mapper mapper;
    private final boolean hasBattery;
    private final String title;
    private final int cartType;

    // ─── Banking state ────────────────────────────────────────────────────────
    private boolean ramEnabled;
    private int romBankLow = 1;      // MBC1/3 low bank select (and MBC5 low 8 bits)
    private int romBankHigh;         // MBC1 upper 2 bits / MBC5 bit 8
    private int ramBank;             // RAM bank or MBC3 RTC register select
    private boolean mode1;           // MBC1 banking mode (0 = ROM, 1 = RAM/upper-ROM)

    private GbCartridge(byte[] rom, byte[] ram, Mapper mapper, boolean hasBattery,
                        String title, int cartType) {
        this.rom = rom;
        this.ram = ram;
        this.mapper = mapper;
        this.hasBattery = hasBattery;
        this.title = title;
        this.cartType = cartType;
    }

    public static GbCartridge fromFile(Path path) throws IOException {
        return fromBytes(Files.readAllBytes(path));
    }

    /**
     * Parses a ROM image. Throws {@link IllegalArgumentException} with a readable
     * message for anything we can't run, so the caller can surface it as a status
     * line instead of letting an exception escape to Minecraft.
     */
    public static GbCartridge fromBytes(byte[] data) {
        if (data == null || data.length < 0x150) {
            throw new IllegalArgumentException("Not a Game Boy ROM (too small: "
                    + (data == null ? 0 : data.length) + " bytes)");
        }

        int cartType = data[0x0147] & 0xFF;
        int romCode = data[0x0148] & 0xFF;
        int ramCode = data[0x0149] & 0xFF;

        // Title: 0x0134..0x0143, NUL-padded ASCII.
        StringBuilder sb = new StringBuilder();
        for (int i = 0x0134; i <= 0x0143 && i < data.length; i++) {
            int c = data[i] & 0xFF;
            if (c == 0) {
                break;
            }
            if (c >= 0x20 && c < 0x7F) {
                sb.append((char) c);
            }
        }
        String title = sb.toString().trim();

        Mapper mapper = mapperFor(cartType);
        if (mapper == null) {
            throw new IllegalArgumentException(String.format(
                    "Unsupported Game Boy cartridge type 0x%02X — GameBox supports "
                            + "ROM-only, MBC1, MBC3 and MBC5.", cartType));
        }
        boolean battery = cartTypeHasBattery(cartType);

        // ROM size: header code; fall back to the actual file length if the code
        // looks wrong, and never trust it past the data we actually have.
        int headerRomSize = (0x8000 << romCode);
        int romSize = headerRomSize;
        if (romSize <= 0 || romSize > (8 << 20)) {
            romSize = Integer.highestOneBit(Math.max(data.length - 1, ROM_BANK_SIZE)) << 1;
        }
        romSize = Math.max(romSize, data.length);
        // Round up to a whole number of 16 KB banks.
        if (romSize % ROM_BANK_SIZE != 0) {
            romSize += ROM_BANK_SIZE - (romSize % ROM_BANK_SIZE);
        }
        byte[] rom = new byte[romSize];
        System.arraycopy(data, 0, rom, 0, Math.min(data.length, romSize));

        int ramSize = ramSizeFor(ramCode);
        if (mapper == Mapper.MBC2) {
            ramSize = 512; // MBC2 has 512 x 4-bit built-in RAM
        }
        byte[] ram = new byte[Math.max(ramSize, 0)];

        return new GbCartridge(rom, ram, mapper, battery, title, cartType);
    }

    private static Mapper mapperFor(int type) {
        switch (type) {
            case 0x00: // ROM ONLY
            case 0x08: // ROM+RAM
            case 0x09: // ROM+RAM+BATTERY
                return Mapper.ROM_ONLY;
            case 0x01: case 0x02: case 0x03: // MBC1 variants
                return Mapper.MBC1;
            case 0x05: case 0x06: // MBC2 variants
                return Mapper.MBC2;
            case 0x0F: case 0x10: case 0x11: case 0x12: case 0x13: // MBC3 variants
                return Mapper.MBC3;
            case 0x19: case 0x1A: case 0x1B: // MBC5
            case 0x1C: case 0x1D: case 0x1E: // MBC5 + rumble
                return Mapper.MBC5;
            default:
                return null;
        }
    }

    private static boolean cartTypeHasBattery(int type) {
        switch (type) {
            case 0x03: case 0x06: case 0x09: case 0x0D:
            case 0x0F: case 0x10: case 0x13: case 0x1B: case 0x1E:
                return true;
            default:
                return false;
        }
    }

    private static int ramSizeFor(int code) {
        switch (code) {
            case 0x00: return 0;
            case 0x01: return 2 * 1024;    // unofficial, treat as 2 KB
            case 0x02: return 8 * 1024;
            case 0x03: return 32 * 1024;   // 4 banks
            case 0x04: return 128 * 1024;  // 16 banks
            case 0x05: return 64 * 1024;   // 8 banks
            default:   return 8 * 1024;    // be lenient
        }
    }

    // ─── Reads ──────────────────────────────────────────────────────────────

    /** CPU read of 0x0000-0x7FFF (ROM). */
    public int readRom(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x4000) {
            // Bank 0 region. MBC1 in mode 1 can map a high bank here, but 0 is
            // correct for the common case and for all other mappers.
            int bank = 0;
            if (mapper == Mapper.MBC1 && mode1) {
                bank = (romBankHigh << 5) & bankMask();
            }
            return rom[((bank * ROM_BANK_SIZE) + addr) & (rom.length - 1)] & 0xFF;
        }
        // Switchable bank region 0x4000-0x7FFF.
        int bank = currentRomBank();
        int offset = (bank * ROM_BANK_SIZE) + (addr - 0x4000);
        return rom[offset & (rom.length - 1)] & 0xFF;
    }

    private int currentRomBank() {
        switch (mapper) {
            case ROM_ONLY:
                return 1;
            case MBC1: {
                int bank = (romBankLow & 0x1F);
                if (bank == 0) {
                    bank = 1; // MBC1 maps bank 0 request to 1 in the switch region
                }
                if (!mode1) {
                    bank |= (romBankHigh << 5);
                }
                return bank & bankMask();
            }
            case MBC2:
            case MBC3: {
                int bank = romBankLow & 0x7F;
                if (bank == 0) {
                    bank = 1;
                }
                return bank & bankMask();
            }
            case MBC5: {
                int bank = (romBankLow & 0xFF) | ((romBankHigh & 1) << 8);
                return bank & bankMask();
            }
            default:
                return 1;
        }
    }

    /** A mask covering the number of 16 KB banks present (power-of-two array). */
    private int bankMask() {
        int banks = rom.length / ROM_BANK_SIZE;
        if (banks <= 1) {
            return 0;
        }
        // rom.length is a power of two, so banks is too; mask = banks-1.
        return banks - 1;
    }

    /** CPU read of 0xA000-0xBFFF (external cartridge RAM). */
    public int readRam(int addr) {
        if (!ramEnabled || ram.length == 0) {
            return 0xFF;
        }
        if (mapper == Mapper.MBC3 && ramBank >= 0x08) {
            return 0x00; // RTC register read-back (not advanced) — tolerated
        }
        int offset = ramOffset(addr);
        if (offset < 0 || offset >= ram.length) {
            return 0xFF;
        }
        int v = ram[offset] & 0xFF;
        if (mapper == Mapper.MBC2) {
            v |= 0xF0; // MBC2 RAM is 4-bit; upper nibble reads as 1s
        }
        return v;
    }

    private int ramOffset(int addr) {
        int local = (addr - 0xA000) & 0x1FFF;
        if (mapper == Mapper.MBC2) {
            return local & 0x1FF; // 512 x 4-bit, mirrored
        }
        int bank = 0;
        if (mapper == Mapper.MBC1) {
            bank = mode1 ? (ramBank & 0x03) : 0;
        } else if (mapper == Mapper.MBC3) {
            bank = ramBank & 0x03;
        } else if (mapper == Mapper.MBC5) {
            bank = ramBank & 0x0F;
        }
        return bank * RAM_BANK_SIZE + local;
    }

    // ─── Writes (bank control + RAM) ──────────────────────────────────────────

    /** CPU write to 0x0000-0x7FFF — these are mapper control registers. */
    public void writeRom(int addr, int value) {
        addr &= 0xFFFF;
        value &= 0xFF;
        switch (mapper) {
            case ROM_ONLY:
                return;
            case MBC1:
                writeMbc1(addr, value);
                return;
            case MBC2:
                writeMbc2(addr, value);
                return;
            case MBC3:
                writeMbc3(addr, value);
                return;
            case MBC5:
                writeMbc5(addr, value);
                return;
            default:
                // unreachable
        }
    }

    private void writeMbc1(int addr, int value) {
        if (addr < 0x2000) {
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (addr < 0x4000) {
            romBankLow = (value & 0x1F);
        } else if (addr < 0x6000) {
            romBankHigh = value & 0x03;
            ramBank = value & 0x03;
        } else {
            mode1 = (value & 1) != 0;
        }
    }

    private void writeMbc2(int addr, int value) {
        if (addr < 0x4000) {
            // Bit 8 of address selects between RAM-enable and ROM-bank.
            if ((addr & 0x0100) == 0) {
                ramEnabled = (value & 0x0F) == 0x0A;
            } else {
                int bank = value & 0x0F;
                romBankLow = bank == 0 ? 1 : bank;
            }
        }
    }

    private void writeMbc3(int addr, int value) {
        if (addr < 0x2000) {
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (addr < 0x4000) {
            romBankLow = value & 0x7F;
        } else if (addr < 0x6000) {
            ramBank = value & 0x0F; // 0-3 RAM bank, 0x08-0x0C RTC register
        } else {
            // RTC latch — ignored (clock not advanced).
        }
    }

    private void writeMbc5(int addr, int value) {
        if (addr < 0x2000) {
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (addr < 0x3000) {
            romBankLow = value & 0xFF;
        } else if (addr < 0x4000) {
            romBankHigh = value & 0x01;
        } else if (addr < 0x6000) {
            ramBank = value & 0x0F;
        }
    }

    /** CPU write to 0xA000-0xBFFF (external cartridge RAM). */
    public void writeRam(int addr, int value) {
        if (!ramEnabled || ram.length == 0) {
            return;
        }
        if (mapper == Mapper.MBC3 && ramBank >= 0x08) {
            return; // RTC register write — ignored
        }
        int offset = ramOffset(addr);
        if (offset < 0 || offset >= ram.length) {
            return;
        }
        if (mapper == Mapper.MBC2) {
            ram[offset] = (byte) (value & 0x0F);
        } else {
            ram[offset] = (byte) (value & 0xFF);
        }
    }

    // ─── Info ────────────────────────────────────────────────────────────────

    public Mapper mapper() {
        return mapper;
    }

    public boolean hasBattery() {
        return hasBattery;
    }

    public String title() {
        return title.isEmpty() ? "(untitled)" : title;
    }

    public int cartType() {
        return cartType;
    }

    public int romBanks() {
        return rom.length / ROM_BANK_SIZE;
    }

    public int ramBytes() {
        return ram.length;
    }
}
