package com.nuofankj.socket.server;

import com.nuofankj.socket.handler.AuthHandler;
import com.nuofankj.socket.handler.MessageHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author xifanxiaxue
 * @date 2/8/20
 * @desc WebSocket入口
 */
@Component
public class WebSocketServer implements SmartInitializingSingleton {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private final InternalLogger log = InternalLoggerFactory.getInstance(WebSocketServer.class);
    private NetServerOptions serverOptions;
    private ServerBootstrap serverBootstrap;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final ChannelGroup channelGroup = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);
    private Channel channel;
    private int port;

    @Override
    public void afterSingletonsInstantiated() {
        this.serverOptions = new NetServerOptions.Builder().build();
        this.start();
    }

    private void start() {
        Class<? extends ServerChannel> serverChannel;
        if (isLinux()) {
            this.bossGroup = new EpollEventLoopGroup(serverOptions.getAcceptorThreads(), new DefaultThreadFactory("NetServerAcceptorIoThread"));
            this.workerGroup = new EpollEventLoopGroup(serverOptions.getAcceptorThreads(), new DefaultThreadFactory("NetServerWorkerIoThread"));
            serverChannel = EpollServerSocketChannel.class;
        } else {
            this.bossGroup = new NioEventLoopGroup(serverOptions.getAcceptorThreads(), new DefaultThreadFactory("NetServerAcceptorIoThread"));
            this.workerGroup = new NioEventLoopGroup(serverOptions.getAcceptorThreads(), new DefaultThreadFactory("NetServerWorkerIoThread"));
            serverChannel = NioServerSocketChannel.class;
        }

        serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(this.bossGroup, this.workerGroup);
        serverBootstrap.channel(serverChannel);
        // BACKLOG用于构造服务端套接字ServerSocket对象，标识当服务器请求处理线程全满时，用于临时存放已完成三次握手的请求的队列的最大长度。如果未设置或所设置的值小于1，Java将使用默认值50。
        serverBootstrap.option(ChannelOption.SO_BACKLOG, serverOptions.getBackLog());
        // 水位监控,用是限制netty向channel写数据时使用的缓冲区边界。当netty需要往channel中写入数据时会先把数据写入一个Buffer缓冲区，这个缓冲区是各个channel独占的，不共享。等到channel空闲的时候就从缓冲区中读取数据进行发送。这样做可以提高网络的吞吐量。但也带来了一个缺点，就是在碰到对端非常慢(对端慢指的是对端处理TCP包的速度变慢，比如对端负载特别高的时候，这个时候如果还是不断地写数据，这个buffer就会不断地增长，最后就会由于占用大量的内存引起服务器处理缓慢，进而可能引起崩溃。这时WRITE_BUFFER_WATER_MARK就派上用场了，它规定了高低水位线。当Buffer的数据超过高水位线时就停止写入数据，设置channel的isWritable为false。等到buffer中的数据由于被消费而低于低水位线时设置channel的isWritable为true，又可以重新接受写入的数据。所以设置了这个参数之后，对应用的要求是，每次写数据时先判断channel的isWritable，在 true时才进行写入。
        serverBootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 128 * 1024));
        // 当设置为true的时候，TCP会实现监控连接是否有效，当连接处于空闲状态的时候，超过了2个小时，本地的TCP实现会发送一个数据包给远程的 socket，如果远程没有发回响应，TCP会持续尝试11分钟，知道响应为止，如果在12分钟的时候还没响应，TCP尝试关闭socket连接。
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, serverOptions.isKeepALive());
        // 比如，某个服务器进程占用了TCP的80端口进行监听，此时再次监听该端口就会返回错误，使用该参数就可以解决问题，该参数允许共用该端口，这个在服务器程序中比较常使
        serverBootstrap.childOption(ChannelOption.SO_REUSEADDR, true);
        // Socket参数，关闭Socket的延迟时间，默认值为-1，表示禁用该功能。-1表示socket.close()方法立即返回，但OS底层会将发送缓冲区全部发送到对端。0表示socket.close()方法立即返回，OS放弃发送缓冲区的数据直接向对端发送RST包，对端收到复位错误。非0整数值表示调用socket.close()方法的线程被阻塞直到延迟时间到或发送缓冲区中的数据发送完毕，若超时，则对端会收到复位错误。
        serverBootstrap.childOption(ChannelOption.SO_LINGER, 0);
        // Netty参数，用于Channel分配接受Buffer的分配器，默认值为AdaptiveRecvByteBufAllocator.DEFAULT，是一个自适应的接受缓冲区分配器，能根据接受到的数据自动调节大小。可选值为FixedRecvByteBufAllocator，固定大小的接受缓冲区分配器。
        serverBootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        // 禁用nagle算法
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, serverOptions.isTcpNoDelay());
        // 表示客户端调用服务端接口的超时时间
        serverBootstrap.childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, serverOptions.getConnectTimeout());
        // 数据接受缓冲区的大小
        serverBootstrap.childOption(ChannelOption.SO_RCVBUF, serverOptions.getReceiveBufferSize());
        // 数据发送缓冲区的大小
        serverBootstrap.childOption(ChannelOption.SO_SNDBUF, serverOptions.getSendBufferSize());
        serverBootstrap.childHandler(createNetServerChannelInitializer(serverOptions.getWebSocketPath()));

        if (port == 0) {
            port = serverOptions.getListenPort();
        }
        listen();
    }

    private ChannelInitializer<Channel> createNetServerChannelInitializer(String webSocketPath) {

        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                DefaultEventLoopGroup defLoopGroup = new DefaultEventLoopGroup(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
                    private AtomicInteger index = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "DEFAULT_EVENT_LOOP_GROUP_" + index.incrementAndGet());
                    }
                });
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new HttpServerCodec());
                pipeline.addLast(new HttpObjectAggregator(64 * 1024));
                // 设计思路是，服务器在一定时间内发现连接读空闲，则再移除对应的channel，当然了客户端也要定时推送pong数据过来
                pipeline.addLast(new IdleStateHandler(60, 0, 0));
                pipeline.addLast(new AuthHandler(webSocketPath, applicationEventPublisher));
                // WebSocketServerProtocolHandler 处理了所有委托管理的 WebSocket 帧类型以 及升级握手本身。如果握手成功，那么所需的ChannelHandler将会被添加到ChannelPipeline 中，而那些不再需要的ChannelHandler则将会被移除
                pipeline.addLast(new WebSocketServerProtocolHandler(webSocketPath));
                pipeline.addLast(new MessageHandler(applicationEventPublisher));
            }
        };
    }

    private void listen() {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(port);
        try {
            ChannelFuture channelFuture = serverBootstrap.bind(inetSocketAddress).sync();
            if (channelFuture.isSuccess()) {
                this.channel = channelFuture.channel();
                log.info("服务器地址[{}]开启成功！！！", channel);
            }
        } catch (Throwable e) {
            shutdown();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void shutdown() {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private boolean isLinux() {

        String osName = System.getProperty("os.name");
        if (osName != null && osName.toLowerCase().contains("linux")) {
            return true;
        }

        return false;
    }
}
