package de.feelspace.fslibtest;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import de.feelspace.fslib.BeltBatteryStatus;
import de.feelspace.fslib.BeltButtonPressEvent;
import de.feelspace.fslib.BeltCommandListener;
import de.feelspace.fslib.BeltCommunicationController;
import de.feelspace.fslib.BeltConnectionInterface;
import de.feelspace.fslib.BeltConnectionState;
import de.feelspace.fslib.BeltMode;
import de.feelspace.fslib.BeltOrientation;
import de.feelspace.fslib.GattConnectionState;
import de.feelspace.fslib.GattController;
import de.feelspace.fslib.NavigationEventListener;
import de.feelspace.fslib.NavigationState;

public class AdvancedBeltController implements GattController.GattEventListener,
        BeltCommandListener {
    // Debug
    @SuppressWarnings("unused")
    private static final String DEBUG_TAG = "FeelSpace-Debug";
    @SuppressWarnings("unused")
    private static final boolean DEBUG = true;

    private final @NonNull BeltConnectionInterface connectionInterface;
    private final @NonNull GattController gattController;

    private final ArrayList<AdvancedBeltListener> listeners = new ArrayList<>();

    // Belt mode for calibration
    private BeltMode beltMode = BeltMode.UNKNOWN;

    // Characteristics
    private @Nullable BluetoothGattCharacteristic paramRequestCharacteristic;
    private @Nullable BluetoothGattCharacteristic paramNotificationCharacteristic;
    private @Nullable BluetoothGattCharacteristic sensorNotificationCharacteristic;
    private @Nullable BluetoothGattCharacteristic sensorCommandCharacteristic;
    private @Nullable BluetoothGattCharacteristic debugInputCharacteristic;
    private @Nullable BluetoothGattCharacteristic debugOutputCharacteristic;

    // Notification state
    private boolean rawSensorNotificationsEnabled = false;
    private boolean debugNotificationsEnabled = false;

    // Sensor calibration
    private final Float[] magOffsets = {null, null, null};
    private final Float[] magGains = {null, null, null};
    private Float magError = null;
    private final Float[] gyroOffsets = {null, null, null};
    private Integer gyroStatus = null;

    // Raw sensor notification sequence
    private Integer sensorSequence = null;

    // Request IDs
    private final static int START_SENSOR_NOTIFICATION_REQUEST_ID = 1;
    private final static int MAG_OFFSET_REQUEST_ID = 2;
    private final static int MAG_GAIN_REQUEST_ID = 3;
    private final static int MAG_ERROR_REQUEST_ID = 4;
    private final static int GYRO_OFFSET_REQUEST_ID = 5;
    private final static int GYRO_STATUS_REQUEST_ID = 6;

    public AdvancedBeltController(@NonNull BeltConnectionInterface connectionInterface) {
        this.connectionInterface = connectionInterface;
        gattController = ((BeltCommunicationController)
                connectionInterface.getCommunicationInterface()).getGattController();
        gattController.addGattEventListener(this);
        connectionInterface.getCommandInterface().addCommandListener(this);
    }

    public void addAdvancedBeltListener(AdvancedBeltListener listener) {
        synchronized (this) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeAdvancedBeltListener(AdvancedBeltListener listener) {
        synchronized (this) {
            listeners.remove(listener);
        }
    }

    public void setRawSensorNotificationsEnable(boolean enable) {
        // Enable notifications, and send command for starting notifications
        if (connectionInterface.getState() == BeltConnectionState.STATE_CONNECTED) {
            if (sensorNotificationCharacteristic == null || sensorCommandCharacteristic == null) {
                Log.e(DEBUG_TAG, "AdvancedBeltController: Missing sensor characteristic!");
            } else {
                // Enable/Disable notification
                if (gattController.setCharacteristicNotification(
                        sensorNotificationCharacteristic, enable)) {
                    if (enable) {
                        // Command to start notifications
                        if (!gattController.request(
                                sensorCommandCharacteristic,
                                sensorNotificationCharacteristic,
                                new byte[] {0x01, 0x01},
                                new Byte[] {0x01},
                                START_SENSOR_NOTIFICATION_REQUEST_ID
                        )) {
                            Log.e(DEBUG_TAG, "AdvancedBeltController: Unable to send sensor command!");
                        }
                    }
                } else {
                    Log.e(DEBUG_TAG, "AdvancedBeltController: Unable to set sensor notification state!");
                }
            }
        }
    }

    public boolean areRawSensorNotificationsEnabled() {
        if (connectionInterface.getState() == BeltConnectionState.STATE_CONNECTED) {
            return rawSensorNotificationsEnabled;
        } else {
            return false;
        }
    }

    public void setDebugNotificationsEnable(boolean enable) {
        if (connectionInterface.getState() == BeltConnectionState.STATE_CONNECTED) {
            if (debugOutputCharacteristic == null) {
                Log.e(DEBUG_TAG, "AdvancedBeltController: No debug output char!");
            } else {
                if (!gattController.setCharacteristicNotification(
                        debugOutputCharacteristic, enable)) {
                    Log.e(DEBUG_TAG, "AdvancedBeltController: Unable to set debug output char notification state!");
                }
            }
        }
    }

    public boolean areDebugNotificationsEnabled() {
        if (connectionInterface.getState() == BeltConnectionState.STATE_CONNECTED) {
            return debugNotificationsEnabled;
        } else {
            return false;
        }
    }

    public void getSensorCalibration() {
        boolean ret = true;
        gattController.request(
                paramRequestCharacteristic,
                paramNotificationCharacteristic,
                new byte[] {0x10, 1, 13}, // Command ID, count, param ID
                new Byte[] {0x10, 13},
                MAG_OFFSET_REQUEST_ID
        );
        gattController.request(
                paramRequestCharacteristic,
                paramNotificationCharacteristic,
                new byte[] {0x10, 1, 14}, // Command ID, count, param ID
                new Byte[] {0x10, 14},
                MAG_GAIN_REQUEST_ID
        );
        gattController.request(
                paramRequestCharacteristic,
                paramNotificationCharacteristic,
                new byte[] {0x10, 1, 15}, // Command ID, count, param ID
                new Byte[] {0x10, 15},
                MAG_ERROR_REQUEST_ID
        );
        gattController.request(
                paramRequestCharacteristic,
                paramNotificationCharacteristic,
                new byte[] {0x10, 1, 16}, // Command ID, count, param ID
                new Byte[] {0x10, 16},
                GYRO_OFFSET_REQUEST_ID
        );
        gattController.request(
                paramRequestCharacteristic,
                paramNotificationCharacteristic,
                new byte[] {0x10, 1, 17}, // Command ID, count, param ID
                new Byte[] {0x10, 17},
                GYRO_STATUS_REQUEST_ID
        );
    }

    public Float[] getMagOffsets() {
        return magOffsets;
    }

    public Float[] getMagGains() {
        return magGains;
    }

    public Float getMagError() {
        return magError;
    }

    public Float[] getGyroOffsets() {
        return gyroOffsets;
    }

    public Integer getGyroStatus() {
        return gyroStatus;
    }

    private float getFloatFromBytes(byte b0, byte b1, byte  b2, byte b3) {
        byte[] bytes = {b0, b1, b2, b3};
        return ByteBuffer.wrap(bytes).getFloat();
    }

    private void informListenersCalibrationUpdated() {
        ArrayList<AdvancedBeltListener> targets;
        synchronized (this) {
            if (listeners.isEmpty()) {
                return;
            }
            targets = new ArrayList<>(listeners);
        }
        for (AdvancedBeltListener l: targets) {
            l.onSensorCalibrationUpdated();
        }
    }

    // MARK: Implementation of `GattEventListener`

    @Override
    public void onGattConnectionStateChange(GattConnectionState state) {
        if (state == GattConnectionState.GATT_CONNECTED) {
            // Retrieve characteristics
            paramRequestCharacteristic = gattController.getCharacteristic(
                    BeltCommunicationController.BELT_CONTROL_SERVICE_UUID,
                    BeltCommunicationController.PARAMETER_REQUEST_CHAR_UUID);
            paramNotificationCharacteristic = gattController.getCharacteristic(
                    BeltCommunicationController.BELT_CONTROL_SERVICE_UUID,
                    BeltCommunicationController.PARAMETER_NOTIFICATION_CHAR_UUID);
            sensorNotificationCharacteristic = gattController.getCharacteristic(
                    BeltCommunicationController.SENSOR_SERVICE_UUID,
                    BeltCommunicationController.SENSOR_PARAM_NOTIFICATION_CHAR_UUID);
            sensorCommandCharacteristic = gattController.getCharacteristic(
                    BeltCommunicationController.SENSOR_SERVICE_UUID,
                    BeltCommunicationController.SENSOR_PARAM_REQUEST_CHAR_UUID);
            debugInputCharacteristic = gattController.getCharacteristic(
                    BeltCommunicationController.DEBUG_SERVICE_UUID,
                    BeltCommunicationController.DEBUG_INPUT_CHAR_UUID);
            debugOutputCharacteristic = gattController.getCharacteristic(
                    BeltCommunicationController.DEBUG_SERVICE_UUID,
                    BeltCommunicationController.DEBUG_OUTPUT_CHAR_UUID);
            // Setup
            gattController.requestMtu(512);
            setDebugNotificationsEnable(true);
            getSensorCalibration();
        } else {
            // Reset state
            paramRequestCharacteristic = null;
            paramNotificationCharacteristic = null;
            sensorNotificationCharacteristic = null;
            sensorCommandCharacteristic = null;
            debugInputCharacteristic = null;
            debugOutputCharacteristic = null;
            rawSensorNotificationsEnabled = false;
            debugNotificationsEnabled = false;
            magOffsets[0] = null; magOffsets[1] = null; magOffsets[2] = null;
            magGains[0] = null; magGains[1] = null; magGains[2] = null;
            magError = null;
            gyroOffsets[0] = null; gyroOffsets[1] = null; gyroOffsets[2] = null;
            gyroStatus = null;
            sensorSequence = null;
        }
    }

    @Override
    public void onGattConnectionFailed() {

    }

    @Override
    public void onGattConnectionLost() {

    }

    @Override
    public void onCharacteristicNotificationSet(@NonNull BluetoothGattCharacteristic characteristic,
                                                boolean enable, boolean success) {
        if (characteristic == debugOutputCharacteristic) {
            // Set value
            debugNotificationsEnabled = enable;
            // Notify listeners
            ArrayList<AdvancedBeltListener> targets;
            synchronized (this) {
                if (listeners.isEmpty()) {
                    return;
                }
                targets = new ArrayList<>(listeners);
            }
            for (AdvancedBeltListener l: targets) {
                l.onDebugNotificationsStateChanged(enable);
            }

        } else if (characteristic == sensorNotificationCharacteristic) {
            if (success && !enable) { // NOTE: Code for starting notification in `onRequestCompleted`
                // Set value
                rawSensorNotificationsEnabled = false;
                // Notify listeners
                ArrayList<AdvancedBeltListener> targets;
                synchronized (this) {
                    if (listeners.isEmpty()) {
                        return;
                    }
                    targets = new ArrayList<>(listeners);
                }
                for (AdvancedBeltListener l: targets) {
                    l.onRawSensorNotificationsStateChanged(false);
                }
            }
        }

    }

    @Override
    public void onCharacteristicRead(@NonNull BluetoothGattCharacteristic characteristic,
                                     @Nullable byte[] value, boolean success) {

    }

    @Override
    public void onCharacteristicWrite(@NonNull BluetoothGattCharacteristic characteristic,
                                      @Nullable byte[] value, boolean success) {

    }

    @Override
    public void onCharacteristicChanged(@Nullable BluetoothGattCharacteristic characteristic,
                                        @Nullable byte[] value) {
        if (characteristic == null || value == null) {
            return;
        }
        if (characteristic == debugOutputCharacteristic) {
            if (value.length >= 5 && value[0] == (byte) 0xA0) {
                // Error notification
                int errorCode = (
                        ((value[4] & 0xFF) << 24) |
                        ((value[3] & 0xFF) << 16) |
                        ((value[2] & 0xFF) << 8) |
                                (0xFF & value[1]));
                // Notify listeners
                ArrayList<AdvancedBeltListener> targets;
                synchronized (this) {
                    if (listeners.isEmpty()) {
                        return;
                    }
                    targets = new ArrayList<>(listeners);
                }
                for (AdvancedBeltListener l: targets) {
                    l.onErrorNotified(errorCode);
                }
            }

        } else if (characteristic == sensorNotificationCharacteristic) {
            if (value.length >= 2 && value[0] == (byte) 0x01) {
                // Raw sensor record notification
                // Check sequence
                int seq = value[1] & 0xFF; // Unsigned byte
                if (sensorSequence != null && ((sensorSequence + 1) % 256) != seq) {
                    // Error in sequence
                    // Notify listeners
                    ArrayList<AdvancedBeltListener> targets;
                    synchronized (this) {
                        if (listeners.isEmpty()) {
                            return;
                        }
                        targets = new ArrayList<>(listeners);
                    }
                    for (AdvancedBeltListener l: targets) {
                        l.onRawSensorNotificationSequenceError();
                    }
                }
                sensorSequence = seq;
                // Retrieve records
                int records_count = (value.length - 2) / 7;
                if ((value.length - 2) % 7 != 0) {
                    Log.w(DEBUG_TAG, "AdvancedBeltController: Partial raw sensor notification!");
                }
                int[][] records = new int[records_count][4];
                for (int r = 0; r < records_count; r++) {
                    records[r][0] = value[(r * 7) + 2] & 0xFF;
                    records[r][1] = (((int) value[(r * 7) + 4]) << 8) | (0xFF & value[(r * 7) + 3]);
                    records[r][2] = (((int) value[(r * 7) + 6]) << 8) | (0xFF & value[(r * 7) + 5]);
                    records[r][3] = (((int) value[(r * 7) + 8]) << 8) | (0xFF & value[(r * 7) + 7]);
                }
                // Notify listeners
                ArrayList<AdvancedBeltListener> targets;
                synchronized (this) {
                    if (listeners.isEmpty()) {
                        return;
                    }
                    targets = new ArrayList<>(listeners);
                }
                for (AdvancedBeltListener l: targets) {
                    l.onRawSensorRecordNotified(records);
                }
            }
        }
    }

    @Override
    public void onRequestCompleted(int requestId, @Nullable byte[] notifiedValue, boolean success) {
        if (requestId == START_SENSOR_NOTIFICATION_REQUEST_ID && success) {
            // Set value
            rawSensorNotificationsEnabled = true;
            // Reset sequence
            sensorSequence = null;
            // Notify listeners
            ArrayList<AdvancedBeltListener> targets;
            synchronized (this) {
                if (listeners.isEmpty()) {
                    return;
                }
                targets = new ArrayList<>(listeners);
            }
            for (AdvancedBeltListener l: targets) {
                l.onRawSensorNotificationsStateChanged(true);
            }
        } else if (requestId == MAG_OFFSET_REQUEST_ID && success) {
            if (notifiedValue != null && notifiedValue.length >= 14) {
                magOffsets[0] = getFloatFromBytes(
                        notifiedValue[5], notifiedValue[4], notifiedValue[3], notifiedValue[2]);
                magOffsets[1] = getFloatFromBytes(
                        notifiedValue[9], notifiedValue[8], notifiedValue[7], notifiedValue[6]);
                magOffsets[2] = getFloatFromBytes(
                        notifiedValue[13], notifiedValue[12], notifiedValue[11], notifiedValue[10]);
            }
        } else if (requestId == MAG_GAIN_REQUEST_ID && success) {
            if (notifiedValue != null && notifiedValue.length >= 14) {
                magGains[0] = getFloatFromBytes(
                        notifiedValue[5], notifiedValue[4], notifiedValue[3], notifiedValue[2]);
                magGains[1] = getFloatFromBytes(
                        notifiedValue[9], notifiedValue[8], notifiedValue[7], notifiedValue[6]);
                magGains[2] = getFloatFromBytes(
                        notifiedValue[13], notifiedValue[12], notifiedValue[11], notifiedValue[10]);
            }
        } else if (requestId == MAG_ERROR_REQUEST_ID && success) {
            if (notifiedValue != null && notifiedValue.length >= 6) {
                magError = getFloatFromBytes(
                        notifiedValue[5], notifiedValue[4], notifiedValue[3], notifiedValue[2]);
            }
        } else if (requestId == GYRO_OFFSET_REQUEST_ID && success) {
            if (notifiedValue != null && notifiedValue.length >= 14) {
                gyroOffsets[0] = getFloatFromBytes(
                        notifiedValue[5], notifiedValue[4], notifiedValue[3], notifiedValue[2]);
                gyroOffsets[1] = getFloatFromBytes(
                        notifiedValue[9], notifiedValue[8], notifiedValue[7], notifiedValue[6]);
                gyroOffsets[2] = getFloatFromBytes(
                        notifiedValue[13], notifiedValue[12], notifiedValue[11], notifiedValue[10]);
            }
        } else if (requestId == GYRO_STATUS_REQUEST_ID && success) {
            if (notifiedValue != null && notifiedValue.length >= 3) {
                gyroStatus = notifiedValue[2] & 0xFF;
                // Inform listener only on the last parameter request
                informListenersCalibrationUpdated();
            }
        }
    }

    @Override
    public void onMtuChanged(int mtu, boolean success) {

    }

    // MARK: Implementation of `BeltCommandListener`
    @Override
    public void onBeltModeChanged(BeltMode mode) {
        if (beltMode == BeltMode.CALIBRATION && mode != BeltMode.CALIBRATION) {
            // Retrieve new calibration
            getSensorCalibration();
        }
        beltMode = mode;
    }

    @Override
    public void onBeltButtonPressed(BeltButtonPressEvent beltButtonPressEvent) {
        if (beltMode == BeltMode.CALIBRATION && beltButtonPressEvent.getSubsequentMode() != BeltMode.CALIBRATION) {
            // Retrieve new calibration
            getSensorCalibration();
        }
        beltMode = beltButtonPressEvent.getSubsequentMode();
    }

    @Override
    public void onBeltDefaultVibrationIntensityChanged(int intensity) {

    }

    @Override
    public void onBeltBatteryStatusUpdated(BeltBatteryStatus status) {

    }

    @Override
    public void onBeltOrientationUpdated(BeltOrientation orientation) {

    }

    @Override
    public void onBeltCompassAccuracySignalStateNotified(boolean signalEnabled) {

    }
}
