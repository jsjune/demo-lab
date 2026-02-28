package org.example.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.TraceEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class TraceEventHandler extends SimpleChannelInboundHandler<String> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaForwarder kafkaForwarder;
    private final AtomicLong eventCounter = new AtomicLong(0);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        try {
            TraceEvent event = objectMapper.readValue(msg, TraceEvent.class);
            kafkaForwarder.forward(event);
            
            long count = eventCounter.incrementAndGet();
            if (count % 100 == 0) {
                log.info("[TRACE COLLECTOR] Processed {} events", count);
            }
        } catch (Exception e) {
            log.warn("[TRACE COLLECTOR] Malformed packet received: {} | Error: {}", msg, e.getMessage());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.io.IOException) {
            log.info("[TRACE COLLECTOR] Client disconnected: {}", cause.getMessage());
        } else {
            log.error("[TRACE COLLECTOR] Pipeline exception: ", cause);
        }
        ctx.close();
    }
}
