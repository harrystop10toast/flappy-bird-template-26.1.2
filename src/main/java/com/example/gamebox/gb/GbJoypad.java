package com.example.gamebox.gb;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Game Boy joypad (register 0xFF00). Eight buttons live in two selectable
 * groups: a <b>direction</b> group (Right/Left/Up/Down) and an <b>action</b>
 * group (A/B/Select/Start). The CPU selects a group by clearing bit 5 (actions)
 * or bit 4 (directions); the low nibble then reads back the pressed state —
 * <b>0 = pressed</b> (active-low), 1 = released.
 *
 * <p>Button state is written from the Minecraft input thread and read on the
 * emulator thread, so it is held in an {@link AtomicInteger}.
 */
public final class GbJoypad {

    public static final int BUTTON_RIGHT = 0;
    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_UP = 2;
    public static final int BUTTON_DOWN = 3;
    public static final int BUTTON_A = 4;
    public static final int BUTTON_B = 5;
    public static final int BUTTON_SELECT = 6;
    public static final int BUTTON_START = 7;

    /** 1 bit per button (see constants); 1 = pressed. */
    private final AtomicInteger buttons = new AtomicInteger(0);

    /** Current group select bits from the last 0xFF00 write (bits 4/5). */
    private volatile int select = 0x30;

    public void setButton(int button, boolean pressed) {
        int mask = 1 << button;
        int cur, next;
        do {
            cur = buttons.get();
            next = pressed ? (cur | mask) : (cur & ~mask);
        } while (!buttons.compareAndSet(cur, next));
    }

    public void clearAll() {
        buttons.set(0);
    }

    /** Whether any button in a group that would raise a joypad interrupt is down. */
    public boolean anyPressed() {
        return buttons.get() != 0;
    }

    /** CPU write to 0xFF00: only the group-select bits (4,5) are writable. */
    public void write(int value) {
        select = value & 0x30;
    }

    /** CPU read of 0xFF00: active-low pressed state for the selected group. */
    public int read() {
        int b = buttons.get();
        int low = 0x0F; // default: nothing pressed (all high)

        if ((select & 0x10) == 0) {
            // Directions selected (bit 4 low).
            int d = 0;
            if ((b & (1 << BUTTON_RIGHT)) != 0) d |= 0x01;
            if ((b & (1 << BUTTON_LEFT)) != 0)  d |= 0x02;
            if ((b & (1 << BUTTON_UP)) != 0)    d |= 0x04;
            if ((b & (1 << BUTTON_DOWN)) != 0)  d |= 0x08;
            low &= ~d;
        }
        if ((select & 0x20) == 0) {
            // Actions selected (bit 5 low).
            int a = 0;
            if ((b & (1 << BUTTON_A)) != 0)      a |= 0x01;
            if ((b & (1 << BUTTON_B)) != 0)      a |= 0x02;
            if ((b & (1 << BUTTON_SELECT)) != 0) a |= 0x04;
            if ((b & (1 << BUTTON_START)) != 0)  a |= 0x08;
            low &= ~a;
        }
        // Upper two bits read as 1; keep the select bits as last written.
        return 0xC0 | (select & 0x30) | (low & 0x0F);
    }
}
