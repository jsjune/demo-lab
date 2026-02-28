package org.example.demolab;

import org.springframework.stereotype.Service;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class TcpSender {

    private final String host = "localhost";
    private final int port = 9090;

    public void send(String type, String method, String path) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String message = String.format("{\"requestId\":\"%s\", \"type\":\"%s\", \"method\":\"%s\", \"path\":\"%s\", \"timestamp\":\"%s\"}",
                requestId, type, method, path, LocalDateTime.now());
        
        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println(message);
        } catch (Exception e) {
            System.err.println("[TCP SENDER ERROR]: " + e.getMessage());
        }
    }
}
