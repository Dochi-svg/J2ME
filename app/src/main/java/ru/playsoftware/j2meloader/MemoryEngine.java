package ru.playsoftware.j2meloader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.shell.MidletThread;

public class MemoryEngine {

    /**
     * Mengambil referensi RAM asli dari MidletThread via Reflection
     */
    public static byte[] getLiveRam() {
        try {
            java.lang.reflect.Field field = MidletThread.class.getDeclaredField("ram");
            field.setAccessible(true);
            return (byte[]) field.get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Mencari nilai Integer (4 Byte) di dalam array RAM
     */
    public static List<Integer> searchInt(byte[] ram, int target) {
        List<Integer> addresses = new ArrayList<>();
        if (ram == null) return addresses;

        for (int i = 0; i <= ram.length - 4 && addresses.size() < 200; i++) {
            int val = ByteBuffer.wrap(ram, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (val == target) {
                addresses.add(i);
            }
        }
        return addresses;
    }

    /**
     * Menuliskan nilai Integer (4 Byte) ke alamat RAM tertentu secara live
     */
    public static void writeInt(byte[] ram, int address, int newValue) {
        if (ram == null || address < 0 || address > ram.length - 4) return;
        ByteBuffer.wrap(ram, address, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newValue);
    }

    /**
     * Membaca nilai Integer (4 Byte) dari alamat RAM tertentu
     */
    public static int readInt(byte[] ram, int address) {
        if (ram == null || address < 0 || address > ram.length - 4) return 0;
        return ByteBuffer.wrap(ram, address, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
