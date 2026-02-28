package org.example.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

@SpringBootApplication
public class TcpCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcpCollectorApplication.class, args);
    }

    @Bean
    public CommandLineRunner tcpServerRunner() {
        return args -> {
            int port = 9090;
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("TCP Collector Server started on port " + port);
                while (true) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println("[COLLECTOR RECEIVED]: " + line);
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing connection: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Could not start TCP server: " + e.getMessage());
            }
        };
    }
}
