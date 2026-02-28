package org.example.collector;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.util.CharsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NettyTcpServer implements SmartLifecycle {

    private final CollectorProperties properties;
    private final TraceEventHandler traceEventHandler;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean running = false;

    @Override
    public void start() {
        log.info("Starting Netty TCP Server on port {}...", properties.getTcp().getPort());
        
        bossGroup = new NioEventLoopGroup(properties.getTcp().getBossThreads());
        workerGroup = new NioEventLoopGroup(properties.getTcp().getWorkerThreads());

        Thread serverThread = new Thread(() -> {
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     public void initChannel(SocketChannel ch) {
                         ch.pipeline().addLast(new LineBasedFrameDecoder(65536));
                         ch.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
                         ch.pipeline().addLast(traceEventHandler);
                     }
                 });

                ChannelFuture f = b.bind(properties.getTcp().getPort()).sync();
                running = true;
                log.info("[TRACE COLLECTOR] Netty TCP Server is bound and running.");
                f.channel().closeFuture().sync();
            } catch (Exception e) {
                log.error("[TRACE COLLECTOR] Server error: ", e);
            } finally {
                stop();
            }
        });
        
        serverThread.setDaemon(false);
        serverThread.setName("netty-server-thread");
        serverThread.start();
    }

    @Override
    public void stop() {
        log.info("Stopping Netty TCP Server...");
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Start last, Stop first
    }
}
