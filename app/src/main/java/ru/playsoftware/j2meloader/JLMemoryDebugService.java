package ru.playsoftware.j2meloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

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
            
        }
    }

    private class ClientHandler implements Runnable {
        private Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {

                }

                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                                  "Upgrade: websocket\r\n" +
                                  "Connection: Upgrade\r\n" +
                                  "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n";
                os.write(response.getBytes("UTF-8"));
                os.flush();

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

            } catch (Exception e) {

            }
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

            os.write(0x81);
            if (length <= 125) {
                os.write(length);
            } else if (length <= 65535) {
                os.write(126);
                os.write((length >> 8) & 0xFF);
                os.write(length & 0xFF);
            }
            os.write(rawData);
            os.flush();
        }
    }
}
