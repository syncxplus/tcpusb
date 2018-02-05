package io.github.syncxplus.tcpusb;

import com.android.ddmlib.IDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Server {
    private final static Logger LOGGER = LoggerFactory.getLogger(Server.class);
    private final static ConcurrentMap<String, Server> instances = new ConcurrentHashMap<>();
    private final List<Connection> connections = new ArrayList<>();
    private final IDevice device;
    private ServerSocket server;
    private boolean running;
    private String key;
    private int port;

    public static Server getInstance(IDevice device) {
        String serial = device.getSerialNumber();
        if (!instances.containsKey(serial)) {
            instances.put(serial, new Server(device));
        }
        return instances.get(serial);
    }

    public static Server removeInstance(String serial) {
        return instances.remove(serial);
    }

    public synchronized Server start() {
        if (!running) {
            running = true;
            new Thread(this::createAdbServerThread).start();
        }
        return this;
    }

    public synchronized Server stop() {
        if (running) {
            for(Connection connection : connections) {
                connection.disconnect();
            }
            try {
                if (server != null) {
                    server.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            running = false;
        }
        return this;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setPort(int port) {
        this.port = port;
    }

    private Server(IDevice device){
        this.device = device;
    }

    private void createAdbServerThread() {
        assert port != 0;
        assert key != null;
        String serial = device.getSerialNumber();
        try {
            server = new ServerSocket(port);
            LOGGER.info("{} adb server on port {} start success", serial, port);
        } catch (IOException e) {
            stop();
            LOGGER.warn("{} adb server on port {} start error", serial, port, e);
        }
        while (server != null && !server.isClosed() && running) {
            try {
                Socket client = server.accept();
                LOGGER.debug("{} new client {}", serial, client.getRemoteSocketAddress());
                Connection adbConnection = new Connection(device, key, this, client);
                adbConnection.connect();
                connections.add(adbConnection);
            } catch (Exception e) {
                LOGGER.warn("{} adb server on port {} error", serial, port);
            }
        }
    }

    synchronized void removeClient(Connection c) {
        try {
            c.disconnect();
            connections.remove(c);
        } catch (Exception e) {
            //do nothing
        }
    }
}
