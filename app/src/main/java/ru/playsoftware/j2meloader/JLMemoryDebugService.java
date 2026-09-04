package ru.playsoftware.j2meloader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * WebSocket-based Memory Debugger Server untuk J2ME Games
 * Mengakses memori VM game yang sedang berjalan (bukan dummy memory)
 */
public class JLMemoryDebugService implements Runnable {

    private final int port;
    private volatile byte[] vmMemory;
    private static JLMemoryDebugService instance;

    public JLMemoryDebugService(int port) {
        this.port = port;
        this.vmMemory = null;
        instance = this;
    }

    /**
     * Singleton instance untuk akses global
     */
    public static JLMemoryDebugService getInstance() {
        return instance;
    }

    /**
     * Set referensi ke memori VM dari game yang sedang berjalan
     */
    public void setVMMemory(byte[] memory) {
        this.vmMemory = memory;
        if (memory != null) {
            System.out.println("[DEBUG] VM Memory set: " + memory.length + " bytes");
        }
    }

    /**
     * Get memori VM yang sedang aktif
     */
    public byte[] getVMMemory() {
        return vmMemory;
    }

    @Override
    public void run() {
        System.out.println("=== J2ME Memory Debugger Server ===");
        System.out.println("Listening on ws://localhost:" + port);
        System.out.println("Waiting for game to start...");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket, this), "J2ME-DebugClient").start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final JLMemoryDebugService debugService;

        public ClientHandler(Socket socket, JLMemoryDebugService debugService) {
            this.socket = socket;
            this.debugService = debugService;
        }

        @Override
        public void run() {
            try (InputStream in = socket.getInputStream();
                 OutputStream out = socket.getOutputStream()) {

                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                
                // WebSocket Handshake
                String line;
                String webSocketKey = "";
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Sec-WebSocket-Key:")) {
                        webSocketKey = line.split(":")[1].trim();
                    }
                }

                if (webSocketKey.isEmpty()) return;

                String acceptKey = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest(
                                (webSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
                        )
                );

                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
                out.write(response.getBytes("UTF-8"));
                out.flush();

                System.out.println("Frontend connected to Memory Debugger!");

                // WebSocket Loop
                while (!socket.isClosed()) {
                    String payload = readWebSocketFrame(in);
                    if (payload == null) break;

                    try {
                        handleCommand(payload, out);
                    } catch (Exception e) {
                        e.printStackTrace();
                        sendJsonResponse(out, "error", "Internal Error: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                System.out.println("Debugger client disconnected.");
            }
        }

        private void handleCommand(String jsonPayload, OutputStream out) throws Exception {
            byte[] memory = debugService.getVMMemory();
            if (memory == null) {
                sendJsonResponse(out, "error", "No game running. VM memory not initialized.");
                return;
            }

            String type = extractJsonValue(jsonPayload, "type");
            
            if ("search".equals(type)) {
                String value = extractJsonValue(jsonPayload, "value");
                String dataType = extractJsonValue(jsonPayload, "dataType");
                long start = parseHex(extractJsonValue(jsonPayload, "startAddr"), 0x00000000L);
                long end = parseHex(extractJsonValue(jsonPayload, "endAddr"), 0x03FFFFFFL);

                if (end >= memory.length) end = memory.length - 1;

                List<String> results = searchMemory(memory, value, dataType, start, end);
                sendJsonResponse(out, "search_result", 
                    "{\"found\":" + results.size() + ",\"results\":" + toJsonArray(results) + "}");

            } else if ("read".equals(type)) {
                String addressStr = extractJsonValue(jsonPayload, "address");
                String dataType = extractJsonValue(jsonPayload, "dataType");

                long addr = parseHex(addressStr, -1);
                if (addr >= 0 && addr < memory.length) {
                    String valStr = readMemoryValue(memory, (int) addr, dataType);
                    sendJsonResponse(out, "value", valStr);
                } else {
                    sendJsonResponse(out, "error", "Address out of bounds: 0x" + Long.toHexString(addr));
                }

            } else if ("write".equals(type)) {
                String addressStr = extractJsonValue(jsonPayload, "address");
                String value = extractJsonValue(jsonPayload, "value");
                String dataType = extractJsonValue(jsonPayload, "dataType");

                long addr = parseHex(addressStr, -1);
                if (addr >= 0 && addr < memory.length) {
                    writeMemory(memory, (int) addr, value, dataType);
                    sendJsonResponse(out, "ok", "Injected " + value + " to 0x" + Long.toHexString(addr));
                } else {
                    sendJsonResponse(out, "error", "Invalid address: 0x" + Long.toHexString(addr));
                }

            } else if ("info".equals(type)) {
                long size = memory.length;
                sendJsonResponse(out, "info", 
                    "{\"memory_size\":" + size + ",\"memory_size_mb\":" + (size / 1024 / 1024) + "}");
            }
        }

        private List<String> searchMemory(byte[] memory, String searchValue, String dataType, long start, long end) {
            List<String> results = new ArrayList<>();
            searchValue = searchValue.trim();
            if (searchValue.isEmpty()) return results;

            try {
                if ("byte".equals(dataType)) {
                    byte target = (byte) Integer.parseInt(searchValue);
                    for (long i = start; i <= end && results.size() < 500; i++) {
                        if (memory[(int) i] == target) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                        }
                    }
                } else if ("int".equals(dataType)) {
                    int target = Integer.parseInt(searchValue);
                    for (long i = start; i <= end - 3 && results.size() < 500; i++) {
                        int val = ByteBuffer.wrap(memory, (int) i, 4)
                                            .order(ByteOrder.LITTLE_ENDIAN)
                                            .getInt();
                        if (val == target) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                        }
                    }
                } else if ("float".equals(dataType)) {
                    float target = Float.parseFloat(searchValue);
                    for (long i = start; i <= end - 3 && results.size() < 500; i++) {
                        float val = ByteBuffer.wrap(memory, (int) i, 4)
                                              .order(ByteOrder.LITTLE_ENDIAN)
                                              .getFloat();
                        if (Math.abs(val - target) < 0.0001f) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                        }
                    }
                }
            } catch (Exception ignored) {}

            return results;
        }

        private void writeMemory(byte[] memory, int addr, String valueStr, String dataType) {
            try {
                if ("byte".equals(dataType)) {
                    memory[addr] = (byte) Integer.parseInt(valueStr.trim());
                } else if ("int".equals(dataType)) {
                    ByteBuffer.wrap(memory, addr, 4)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .putInt(Integer.parseInt(valueStr.trim()));
                } else if ("float".equals(dataType)) {
                    ByteBuffer.wrap(memory, addr, 4)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .putFloat(Float.parseFloat(valueStr.trim()));
                }
            } catch (Exception ignored) {}
        }

        private String readMemoryValue(byte[] memory, int addr, String dataType) {
            try {
                if ("byte".equals(dataType)) {
                    return String.valueOf(memory[addr]);
                } else if ("int".equals(dataType)) {
                    return String.valueOf(ByteBuffer.wrap(memory, addr, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getInt());
                } else if ("float".equals(dataType)) {
                    return String.valueOf(ByteBuffer.wrap(memory, addr, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getFloat());
                }
            } catch (Exception e) {
                return "0";
            }
            return "0";
        }

        private String extractJsonValue(String json, String key) {
            String patternWithQuotes = "\"" + key + "\":\"";
            int start = json.indexOf(patternWithQuotes);
            if (start != -1) {
                start += patternWithQuotes.length();
                int end = json.indexOf("\"", start);
                if (end != -1) return json.substring(start, end);
            }

            String patternNoQuotes = "\"" + key + "\":";
            start = json.indexOf(patternNoQuotes);
            if (start != -1) {
                start += patternNoQuotes.length();
                int endComma = json.indexOf(",", start);
                int endBrace = json.indexOf("}", start);
                int end = (endComma != -1 && endComma < endBrace) ? endComma : endBrace;
                if (end != -1) {
                    return json.substring(start, end).replace("\"", "").trim();
                }
            }
            return "";
        }

        private String toJsonArray(List<String> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append("\"").append(list.get(i)).append("\"");
                if (i < list.size() - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }

        private long parseHex(String hex, long defaultVal) {
            if (hex == null || hex.isEmpty()) return defaultVal;
            try {
                hex = hex.replace("0x", "").replace("0X", "").trim();
                return Long.parseLong(hex, 16);
            } catch (Exception e) {
                return defaultVal;
            }
        }

        private String padHex(String hex) {
            StringBuilder sb = new StringBuilder(hex.toUpperCase());
            while (sb.length() < 8) sb.insert(0, "0");
            return sb.toString();
        }

        private void sendJsonResponse(OutputStream out, String type, String dataPayload) throws Exception {
            String json = "{\"type\":\"" + type + "\",\"data\":" + 
                    (dataPayload.startsWith("{") || dataPayload.startsWith("[") ? dataPayload : "\"" + dataPayload + "\"") + "}";

            byte[] payloadBytes = json.getBytes("UTF-8");
            int length = payloadBytes.length;

            out.write(0x81);
            if (length <= 125) {
                out.write(length);
            } else if (length <= 65535) {
                out.write(126);
                out.write((length >> 8) & 0xFF);
                out.write(length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((length >> (i * 8)) & 0xFF));
                }
            }
            out.write(payloadBytes);
            out.flush();
        }

        private String readWebSocketFrame(InputStream in) throws Exception {
            int b1 = in.read();
            if (b1 == -1) return null;
            int b2 = in.read();
            if (b2 == -1) return null;

            boolean masked = (b2 & 0x80) != 0;
            long payloadLength = b2 & 0x7F;

            if (payloadLength == 126) {
                int byte1 = in.read();
                int byte2 = in.read();
                if (byte1 == -1 || byte2 == -1) return null;
                payloadLength = (byte1 << 8) | byte2;
            } else if (payloadLength == 127) {
                long len = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b == -1) return null;
                    len = (len << 8) | b;
                }
                payloadLength = len;
            }

            byte[] key = new byte[4];
            if (masked) {
                int readKey = in.read(key, 0, 4);
                if (readKey < 4) return null;
            }

            byte[] payload = new byte[(int) payloadLength];
            int totalRead = 0;
            while (totalRead < payloadLength) {
                int read = in.read(payload, totalRead, (int) payloadLength - totalRead);
                if (read == -1) break;
                totalRead += read;
            }

            if (masked) {
                for (int i = 0; i < payloadLength; i++) {
                    payload[i] = (byte) (payload[i] ^ key[i % 4]);
                }
            }

            return new String(payload, "UTF-8");
        }
    }
}
