package com.ctrip.xpipe.redis.console.health;

import com.ctrip.xpipe.api.command.CommandFuture;
import com.ctrip.xpipe.api.command.CommandFutureListener;
import com.ctrip.xpipe.api.endpoint.Endpoint;
import com.ctrip.xpipe.api.pool.SimpleObjectPool;
import com.ctrip.xpipe.netty.commands.NettyClient;
import com.ctrip.xpipe.pool.BorrowObjectException;
import com.ctrip.xpipe.pool.FixedObjectPool;
import com.ctrip.xpipe.pool.XpipeNettyClientKeyedObjectPool;
import com.ctrip.xpipe.redis.console.health.redisconf.Callbackable;
import com.ctrip.xpipe.redis.core.protocal.cmd.*;
import com.ctrip.xpipe.redis.core.protocal.cmd.pubsub.PublishCommand;
import com.ctrip.xpipe.redis.core.protocal.cmd.pubsub.SubscribeCommand;
import com.ctrip.xpipe.redis.core.protocal.pojo.Role;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.ctrip.xpipe.redis.console.spring.ConsoleContextConfig.REDIS_COMMAND_EXECUTOR;


/**
 * @author marsqing
 *         <p>
 *         Dec 1, 2016 2:28:43 PM
 */
public class RedisSession {

    private static Logger logger = LoggerFactory.getLogger(RedisSession.class);

    public static final String KEY_SUBSCRIBE_TIMEOUT_SECONDS = "SUBSCRIBE_TIMEOUT_SECONDS";

    private int waitResultSeconds = 2;

    private int subscribConnsTimeoutSeconds = Integer.parseInt(System.getProperty(KEY_SUBSCRIBE_TIMEOUT_SECONDS, "60"));

    private Endpoint endpoint;

    private ConcurrentMap<String, PubSubConnectionWrapper> subscribConns = new ConcurrentHashMap<>();

    private AtomicReference<StatefulRedisConnection<String, String>> nonSubscribeConn = new AtomicReference<>();

    @Resource
    private XpipeNettyClientKeyedObjectPool keyedNettyClientPool;

    @Resource(name=REDIS_COMMAND_EXECUTOR)
    private ScheduledExecutorService scheduled;

    private SimpleObjectPool<NettyClient> clientPool;

