package org.example.demolab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy
public class DemoLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoLabApplication.class, args);
    }

}
