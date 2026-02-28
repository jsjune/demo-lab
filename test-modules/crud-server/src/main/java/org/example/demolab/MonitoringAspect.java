package org.example.demolab;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class MonitoringAspect {

    private final TcpSender tcpSender;

    public MonitoringAspect(TcpSender tcpSender) {
        this.tcpSender = tcpSender;
    }

    @Around("execution(* org.example.demolab.ProductController.*(..))")
    public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String method = request.getMethod();
        String path = request.getRequestURI();

        // 1. Send START packet
        tcpSender.send("START", method, path);

        Object result;
        try {
            result = joinPoint.proceed();
        } finally {
            // 2. Send END packet
            tcpSender.send("END", method, path);
        }

        return result;
    }
}
