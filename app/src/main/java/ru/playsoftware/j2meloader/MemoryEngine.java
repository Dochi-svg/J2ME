package ru.playsoftware.j2meloader;

import java.util.ArrayList;
import java.util.List;
import javax.microedition.shell.MidletThread;

/**
 * Mesin scan RAM virtual J2ME.
 *
 * Alamat yang dikembalikan adalah offset/index di dalam buffer ram
 * milik MidletThread. Integer dibaca/ditulis little-endian.
 */
public final class MemoryEngine {

    private MemoryEngine() {}

    /** Mengambil buffer RAM live yang sedang dipakai MIDlet. */
    public static byte[] getLiveRam() {
        try {
            java.lang.reflect.Field field =
                    MidletThread.class.getDeclaredField("ram");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof byte[] ? (byte[]) value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * First Scan: mencari SEMUA integer 32-bit yang sama dengan target.
     * 4-byte aligned agar konsisten dengan representasi int virtual RAM.
     */
    public static List<Integer> firstScanInt(byte[] ram, int target) {
        List<Integer> addresses = new ArrayList<>();
        if (ram == null || ram.length < 4) return addresses;

        for (int address = 0; address <= ram.length - 4; address += 4) {
            if (readInt(ram, address) == target) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    /**
     * Next Scan: membaca RAM LIVE dan hanya mempertahankan alamat
     * yang sebelumnya ditemukan dan sekarang masih berisi target.
     */
    public static List<Integer> nextScanInt(
            byte[] ram, List<Integer> previousResults, int target) {

        List<Integer> filtered = new ArrayList<>();
        if (ram == null || previousResults == null) return filtered;

        for (Integer address : previousResults) {
            if (address == null) continue;
            int a = address;
            if (a >= 0 && a <= ram.length - 4 && readInt(ram, a) == target) {
                filtered.add(a);
            }
        }
        return filtered;
    }

    /** Kompatibilitas dengan pemanggil lama. */
    public static List<Integer> searchInt(byte[] ram, int target) {
        return firstScanInt(ram, target);
    }

    /** Menulis integer 32-bit langsung ke RAM live. */
    public static boolean writeInt(byte[] ram, int address, int newValue) {
        if (ram == null || address < 0 || address > ram.length - 4) {
            return false;
        }

        ram[address]     = (byte) newValue;
        ram[address + 1] = (byte) (newValue >>> 8);
        ram[address + 2] = (byte) (newValue >>> 16);
        ram[address + 3] = (byte) (newValue >>> 24);
        return true;
    }

    /** Membaca integer 32-bit little-endian dari RAM live. */
    public static int readInt(byte[] ram, int address) {
        if (ram == null || address < 0 || address > ram.length - 4) {
            return 0;
        }

        return (ram[address] & 0xFF)
                | ((ram[address + 1] & 0xFF) << 8)
                | ((ram[address + 2] & 0xFF) << 16)
                | ((ram[address + 3] & 0xFF) << 24);
    }
}
