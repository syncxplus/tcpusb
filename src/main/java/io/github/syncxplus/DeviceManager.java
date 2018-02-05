package io.github.syncxplus;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log;
import io.github.syncxplus.tcpusb.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component(value = "deviceChangeListener")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class DeviceManager implements AndroidDebugBridge.IDeviceChangeListener, ApplicationListener<ContextRefreshedEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceManager.class);
    private static final ConcurrentMap<String, IDevice> deviceMap = new ConcurrentHashMap<>();
    private static boolean isStarted;
    private static int port;

    public static Map<String, IDevice> getDevices() {
        return deviceMap;
    }

    public boolean isStarted() {
        return isStarted;
    }

    private synchronized void start() {
        if (!isStarted) {
            isStarted = true;
            port = 8080 + (int) Math.round(Math.random() * 100);
            DdmPreferences.setLogLevel(Log.LogLevel.VERBOSE.getStringValue());
            AndroidDebugBridge.initIfNeeded(false);
            AndroidDebugBridge.createBridge();
            AndroidDebugBridge.addDeviceChangeListener(this);
            LOGGER.info("Listening devices ...");
        } else {
            LOGGER.warn("DeviceManager already started");
        }
    }

    @Override
    public void deviceConnected(IDevice device) {
        String serial = device.getSerialNumber();
        LOGGER.info("device connected {}", serial);
        if (deviceMap.put(serial, device) != null) {
            LOGGER.info("device replaced {}", serial);
        } else {
            LOGGER.info("device added {}", serial);
        }
        Server server = Server.getInstance(device);
        server.setKey(serial);
        server.setPort(++port);
        server.stop().start();
    }

    @Override
    public void deviceDisconnected(IDevice device) {
        String serial = device.getSerialNumber();
        LOGGER.info("device disconnected {}", serial);
        if (deviceMap.remove(serial) != null) {
            LOGGER.info("device removed {}", serial);
        } else {
            LOGGER.info("device not existed {}", serial);
        }
        Server server = Server.removeInstance(serial);
        if (server != null) {
            server.stop();
        }
    }

    @Override
    public void deviceChanged(IDevice device, int changeMask) {
        String serial = device.getSerialNumber();
        LOGGER.info("device changed {}: {}", serial, changeMask);
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        LOGGER.info(event.toString());
        start();
    }
}
