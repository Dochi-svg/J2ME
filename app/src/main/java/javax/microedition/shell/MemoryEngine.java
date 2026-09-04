package javax.microedition.shell;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory scanner/injector for the emulator's live virtual RAM.
 *
 * Addresses returned by this class are offsets inside MidletThread.ram.
 */
public final class MemoryEngine {

    private MemoryEngine() {
    }

    /** Returns the actual RAM buffer owned by MidletThread. */
    public static byte[] getLiveRam() {
        try {
            java.lang.reflect.Field field =
                    MidletThread.class.getDeclaredField("ram");
            field.setAccessible(true);

            Object value = field.get(null);
            if (value instanceof byte[]) {
                return (byte[]) value;
            }
        } catch (Throwable ignored) {
            // Real RAM could not be obtained.
        }

        return null;
    }

    /** First Scan: search the complete RAM for aligned int32 values. */
    public static List<Integer> firstScanInt(byte[] ram, int target) {
        List<Integer> results = new ArrayList<>();

        if (ram == null || ram.length < 4) {
            return results;
        }

        for (int address = 0; address <= ram.length - 4; address += 4) {
            if (readInt(ram, address) == target) {
                results.add(address);
            }
        }

        return results;
    }

    /** Next Scan: keep only previous addresses whose current value matches target. */
    public static List<Integer> nextScanInt(
            byte[] ram, List<Integer> previousResults, int target) {

        List<Integer> results = new ArrayList<>();

        if (ram == null || previousResults == null) {
            return results;
        }

        for (Integer addressObject : previousResults) {
            if (addressObject == null) {
                continue;
            }

            int address = addressObject;

            if (address >= 0
                    && address <= ram.length - 4
                    && readInt(ram, address) == target) {
                results.add(address);
            }
        }

        return results;
    }

    /** Compatibility method for existing callers. */
    public static List<Integer> searchInt(byte[] ram, int target) {
        return firstScanInt(ram, target);
    }

    /** Read signed 32-bit little-endian integer. */
    public static int readInt(byte[] ram, int address) {
        if (ram == null || address < 0 || address > ram.length - 4) {
            return 0;
        }

        return (ram[address] & 0xFF)
                | ((ram[address + 1] & 0xFF) << 8)
                | ((ram[address + 2] & 0xFF) << 16)
                | ((ram[address + 3] & 0xFF) << 24);
    }

    /** Write signed 32-bit little-endian integer directly to RAM. */
    public static boolean writeInt(byte[] ram, int address, int value) {
        if (ram == null || address < 0 || address > ram.length - 4) {
            return false;
        }

        ram[address]     = (byte) value;
        ram[address + 1] = (byte) (value >>> 8);
        ram[address + 2] = (byte) (value >>> 16);
        ram[address + 3] = (byte) (value >>> 24);

        return true;
    }
}
