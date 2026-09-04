package ru.playsoftware.j2meloader;

import java.io.BufferedReader;
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

public class JLMemoryDebugService implements Runnable {

    private static final int MEMORY_SIZE = 0x03FFFFFF; 
    private static final byte[] memoryBuffer = new byte[MEMORY_SIZE];
    private int port;

    public JLMemoryDebugService(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        initDummyMemory();

        System.out.println("=== J2ME Memory Debugger Server (Little-Endian) ===");
        System.out.println("Listening on ws://localhost:" + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void initDummyMemory() {
        // Mengisi memori dengan angka dummy (Little-Endian)
        // Menulis angka Int 100 di alamat 0x00002000
        ByteBuffer.wrap(memoryBuffer, 0x00002000, 4)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .putInt(100);

        // Menulis angka Int 100 di alamat 0x00002005 (unaligned test)
        ByteBuffer.wrap(memoryBuffer, 0x00002005, 4)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .putInt(100);

        // Menulis Float 99.5f di alamat 0x00003000
        ByteBuffer.wrap(memoryBuffer, 0x00003000, 4)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .putFloat(99.5f);
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {

                // 1. Handshake WebSocket
                String line;
                String webSocketKey = "";
                while ((line = in.readLine()) != null && !line.isEmpty()) {
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

                System.out.println("Frontend connected!");

                // 2. Loop Baca Frame WebSocket
                while (true) {
                    String payload = readWebSocketFrame(socket.getInputStream());
                    if (payload == null) break;

                    handleCommand(payload, out);
                }

            } catch (Exception e) {
                System.out.println("Client disconnected.");
            }
        }

        private void handleCommand(String jsonPayload, OutputStream out) throws Exception {
            String type = extractJsonValue(jsonPayload, "type");
            
            if ("search".equals(type)) {
                String value = extractJsonValue(jsonPayload, "value");
                String dataType = extractJsonValue(jsonPayload, "dataType");
                long start = parseHex(extractJsonValue(jsonPayload, "startAddr"), 0x00001000L);
                long end = parseHex(extractJsonValue(jsonPayload, "endAddr"), 0x03FFFFFFL);

                if (end >= MEMORY_SIZE) end = MEMORY_SIZE - 1;

                List<String> results = searchMemory(value, dataType, start, end);
                sendJsonResponse(out, "search_result", "{\"found\":" + results.size() + ",\"results\":" + toJsonArray(results) + "}");

            } else if ("nextSearch".equals(type)) {
                String value = extractJsonValue(jsonPayload, "value");
                String dataType = extractJsonValue(jsonPayload, "dataType");
                List<String> prevResults = extractJsonArray(jsonPayload, "previousResults");

                List<String> filtered = filterMemory(value, dataType, prevResults);
                sendJsonResponse(out, "search_result", "{\"found\":" + filtered.size() + ",\"results\":" + toJsonArray(filtered) + "}");

            } else if ("write".equals(type)) {
                String addressStr = extractJsonValue(jsonPayload, "address");
                String value = extractJsonValue(jsonPayload, "value");
                String dataType = extractJsonValue(jsonPayload, "dataType");

                long addr = parseHex(addressStr, -1);
                if (addr >= 0 && addr < MEMORY_SIZE) {
                    writeMemory((int) addr, value, dataType);
                    sendJsonResponse(out, "ok", "Injected " + value + " to " + addressStr);
                } else {
                    sendJsonResponse(out, "error", "Invalid Address");
                }

            } else if ("read".equals(type)) {
                String addressStr = extractJsonValue(jsonPayload, "address");
                String dataType = extractJsonValue(jsonPayload, "dataType");

                long addr = parseHex(addressStr, -1);
                if (addr >= 0 && addr < MEMORY_SIZE) {
                    String valStr = readMemoryValue((int) addr, dataType);
                    sendJsonResponse(out, "value", valStr);
                } else {
                    sendJsonResponse(out, "error", "Read Failed");
                }
            }
        }

        // --- CORE MEMORY OPERATIONS (LITTLE ENDIAN) ---

        private List<String> searchMemory(String searchValue, String dataType, long start, long end) {
            List<String> results = new ArrayList<>();
            searchValue = searchValue.trim();

            try {
                if ("byte".equals(dataType)) {
                    byte target = (byte) Integer.parseInt(searchValue);
                    for (long i = start; i <= end; i++) {
                        if (memoryBuffer[(int) i] == target) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                            if (results.size() >= 500) break;
                        }
                    }
                } else if ("int".equals(dataType)) {
                    int target = Integer.parseInt(searchValue);
                    for (long i = start; i <= end - 3; i++) {
                        int val = ByteBuffer.wrap(memoryBuffer, (int) i, 4)
                                            .order(ByteOrder.LITTLE_ENDIAN)
                                            .getInt();
                        if (val == target) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                            if (results.size() >= 500) break;
                        }
                    }
                } else if ("long".equals(dataType)) {
                    long target = Long.parseLong(searchValue);
                    for (long i = start; i <= end - 7; i++) {
                        long val = ByteBuffer.wrap(memoryBuffer, (int) i, 8)
                                             .order(ByteOrder.LITTLE_ENDIAN)
                                             .getLong();
                        if (val == target) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                            if (results.size() >= 500) break;
                        }
                    }
                } else if ("float".equals(dataType)) {
                    float target = Float.parseFloat(searchValue);
                    for (long i = start; i <= end - 3; i++) {
                        float val = ByteBuffer.wrap(memoryBuffer, (int) i, 4)
                                              .order(ByteOrder.LITTLE_ENDIAN)
                                              .getFloat();
                        if (Math.abs(val - target) < 0.0001f) {
                            results.add("0x" + padHex(Long.toHexString(i)));
                            if (results.size() >= 500) break;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return results;
        }

        private List<String> filterMemory(String searchValue, String dataType, List<String> addresses) {
            List<String> filtered = new ArrayList<>();
            for (String addrStr : addresses) {
                long addr = parseHex(addrStr, -1);
                if (addr < 0 || addr >= MEMORY_SIZE) continue;

                String currentVal = readMemoryValue((int) addr, dataType);
                if ("float".equals(dataType)) {
                    try {
                        float v1 = Float.parseFloat(currentVal);
                        float v2 = Float.parseFloat(searchValue);
                        if (Math.abs(v1 - v2) < 0.0001f) filtered.add(addrStr);
                    } catch (Exception ignored) {}
                } else if (currentVal.equals(searchValue.trim())) {
                    filtered.add(addrStr);
                }
            }
            return filtered;
        }

        private void writeMemory(int addr, String valueStr, String dataType) {
            try {
                if ("byte".equals(dataType)) {
                    memoryBuffer[addr] = (byte) Integer.parseInt(valueStr.trim());
                } else if ("int".equals(dataType)) {
                    ByteBuffer.wrap(memoryBuffer, addr, 4)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .putInt(Integer.parseInt(valueStr.trim()));
                } else if ("long".equals(dataType)) {
                    ByteBuffer.wrap(memoryBuffer, addr, 8)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .putLong(Long.parseLong(valueStr.trim()));
                } else if ("float".equals(dataType)) {
                    ByteBuffer.wrap(memoryBuffer, addr, 4)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .putFloat(Float.parseFloat(valueStr.trim()));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private String readMemoryValue(int addr, String dataType) {
            try {
                if ("byte".equals(dataType)) {
                    return String.valueOf(memoryBuffer[addr]);
                } else if ("int".equals(dataType)) {
                    return String.valueOf(ByteBuffer.wrap(memoryBuffer, addr, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getInt());
                } else if ("long".equals(dataType)) {
                    return String.valueOf(ByteBuffer.wrap(memoryBuffer, addr, 8)
                            .order(ByteOrder.LITTLE_ENDIAN).getLong());
                } else if ("float".equals(dataType)) {
                    return String.valueOf(ByteBuffer.wrap(memoryBuffer, addr, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getFloat());
                }
            } catch (Exception e) {
                return "0";
            }
            return "0";
        }

        // --- UTILITY & JSON PARSER MANUAL ---

        private String extractJsonValue(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start == -1) {
                pattern = "\"" + key + "\":";
                start = json.indexOf(pattern);
                if (start == -1) return "";
                start += pattern.length();
                int end = json.indexOf(",", start);
                if (end == -1) end = json.indexOf("}", start);
                return json.substring(start, end).replace("\"", "").trim();
            }
            start += pattern.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        }

        private List<String> extractJsonArray(String json, String key) {
            List<String> list = new ArrayList<>();
            String pattern = "\"" + key + "\":[";
            int start = json.indexOf(pattern);
            if (start == -1) return list;
            start += pattern.length();
            int end = json.indexOf("]", start);
            if (end == -1) return list;

            String arrayContent = json.substring(start, end);
            String[] items = arrayContent.split(",");
            for (String item : items) {
                String cleaned = item.replace("\"", "").trim();
                if (!cleaned.isEmpty()) list.add(cleaned);
            }
            return list;
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
            while (hex.length() < 8) hex = "0" + hex;
            return hex.toUpperCase();
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
            }
            out.write(payloadBytes);
            out.flush();
        }

        private String readWebSocketFrame(java.io.InputStream in) throws Exception {
            int b1 = in.read();
            if (b1 == -1) return null;
            int b2 = in.read();

            boolean masked = (b2 & 0x80) != 0;
            int payloadLength = b2 & 0x7F;

            if (payloadLength == 126) {
                payloadLength = (in.read() << 8) | in.read();
            } else if (payloadLength == 127) {
                return null; 
            }

            byte[] key = new byte[4];
            if (masked) {
                in.read(key, 0, 4);
            }

            byte[] payload = new byte[payloadLength];
            int totalRead = 0;
            while (totalRead < payloadLength) {
                int read = in.read(payload, totalRead, payloadLength - totalRead);
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