    public RedisSession(Endpoint endpoint) {
        this.endpoint = endpoint;
        this.clientPool = keyedNettyClientPool.getKeyPool(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()));
    }

    public void check() {

        for (Map.Entry<String, PubSubConnectionWrapper> entry : subscribConns.entrySet()) {

            String channel = entry.getKey();
            PubSubConnectionWrapper pubSubConnectionWrapper = entry.getValue();

            if (System.currentTimeMillis() - pubSubConnectionWrapper.getLastActiveTime() > subscribConnsTimeoutSeconds * 1000) {

                logger.info("[check][connectin inactive for a long time, force reconnect]{}, {}", subscribConns, endpoint);
                pubSubConnectionWrapper.closeAndClean();
                subscribConns.remove(channel);

                subscribeIfAbsent(channel, pubSubConnectionWrapper.getCallback());
            }
        }

    }

    public synchronized void closeSubscribedChannel(String channel) {

        PubSubConnectionWrapper pubSubConnectionWrapper = subscribConns.get(channel);
        if (pubSubConnectionWrapper != null) {
            logger.info("[closeSubscribedChannel]{}, {}", endpoint, channel);
            pubSubConnectionWrapper.closeAndClean();
            subscribConns.remove(channel);
        }
    }

    public synchronized void subscribeIfAbsent(String channel, SubscribeCallback callback) {

        PubSubConnectionWrapper pubSubConnectionWrapper = subscribConns.get(channel);
        if (pubSubConnectionWrapper != null) {
            pubSubConnectionWrapper.replace(callback);
            return;
        }

        if (!subscribConns.containsKey(channel)) {
            SubscribeCommand command = new SubscribeCommand(endpoint.getHost(), endpoint.getPort(), scheduled, channel);
            PubSubConnectionWrapper wrapper = new PubSubConnectionWrapper(command.execute(), callback);
            subscribConns.put(channel, wrapper);
        }
    }

    public synchronized void publish(String channel, String message) {
        PublishCommand pubCommand = new PublishCommand(clientPool, scheduled, channel, message);
        pubCommand.execute().addListener(new CommandFutureListener<Object>() {
            @Override
            public void operationComplete(CommandFuture<Object> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    logger.warn("Error publish to redis {}", endpoint);
                }
            }
        });
    }

    public void ping(final PingCallback callback) {
        // if connect has been established
        PingCommand pingCommand = new PingCommand(clientPool, scheduled);
        pingCommand.execute().addListener(new CommandFutureListener<String>() {
            @Override
            public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                if(commandFuture.isSuccess()) {
                    callback.pong(commandFuture.get());
                } else {
                    callback.fail(commandFuture.cause());
                }
            }
        });
    }

    public void role(RollCallback callback) {
        new RoleCommand(clientPool, scheduled).execute().addListener(new CommandFutureListener<Role>() {
            @Override
            public void operationComplete(CommandFuture<Role> commandFuture) throws Exception {
                if(commandFuture.isSuccess()) {
                    callback.role(commandFuture.get().getServerRole().name());
                } else {
                    callback.fail(commandFuture.cause());
                }
            }
        });
    }

    public void configRewrite(BiConsumer<String, Throwable> consumer) {
        new ConfigRewrite(clientPool, scheduled).execute().addListener(new CommandFutureListener<String>() {
            @Override
            public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                if(commandFuture.isSuccess()) {
                    consumer.accept(commandFuture.get(), null);
                } else {
                    consumer.accept(null, commandFuture.cause());
                }
            }
        });
    }

    public String roleSync() throws InterruptedException, ExecutionException, TimeoutException {

        return new RoleCommand(clientPool, waitResultSeconds * 1000, true, scheduled).execute().get().getServerRole().name();

    }

    public void info(final String infoSection, Callbackable<String> callback) {
        new InfoCommand(clientPool, infoSection, scheduled).execute()
                .addListener(new CommandFutureListener<String>() {
                    @Override
                    public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                        if(!commandFuture.isSuccess()) {
                            callback.fail(commandFuture.cause());
                        } else {
                            callback.success(commandFuture.get());
                        }
                    }
                });
    }


    public void infoServer(Callbackable<String> callback) {
        String section = "server";
        info(section, callback);
    }

    public void infoReplication(Callbackable<String> callback) {
        String infoReplicationSection = "replication";
        info(infoReplicationSection, callback);
    }

    public void isDiskLessSync(Callbackable<Boolean> callback) {
        new ConfigGetCommand.ConfigGetDisklessSync(clientPool, scheduled)
                .execute().addListener(new CommandFutureListener<Boolean>() {
            @Override
            public void operationComplete(CommandFuture<Boolean> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    callback.fail(commandFuture.cause());
                } else {
                    callback.success(commandFuture.get());
                }
            }
        });
    }

    @Override
    public String toString() {
        return String.format("%s", endpoint.toString());
    }

    public interface RollCallback {

        void role(String role);

        void fail(Throwable e);
    }

    public interface SubscribeCallback {

        void message(String channel, String message);

        void fail(Throwable e);
    }

    public class PubSubConnectionWrapper {

        private Long lastActiveTime = System.currentTimeMillis();
        private AtomicReference<SubscribeCallback> callback = new AtomicReference<>();

        private AtomicReference<CommandFuture<Object>> subscribeCommandFuture = new AtomicReference<>();

        public PubSubConnectionWrapper(CommandFuture<Object> commandFuture, SubscribeCallback callback) {
            this.subscribeCommandFuture.set(commandFuture);
            this.callback.set(callback);
        }

        public void closeAndClean() {
            subscribeCommandFuture.get().cancel(true);
        }

        public CommandFuture<Object> getSubscribeCommandFuture() {
            return subscribeCommandFuture.get();
        }

        public SubscribeCallback getCallback() {
            return callback.get();
        }

        public void replace(SubscribeCallback callback) {
            this.callback.set(callback);
        }

        public void setLastActiveTime(Long lastActiveTime) {
            this.lastActiveTime = lastActiveTime;
        }

        public Long getLastActiveTime() {
            return lastActiveTime;
        }
    }

    public void closeConnection() {
        try {
            nonSubscribeConn.get().close();
        } catch (Exception ignore) {}
        for(PubSubConnectionWrapper connectionWrapper : subscribConns.values()) {
            try {
                connectionWrapper.closeAndClean();
            } catch (Exception ignore) {}
        }
    }

    public RedisSession setKeyedNettyClientPool(XpipeNettyClientKeyedObjectPool keyedNettyClientPool) {
        this.keyedNettyClientPool = keyedNettyClientPool;
        return this;
    }
}
