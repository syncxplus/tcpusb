package io.github.syncxplus.tcpusb;

import com.android.ddmlib.AdbHelper;
import com.android.ddmlib.IDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

class Protocol {
    private static final Logger LOGGER = LoggerFactory.getLogger(Protocol.class);

    static final int HEADER_LENGTH = 24;
    static final int COMMAND_LENGTH = 4;
    static final int VERSION = 0x01000000;
    static final int MAXDATA = 256 * 1024;

    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static String getCommandString(int command) {
        ByteBuffer packet = ByteBuffer.allocate(COMMAND_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(command);
        return new String(packet.array(), StandardCharsets.UTF_8);
    }

    static byte[] generateConnect(IDevice device) {
        String productName = device.getProperty("ro.product.device");
        String productModel = device.getProperty("ro.product.model");
        String deviceName = device.getProperty("ro.product.manufacturer");
        String deviceIdStr = "device::ro.product.name=" + productName + ";ro.product.model=" + productModel + ";ro.product.device=" + deviceName + "\0";
        return generateMessage(A_CNXN, VERSION, MAXDATA, deviceIdStr.getBytes());
    }

    static byte[] generateToken(int length){
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append(Integer.toHexString(r.nextInt()));
        }

        return sb.toString().substring(0, length).getBytes();
    }

    static byte[] generateAuth(int authType) {
        return generateMessage(A_AUTH, authType, 0, generateToken(20));
    }

    static byte[] generateWrite(int localId, int remoteId, byte[] data) {
        return generateMessage(A_WRTE, localId, remoteId, data);
    }

    static byte[] generateClose(int localId, int remoteId) {
        return generateMessage(A_CLSE, localId, remoteId, null);
    }

    static byte[] generateReady(int localId, int remoteId) {
        return generateMessage(A_OKAY, localId, remoteId, null);
    }

    static byte[] generateSync(int sycnToken) {
        return generateMessage(A_SYNC, 1, sycnToken, null);
    }

    private static int getPayloadChecksum(byte[] payload) {
        int checksum = 0;
        for (byte b : payload) {
            if (b >= 0) {
                checksum += b;
            }
            else {
                checksum += (b + 256);
            }
        }
        return checksum;
    }

    private static boolean validateMessage(AdbMessage msg) {
        /* Magic is cmd ^ 0xFFFFFFFF */
        if (msg.command != (msg.magic ^ 0xFFFFFFFF)) {
            return false;
        }
        return msg.payloadLength == 0 || getPayloadChecksum(msg.payload) == msg.checksum;
    }

    private static void dumpMessage(int command, int arg0, int arg1, byte[] payload) {
        int payloadLength =  (payload == null) ? 0 : payload.length;
        StringBuilder sb = new StringBuilder(String.format("[%dbytes]", payloadLength));
        switch (command) {
            case A_CNXN:
            case A_OPEN:
                sb.append(" ").append(AdbHelper.replyToString(payload));
        }
        LOGGER.debug("[{}, {}] {} {}", arg0, arg1, getCommandString(command), sb.toString());
    }

    private static void dumpMessage(AdbMessage message) {
        dumpMessage(message.command, message.arg0, message.arg1, message.payload);
    }

    /**
     * This function generates an ADB message given the fields.
     * struct message {
     *         unsigned command;       // command identifier constant
     *         unsigned arg0;          // first argument
     *         unsigned arg1;          // second argument
     *         unsigned data_length;   // length of payload (0 is allowed)
     *         unsigned data_check;    // checksum of data payload
     *         unsigned magic;         // command ^ 0xffffffff
     * };
     * @param cmd Command identifier
     * @param arg0 First argument
     * @param arg1 Second argument
     * @param payload Data payload
     * @return Byte array containing the message
     */
    static byte[] generateMessage(int cmd, int arg0, int arg1, byte[] payload) {
        dumpMessage(cmd, arg0, arg1, payload);

        ByteBuffer message;
        
        if (payload != null) {
            message = ByteBuffer.allocate(HEADER_LENGTH + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        } else {
            message = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        }
        
        message.putInt(cmd);
        message.putInt(arg0);
        message.putInt(arg1);

        if (payload != null) {
            message.putInt(payload.length);
            message.putInt(getPayloadChecksum(payload));
        } else {
            message.putInt(0);
            message.putInt(0);
        }
        
        message.putInt(cmd ^ 0xFFFFFFFF);
        
        if (payload != null) {
            message.put(payload);
        }

        return message.array();
    }

    final static class AdbMessage {
        int command;
        int arg0;
        int arg1;
        int payloadLength;
        int checksum;
        int magic;
        byte[] payload;

        static AdbMessage parse(InputStream in) throws IOException {
            AdbMessage msg = new AdbMessage();
            ByteBuffer packet = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);

            /* Read the header first */
            int dataRead = 0;
            do {
                int bytesRead = in.read(packet.array(), dataRead, HEADER_LENGTH - dataRead);
                if (bytesRead < 0) {
                    return null;
                } else {
                    dataRead += bytesRead;
                }
            } while (dataRead < HEADER_LENGTH);

            msg.command = packet.getInt();
            msg.arg0 = packet.getInt();
            msg.arg1 = packet.getInt();
            msg.payloadLength = packet.getInt();
            msg.checksum = packet.getInt();
            msg.magic = packet.getInt();

            /* Read the payload */
            if (msg.payloadLength != 0) {
                msg.payload = new byte[msg.payloadLength];
                dataRead = 0;
                do {
                    int bytesRead = in.read(msg.payload, dataRead, msg.payloadLength - dataRead);
                    if (bytesRead < 0) {
                        break;
                    } else {
                        dataRead += bytesRead;
                    }
                } while (dataRead < msg.payloadLength);
            }

            dumpMessage(msg);

            return msg;
        }
    }
}
