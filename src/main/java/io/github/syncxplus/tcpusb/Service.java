package io.github.syncxplus.tcpusb;

import com.android.ddmlib.AdbHelper;
import com.android.ddmlib.AndroidDebugBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

class Service {
    private final static Logger LOGGER = LoggerFactory.getLogger(Service.class);
    private final static int READ_TIMEOUT = 10;
    private final static int OPEN_TIMEOUT = 500;
    private final int localId;
    private final int remoteId;
    private final int maxDataLength;
    private final Connection connection;
    private final String serial;
    private String service;
    private Socket adbServer;
    private byte[] buff;
    private boolean ended;
    private boolean opened;
    private boolean waitAck;
    private final Object lock = new Object();

    Service(int localId, int remoteId, Connection connection, String serial, int maxDataLength) {
        this.localId = localId;
        this.remoteId = remoteId;
        this.connection = connection;
        this.serial = serial;
        this.maxDataLength = maxDataLength;
        buff = new byte[maxDataLength];
    }

    void close() {
        try {
            if (adbServer != null) {
                adbServer.close();
                adbServer = null;
            }
            if (!ended) {
                ended = true;
                int localId = opened ? this.localId : 0;
                connection.reply(Protocol.generateClose(localId, remoteId));
            }
        } catch (IOException e) {
            //do nothing
        }
    }

    void handle(Protocol.AdbMessage message) {
        try {
            switch (message.command) {
                case Protocol.A_OPEN:
                    handleOpenPacket(message);
                    break;
                case Protocol.A_OKAY:
                    handleOkayPacket();
                    break;
                case Protocol.A_WRTE:
                    handleWritePacket(message);
                    break;
                case Protocol.A_CLSE:
                    handleClosePacket(message);
                    break;
                default:
                    LOGGER.error("{} Unexpected message {}", serial, message.command);
            }
        } catch (IOException e) {
            LOGGER.debug("{} Handle packet exception", serial, e);
            connection.removeService(remoteId);
        }
    }

    private void handleOpenPacket(Protocol.AdbMessage message) throws IOException {
        adbServer = new Socket(AndroidDebugBridge.getSocketAddress().getAddress(), AndroidDebugBridge.getSocketAddress().getPort());
        adbServer.setTcpNoDelay(true);
        adbServer.setSoTimeout(READ_TIMEOUT);
        write(AdbHelper.formAdbRequest("host:transport:" + serial));
        if (isOkay() && !ended) {
            service = AdbHelper.replyToString(message.payload).trim();
            write(AdbHelper.formAdbRequest(service));
            String reply = readOpenStatus(4);
            if (reply != null) {
                if (reply.equals("OKAY")) {//open success
                    connection.reply(Protocol.generateReady(localId, remoteId));
                    opened = true;
                    new Thread(() -> {
                        try {
                            int ret = readAndReply();
                            while (ret != -1) {
                                synchronized (lock) {
                                    ret = readAndReply();
                                }
                            }
                        } catch (Exception e) {
                            //do nothing
                        }
                        LOGGER.debug("{} Ending service {}", serial, service);
                        connection.removeService(remoteId);
                    }).start();
                    return;
                } else if (reply.equals("FAIL")) {
                    connection.reply(Protocol.generateWrite(localId, remoteId, readError().getBytes()));
                }
            }
        }
        LOGGER.error("{} Failed to open service", serial);
        connection.removeService(remoteId);
    }

    private void handleOkayPacket() throws IOException {
        if (!ended) {
            synchronized (lock) {
                waitAck = false;
                readAndReply();
            }
        }
    }

    private void handleWritePacket(Protocol.AdbMessage message) throws  IOException {
        if (!ended) {
            if (message.payloadLength > 0) {
                write(message.payload);
            }
            connection.reply(Protocol.generateReady(localId, remoteId));
            if (AdbHelper.replyToString(message.payload).startsWith("QUIT")) {
                connection.removeService(remoteId);
            }
        }
    }

    private void handleClosePacket(Protocol.AdbMessage message) {
        if (!ended) {
            connection.removeService(message.arg0);
        }
    }

    private boolean isOkay() throws IOException {
        String reply = readOpenStatus(4);
        if ("OKAY".equals(reply)) {
            return true;
        } else if ("FAIL".equals(reply)){
            LOGGER.error("{} FAIL: {}", serial, readError());
        }
        return false;
    }

    private String readError() throws IOException {
        String error = null;
        String length = readOpenStatus(4);
        if (length != null) {
            try {
                error = readOpenStatus(Integer.parseInt(length, 16));
            } catch (Exception e) {
                //do nothing
            }
        }
        return error != null ? error : "";
    }

    private String readOpenStatus(int length) throws IOException {
        if (!ended) {
            length = Math.min(length, maxDataLength);
            int read, count = 0;
            byte[] bytes = new byte[length];
            InputStream is = adbServer.getInputStream();
            int timeout = 0;
            while (is.available() == 0) {
                if (timeout < OPEN_TIMEOUT) {
                    timeout += 100;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        //do nothing
                    }
                } else {
                    break;
                }
            }
            try {
                while (count < length && (read = adbServer.getInputStream().read(bytes, count, length - count)) > 0) {
                    count += read;
                }
            } catch (SocketTimeoutException e) {
                //do nothing
            }
            if (count > 0) {
                if (count != length) {
                    return AdbHelper.replyToString(Arrays.copyOf(bytes, count));
                } else {
                    return AdbHelper.replyToString(bytes);
                }
            }
        }
        return null;
    }

    private int readAndReply() throws IOException {
        if (!ended) {
            int read = 0, count = 0;
            if (!waitAck) {
                try {
                    while (count < maxDataLength && (read = adbServer.getInputStream().read(buff, count, maxDataLength - count)) > 0) {
                        count += read;
                    }
                } catch (SocketTimeoutException e) {
                    //do nothing
                }
                if (count > 0) {
                    byte[] bytes;
                    if (count != maxDataLength) {
                        bytes = Arrays.copyOf(buff, count);
                    } else {
                        bytes = buff;
                    }
                    connection.reply(Protocol.generateWrite(localId, remoteId, bytes));
                    waitAck = true;
                }
            }
            return read;
        } else {
            return -1;
        }
    }

    private void write(byte[] data) throws IOException {
        if (adbServer != null && data != null) {
            adbServer.getOutputStream().write(data);
        }
    }
}
