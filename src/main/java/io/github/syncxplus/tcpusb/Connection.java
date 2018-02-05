package io.github.syncxplus.tcpusb;

import com.android.ddmlib.AdbHelper;
import com.android.ddmlib.IDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

class Connection {
    private final static Logger LOGGER = LoggerFactory.getLogger(Connection.class);
    private final static String STRING_EOF = new String(new byte[] {13, 13, 10});
    private final static String STRING_AUTH_REQUIRE = "AUTH Required!\nYou can find the auth command on the web.\nIt looks like: adb shell auth ...";
    private final static String STRING_AUTH_FAILURE = "AUTH FAILURE";
    private final static String STRING_AUTH_SUCCESS = "AUTH SUCCESS";
    private final static Pattern FORBIDDEN_PATTERN = Pattern.compile("(^|reboot:|shell:|.*\\s)($|kill|reboot|rm|su)($|\\s.*)");
    private Map<Integer, Service> services = new ConcurrentHashMap<>();
    private final IDevice device;
    private final String serial;
    private final String key;
    private final Server server;
    private final Socket client;
    private byte[] token;
    private boolean connected;
    private boolean authorized;
    private boolean status;
    private int maxLoad;
    private int syncToken;
    private byte[] clientSignature;

    Connection(IDevice device, String key, Server server, Socket client) {
        this.device = device;
        this.serial = device.getSerialNumber();
        this.key = key;
        this.server = server;
        this.client = client;
    }

    void connect() {
        connected = true;
        new Thread(this::clientMessageHandler).start();
    }

    void disconnect() {
        services.forEach((i, service) -> service.close());
        services.clear();
        try {
            this.client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        connected = false;
    }

    void removeService(int id) {
        Service service = services.remove(id);
        if (service != null) {
            service.close();
        }
    }

    void reply(byte[] msg) throws IOException {
        client.getOutputStream().write(msg);
    }

    private void reject(int localId, int remoteId, String reason) {
        try {
            reply(Protocol.generateReady(localId, remoteId));
            reply(Protocol.generateWrite(localId, remoteId, reason.getBytes()));
            reply(Protocol.generateClose(localId, remoteId));
        } catch (IOException e) {
            //do nothing
        }
    }

    private void handleAuth(Protocol.AdbMessage message) throws IOException {
        int authType = message.arg0;
        switch (authType) {
            case Protocol.AUTH_SIGNATURE:
                if (null == clientSignature) {
                    clientSignature = message.payload;
                }
                reply(Protocol.generateAuth(Protocol.AUTH_TOKEN));
                break;
            case Protocol.AUTH_RSAPUBLICKEY:
                if (null == clientSignature) {
                    LOGGER.error("{} Public key sent before signature", serial);
                    server.removeClient(this);
                } else if (message.payload.length < 2) {
                    LOGGER.error("{} Empty RSA public key", serial);
                    server.removeClient(this);
                } else{
                    if (AndroidPubKey.verify(new String(message.payload), token, clientSignature)) {
                        reply(Protocol.generateConnect(device));
                        authorized = true;
                    } else {
                        LOGGER.error("{} RSA public key verification failed.", serial);
                        server.removeClient(this);
                    }
                }
                break;
            default:
                LOGGER.error("{} Auth type not supported: {}", serial, authType);
                break;
        }
    }

    private boolean isKeyAuthorised(int localId, int remoteId, String content) {
        if (!status) {
            if (content.startsWith("shell:auth")) {
                if (key != null && content.endsWith(key)) {
                    status = true;
                    reject(localId, remoteId, STRING_AUTH_SUCCESS + STRING_EOF);
                } else {
                    reject(localId, remoteId, STRING_AUTH_FAILURE + STRING_EOF);
                }
            } else {
                reject(localId, remoteId, STRING_AUTH_REQUIRE + STRING_EOF);
            }
            return false;//leave it false no matter auth success or failure, it will return true next time
        } else {
            return true;
        }
    }

    private boolean isAllowed(int localId, int remoteId, String command) {
        if (FORBIDDEN_PATTERN.matcher(command).matches()) {
            reject(localId, remoteId, String.format("[%s] is not allowed" + STRING_EOF, command));
            return false;
        } else {
            return true;
        }
    }

    private void clientMessageHandler() {
        while (connected) {
            try {
                Protocol.AdbMessage message = Protocol.AdbMessage.parse(client.getInputStream());
                if (message != null) {
                    switch (message.command) {
                        case Protocol.A_SYNC:
                            reply(Protocol.generateSync(syncToken));
                            syncToken += 1;
                            break;
                        case Protocol.A_CNXN:
                            maxLoad = Math.min(message.arg1, Protocol.MAXDATA);
                            token = Protocol.generateToken(20);
                            reply(Protocol.generateMessage(Protocol.A_AUTH, Protocol.AUTH_TOKEN, 0, token));
                            break;
                        case Protocol.A_AUTH:
                            handleAuth(message);
                            break;
                        case Protocol.A_OPEN:
                            int remoteId = message.arg0;
                            int localId = remoteId + 1;
                            if (!authorized) {
                                LOGGER.error("{} connection not authorized", serial);
                                break;
                            }
                            if (message.payloadLength == 0) {
                                LOGGER.error("{} empty payload", serial);
                                break;
                            }
                            String payload = AdbHelper.replyToString(message.payload).trim();
                            if (isKeyAuthorised(localId, remoteId, payload) && isAllowed(localId, remoteId, payload)) {
                                Service service = new Service(localId, remoteId, this, serial, maxLoad);
                                services.put(remoteId, service);
                                service.handle(message);
                            }
                            break;
                        case Protocol.A_WRTE:
                        case Protocol.A_OKAY:
                        case Protocol.A_CLSE:
                            if (!authorized) {
                                LOGGER.error("{} connection not authorized", serial);
                                break;
                            }
                            Service service = services.get(message.arg0);
                            if (service != null) {
                                service.handle(message);
                            } else {
                                LOGGER.info("{} ({}, {}) Received a packet to a service that may have been closed already", serial, message.arg0, message.arg1);
                            }
                            break;
                        default:
                            LOGGER.warn("{} ignore: {}", serial, Protocol.getCommandString(message.command));
                            break;
                    }
                } else {
                    LOGGER.error("{} adb client {}: disconnected", serial, client.getRemoteSocketAddress());
                    server.removeClient(this);
                }
            } catch (IOException e) {
                LOGGER.error("{} adb client {}: message error: {}", serial, client.getRemoteSocketAddress(), e);
                server.removeClient(this);
            }
        }
    }
}
