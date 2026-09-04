package ru.playsoftware.j2meloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

public class JLMemoryDebugService implements Runnable {
    private int port;
    private boolean running = true;
    private ServerSocket serverSocket;
    private static byte[] memoryBuffer = null;

    public JLMemoryDebugService(int port) {
        this.port = port;
        // Initialize memory buffer (64 MB default)
        if (memoryBuffer == null) {
            memoryBuffer = new byte[64 * 1024 * 1024];
        }
    }

    public void stopService() {
        this.running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Memory Debug Service started on port " + port);
            System.out.println("Memory Buffer Size: " + (memoryBuffer.length / (1024 * 1024)) + " MB");
            while (running) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private class ClientHandler implements Runnable {
        private Socket socket;
        private static final String MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                // Parse HTTP headers
                Map<String, String> headers = new HashMap<>();
                String line;
                String requestLine = reader.readLine();
                System.out.println("Request: " + requestLine);
                
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String key = line.substring(0, colonIndex).trim();
                        String value = line.substring(colonIndex + 1).trim();
                        headers.put(key.toLowerCase(), value);
                    }
                }

                // Validate WebSocket upgrade request
                String upgrade = headers.get("upgrade");
                String connection = headers.get("connection");
                String secWebSocketKey = headers.get("sec-websocket-key");

