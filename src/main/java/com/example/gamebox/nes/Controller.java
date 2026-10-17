package com.example.gamebox.nes;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standard NES controller (4021 shift register behaviour). The CPU strobes
 * $4016 to latch the current button state, then reads $4016 eight times to shift
 * out one bit per read in the order A, B, Select, Start, Up, Down, Left, Right.
 *
 * <p>Button state is written from the Minecraft client/input thread and read on
 * the emulator thread, so it is held in an {@link AtomicInteger}.
 */
public final class Controller {

    public static final int BUTTON_A = 0;
    public static final int BUTTON_B = 1;
    public static final int BUTTON_SELECT = 2;
    public static final int BUTTON_START = 3;
    public static final int BUTTON_UP = 4;
    public static final int BUTTON_DOWN = 5;
    public static final int BUTTON_LEFT = 6;
    public static final int BUTTON_RIGHT = 7;

    /** Live button state (bit per button), set by the input layer. */
    private final AtomicInteger buttons = new AtomicInteger(0);

    /** Latched snapshot shifted out by reads. */
    private int shift;
    private boolean strobe;

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

    /** $4016 write: when strobe bit is high, continuously reload the latch. */
    public void write(int value) {
        strobe = (value & 1) != 0;
        if (strobe) {
            shift = buttons.get();
        }
    }

    /** $4016 read: returns the next button bit (bit0); 1 after 8 reads. */
    public int read() {
        if (strobe) {
            shift = buttons.get();
        }
        int bit = shift & 1;
        shift = (shift >> 1) | 0x80; // after 8 reads, official NES returns 1s
        return bit;
    }
}
