package ru.playsoftware.j2meloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class JLMemoryDebugService implements Runnable {
    private int port;
    private boolean running = true;
    private ServerSocket serverSocket;

    public JLMemoryDebugService(int port) {
        this.port = port;
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

                    // Send memory data
                    while (!socket.isClosed() && socket.isConnected()) {
                        Runtime runtime = Runtime.getRuntime();
                        long totalMem = runtime.totalMemory();
                        long freeMem = runtime.freeMemory();
                        long usedMem = totalMem - freeMem;

                        String usedHex  = "0x" + padHex(Long.toHexString(usedMem));
                        String freeHex  = "0x" + padHex(Long.toHexString(freeMem));
                        String totalHex = "0x" + padHex(Long.toHexString(totalMem));

                        String payload = "{\"used\":\"" + usedHex + "\",\"free\":\"" + freeHex + "\",\"total\":\"" + totalHex + "\"}";

                        sendWsTextFrame(os, payload);
                        Thread.sleep(500);
                    }
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