                if ("websocket".equalsIgnoreCase(upgrade) && 
                    connection != null && connection.toLowerCase().contains("upgrade") && 
                    secWebSocketKey != null) {
                    
                    // Calculate Sec-WebSocket-Accept
                    String secWebSocketAccept = generateSecWebSocketAccept(secWebSocketKey);
                    
                    String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                                      "Upgrade: websocket\r\n" +
                                      "Connection: Upgrade\r\n" +
                                      "Sec-WebSocket-Accept: " + secWebSocketAccept + "\r\n\r\n";
                    os.write(response.getBytes("UTF-8"));
                    os.flush();

                    System.out.println("WebSocket handshake successful");

                    // WebSocket message loop
                    handleWebSocketMessages(is, os);
                } else {
                    String response = "HTTP/1.1 400 Bad Request\r\n\r\n";
                    os.write(response.getBytes("UTF-8"));
                    os.flush();
                    System.err.println("Invalid WebSocket request");
                }

            } catch (Exception e) {
                System.err.println("Client handler error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void handleWebSocketMessages(InputStream is, OutputStream os) throws Exception {
            byte[] buffer = new byte[4096];
            long lastSendTime = 0;

            while (!socket.isClosed() && socket.isConnected()) {
                try {
                    // Send memory info every 500ms
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastSendTime >= 500) {
                        sendMemoryInfo(os);
                        lastSendTime = currentTime;
                    }

                    // Check for incoming messages (non-blocking check)
                    if (is.available() > 0) {
                        int bytesRead = is.read(buffer);
                        if (bytesRead > 0) {
                            processWebSocketMessage(buffer, bytesRead, os);
                        }
                    }

                    Thread.sleep(50);
                } catch (IOException e) {
                    break;
                }
            }
        }

        private void sendMemoryInfo(OutputStream os) throws IOException {
            Runtime runtime = Runtime.getRuntime();
            long totalMem = runtime.totalMemory();
            long freeMem = runtime.freeMemory();
            long usedMem = totalMem - freeMem;
            long maxMem = runtime.maxMemory();

            String usedHex  = "0x" + padHex(Long.toHexString(usedMem));
            String freeHex  = "0x" + padHex(Long.toHexString(freeMem));
            String totalHex = "0x" + padHex(Long.toHexString(totalMem));
            String maxHex   = "0x" + padHex(Long.toHexString(maxMem));

            String payload = "{\"type\":\"memory\",\"used\":\"" + usedHex + "\",\"free\":\"" + freeHex + 
                           "\",\"total\":\"" + totalHex + "\",\"max\":\"" + maxHex + "\"}";

            sendWsTextFrame(os, payload);
        }

        private void processWebSocketMessage(byte[] data, int length, OutputStream os) throws IOException {
            // Parse WebSocket frame
            if (length < 2) return;

            int opcode = data[0] & 0x0F;
            boolean masked = (data[1] & 0x80) != 0;

            if (opcode == 0x1) { // Text frame
                int payloadStart = 2;
                int payloadLength = data[1] & 0x7F;

                if (payloadLength == 126) {
                    payloadStart = 4;
                    payloadLength = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                } else if (payloadLength == 127) {
                    payloadStart = 10;
                }

                byte[] maskKey = new byte[4];
                if (masked) {
                    for (int i = 0; i < 4; i++) {
                        maskKey[i] = data[payloadStart + i];
                    }
                    payloadStart += 4;
                }

                byte[] payload = new byte[payloadLength];
                for (int i = 0; i < payloadLength && payloadStart + i < length; i++) {
                    byte b = data[payloadStart + i];
                    if (masked) {
                        b ^= maskKey[i % 4];
                    }
                    payload[i] = b;
                }

                String message = new String(payload, "UTF-8");
                System.out.println("Received: " + message);
                handleCommand(message, os);
            }
        }

        private void handleCommand(String message, OutputStream os) throws IOException {
            try {
                // Parse JSON command
                String type = extractJsonValue(message, "type");
                
                if ("write".equals(type)) {
                    String address = extractJsonValue(message, "address");
                    String value = extractJsonValue(message, "value");
                    String dataType = extractJsonValue(message, "dataType");
                    
                    boolean success = writeMemory(address, value, dataType);
                    sendResponse(os, success ? "ok" : "error", "Write operation " + (success ? "successful" : "failed"));
                    
                } else if ("read".equals(type)) {
                    String address = extractJsonValue(message, "address");
                    String dataType = extractJsonValue(message, "dataType");
                    String size = extractJsonValue(message, "size");
                    
                    String result = readMemory(address, dataType, size);
                    sendResponse(os, "value", result);
                    
                } else if ("search".equals(type)) {
                    String value = extractJsonValue(message, "value");
                    String dataType = extractJsonValue(message, "dataType");
                    String startAddr = extractJsonValue(message, "startAddr");
                    String endAddr = extractJsonValue(message, "endAddr");
                    
                    String result = searchMemory(value, dataType, startAddr, endAddr);
                    sendResponse(os, "search_result", result);
                    
                } else if ("getBufferInfo".equals(type)) {
                    long bufferSize = memoryBuffer.length;
                    String info = "{\"size\":" + bufferSize + ",\"sizeMB\":" + (bufferSize / (1024 * 1024)) + 
                                ",\"start\":\"0x00000000\",\"end\":\"0x" + Long.toHexString(bufferSize - 1) + "\"}";
                    sendResponse(os, "buffer_info", info);
                }
            } catch (Exception e) {
                System.err.println("Command error: " + e.getMessage());
                e.printStackTrace();
                sendResponse(os, "error", e.getMessage());
            }
        }

        private boolean writeMemory(String address, String value, String dataType) {
            try {
                long addr = Long.parseLong(address.replace("0x", ""), 16);
                
                // Check bounds
                if (addr < 0 || addr >= memoryBuffer.length) {
                    System.err.println("Address out of bounds: " + address);
                    return false;
                }
                
                if ("byte".equals(dataType)) {
                    byte val = (byte) Integer.parseInt(value);
                    memoryBuffer[(int)addr] = val;
                    return true;
                } else if ("int".equals(dataType)) {
                    int val = Integer.parseInt(value);
                    if (addr + 4 > memoryBuffer.length) return false;
                    ByteBuffer.wrap(memoryBuffer, (int)addr, 4).putInt(val);
                    return true;
                } else if ("long".equals(dataType)) {
                    long val = Long.parseLong(value);
                    if (addr + 8 > memoryBuffer.length) return false;
                    ByteBuffer.wrap(memoryBuffer, (int)addr, 8).putLong(val);
                    return true;
                } else if ("float".equals(dataType)) {
                    float val = Float.parseFloat(value);
                    if (addr + 4 > memoryBuffer.length) return false;
                    ByteBuffer.wrap(memoryBuffer, (int)addr, 4).putFloat(val);
                    return true;
                }
                return false;
            } catch (Exception e) {
                System.err.println("Write error: " + e.getMessage());
                return false;
            }
        }

        private String readMemory(String address, String dataType, String size) {
            try {
                long addr = Long.parseLong(address.replace("0x", ""), 16);
                int readSize = Integer.parseInt(size);
                
                if (addr < 0 || addr >= memoryBuffer.length) {
                    return "error: address out of bounds";
                }
                
                if ("byte".equals(dataType)) {
                    byte[] data = new byte[readSize];
                    int copySize = Math.min(readSize, (int)(memoryBuffer.length - addr));
                    System.arraycopy(memoryBuffer, (int)addr, data, 0, copySize);
                    StringBuilder hex = new StringBuilder();
                    for (byte b : data) {
                        hex.append(String.format("%02X ", b));
                    }
                    return hex.toString();
                } else if ("int".equals(dataType)) {
                    if (addr + 4 > memoryBuffer.length) return "error: out of bounds";
                    int val = ByteBuffer.wrap(memoryBuffer, (int)addr, 4).getInt();
                    return "0x" + Integer.toHexString(val);
                } else if ("long".equals(dataType)) {
                    if (addr + 8 > memoryBuffer.length) return "error: out of bounds";
                    long val = ByteBuffer.wrap(memoryBuffer, (int)addr, 8).getLong();
                    return "0x" + Long.toHexString(val);
                } else if ("float".equals(dataType)) {
                    if (addr + 4 > memoryBuffer.length) return "error: out of bounds";
                    float val = ByteBuffer.wrap(memoryBuffer, (int)addr, 4).getFloat();
                    return Float.toString(val);
                }
                return "0x00";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        private String searchMemory(String searchValue, String dataType, String startAddr, String endAddr) {
            try {
                long start = Long.parseLong(startAddr.replace("0x", ""), 16);
                long end = Long.parseLong(endAddr.replace("0x", ""), 16);
                
                // Validate range
                if (start < 0) start = 0;
                if (end >= memoryBuffer.length) end = memoryBuffer.length - 1;
                if (start > end) {
                    return "{\"error\":\"Invalid range\",\"results\":[]}";
                }
                
                List<String> results = new ArrayList<>();
                long scanSize = end - start + 1;
                
                System.out.println("Scanning from 0x" + Long.toHexString(start) + 
                                 " to 0x" + Long.toHexString(end) + 
                                 " (" + (scanSize / 1024) + " KB)");
                
                if ("byte".equals(dataType)) {
                    byte searchVal = (byte) Integer.parseInt(searchValue);
                    for (long i = start; i <= end; i++) {
                        if (memoryBuffer[(int)i] == searchVal) {
                            results.add("0x" + Long.toHexString(i));
                            if (results.size() >= 100) break; // Limit results
                        }
                    }
                } else if ("int".equals(dataType)) {
                    int searchVal = Integer.parseInt(searchValue);
                    for (long i = start; i <= end - 3; i++) {
                        if (i + 4 > memoryBuffer.length) break;
                        int val = ByteBuffer.wrap(memoryBuffer, (int)i, 4).getInt();
                        if (val == searchVal) {
                            results.add("0x" + Long.toHexString(i));
                            if (results.size() >= 100) break;
                        }
                    }
                } else if ("long".equals(dataType)) {
                    long searchVal = Long.parseLong(searchValue);
                    for (long i = start; i <= end - 7; i++) {
                        if (i + 8 > memoryBuffer.length) break;
                        long val = ByteBuffer.wrap(memoryBuffer, (int)i, 8).getLong();
                        if (val == searchVal) {
                            results.add("0x" + Long.toHexString(i));
                            if (results.size() >= 100) break;
                        }
                    }
                } else if ("float".equals(dataType)) {
                    float searchVal = Float.parseFloat(searchValue);
                    for (long i = start; i <= end - 3; i++) {
                        if (i + 4 > memoryBuffer.length) break;
                        float val = ByteBuffer.wrap(memoryBuffer, (int)i, 4).getFloat();
                        if (Math.abs(val - searchVal) < 0.0001f) {
                            results.add("0x" + Long.toHexString(i));
                            if (results.size() >= 100) break;
                        }
                    }
                }
                
                StringBuilder resultJson = new StringBuilder("{\"scanRange\":{\"start\":\"0x" + 
                    Long.toHexString(start) + "\",\"end\":\"0x" + Long.toHexString(end) + 
                    "\"},\"found\":" + results.size() + ",\"results\":[");
                
                for (int i = 0; i < results.size(); i++) {
                    resultJson.append("\"").append(results.get(i)).append("\"");
                    if (i < results.size() - 1) resultJson.append(",");
                }
                resultJson.append("]}");
                
                return resultJson.toString();
            } catch (Exception e) {
                System.err.println("Search error: " + e.getMessage());
                return "{\"error\":\"" + e.getMessage() + "\",\"results\":[]}";
            }
        }

        private void sendResponse(OutputStream os, String type, String value) throws IOException {
            String response = "{\"type\":\"" + type + "\",\"data\":" + value + "}";
            sendWsTextFrame(os, response);
        }

        private String generateSecWebSocketAccept(String secWebSocketKey) throws Exception {
            String input = secWebSocketKey + MAGIC_STRING;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash);
        }

        private String padHex(String hex) {
            hex = hex.toUpperCase();
            while (hex.length() < 8) {
                hex = "0" + hex;
            }
            return hex;
        }

        private String extractJsonValue(String json, String key) {
            String searchKey = "\"" + key + "\":\"";
            int startIndex = json.indexOf(searchKey);
            if (startIndex == -1) return "";
            
            startIndex += searchKey.length();
            int endIndex = json.indexOf("\"", startIndex);
            
            if (endIndex == -1) return "";
            return json.substring(startIndex, endIndex);
        }

        private void sendWsTextFrame(OutputStream os, String message) throws IOException {
            byte[] rawData = message.getBytes("UTF-8");
            int length = rawData.length;

            // Frame format: FIN (1) + RSV (3) + OPCODE (4) = 0x81 for text frame
            os.write(0x81);
            
            // Length encoding
            if (length <= 125) {
                os.write(length);
            } else if (length <= 65535) {
                os.write(126);
                os.write((length >> 8) & 0xFF);
                os.write(length & 0xFF);
            } else {
                os.write(127);
                os.write((length >> 56) & 0xFF);
                os.write((length >> 48) & 0xFF);
                os.write((length >> 40) & 0xFF);
                os.write((length >> 32) & 0xFF);
                os.write((length >> 24) & 0xFF);
                os.write((length >> 16) & 0xFF);
                os.write((length >> 8) & 0xFF);
                os.write(length & 0xFF);
            }
            
            // No masking needed for server->client messages
            os.write(rawData);
            os.flush();
        }
    }
}
