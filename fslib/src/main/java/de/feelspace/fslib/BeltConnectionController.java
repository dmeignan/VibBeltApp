/*
 * Copyright (c) 2017-2019. feelSpace GmbH. All rights reserved.
 *
 * More info: www.feelspace.de
 * Developer contact: dev@feelspace.de
 * General information contact: info@feelspace.de
 */
package de.feelspace.fslib;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Implementation of the belt connection interface.
 */
class BeltConnectionController extends BeltConnectionInterface implements
        GattController.GattEventListener, BluetoothScanner.BluetoothScannerDelegate,
        BeltCommunicationController.HandshakeCallback,
        BluetoothPairingManager.BluetoothPairingDelegate {

    // Debug
    @SuppressWarnings("unused")
    private static final String DEBUG_TAG = "FeelSpace-Debug";
    @SuppressWarnings("unused")
    private static final boolean DEBUG = true;

    /**
     * The application context to establish connection.
     */
    private final @NonNull Context applicationContext;

    /**
     * The GATT controller.
     */
    private final @NonNull GattController gattController;

    /**
     * The Bluetooth scanner.
     */
    private final @NonNull BluetoothScanner scanner;

    /**
     * The pairing manager.
     */
    private final @NonNull BluetoothPairingManager pairingManager;

    /**
     * The communication controller.
     */
    private final @NonNull BeltCommunicationController communicationController;

    /**
     * Executor for timeout task and other delayed tasks.
     */
    private final @NonNull ScheduledThreadPoolExecutor executor;

    /**
     * Flag for pending connect.
     */
    private boolean connectOnFirstBeltFound = false;

    /**
     * Constructor.
     *
     * @param applicationContext The application context.
     * @throws IllegalArgumentException If the application context is <code>null</code>.
     */
    public BeltConnectionController(Context applicationContext) throws IllegalArgumentException {
        if (applicationContext == null) {
            throw new IllegalArgumentException("Null context.");
        }
        this.applicationContext = applicationContext;
        executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        gattController = new GattController(executor);
        gattController.addGattEventListener(this);
        communicationController = new BeltCommunicationController(gattController);
        scanner = new BluetoothScanner(executor,this);
        pairingManager = new BluetoothPairingManager(applicationContext, executor,this);
    }

    @Override
    public void scan() {
        synchronized (this) {
            connectOnFirstBeltFound = false;
            state = BeltConnectionState.STATE_SCANNING;
        }
        gattController.disconnect();
        scanner.startScan();
        notifyState();
    }

    @Override
    public void pairAndConnect(BluetoothDevice device) throws IllegalArgumentException, SecurityException {
        synchronized (this) {
            if (device == null) {
                throw new IllegalArgumentException("Cannot pair with a null device.");
            }
            connectOnFirstBeltFound = false;
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                // Connect without pairing
                state = BeltConnectionState.STATE_CONNECTING;
            } else {
                // Start pairing
                state = BeltConnectionState.STATE_PAIRING;
            }
        }
        // Stop pairing/scan before any pairing/connection attempt, even when not scanning
        scanner.stopScan();
        pairingManager.stopPairing();
        if (state == BeltConnectionState.STATE_PAIRING) {
            pairingManager.startPairing(device);
        } else {
            gattController.connect(applicationContext, device);
        }
        notifyState();
    }

    @Override
    public void connect(BluetoothDevice device) throws IllegalArgumentException {
        synchronized (this) {
            if (device == null) {
                throw new IllegalArgumentException("Cannot connect with a null device.");
            }
            connectOnFirstBeltFound = false;
            state = BeltConnectionState.STATE_CONNECTING;
        }
        // Stop pairing/scan before any connection attempt, even when not scanning
        scanner.stopScan();
        pairingManager.stopPairing();
        gattController.connect(applicationContext, device);
        notifyState();
    }

    @Override
    public void scanPairAndConnect() throws IllegalStateException {
        synchronized (this) {
            connectOnFirstBeltFound = true;
            state = BeltConnectionState.STATE_SCANNING;
        }
        gattController.disconnect();
        scanner.startScan();
        notifyState();
    }


    @Override
    public void stopScan() {
        synchronized (this) {
            if (state != BeltConnectionState.STATE_SCANNING) {
                return;
            }
            connectOnFirstBeltFound = false;
            state = BeltConnectionState.STATE_DISCONNECTED;
        }
        scanner.stopScan();
        notifyState();
    }

    @Override
    public void disconnect() {
        synchronized (this) {
            if (state == BeltConnectionState.STATE_SCANNING ||
                    state == BeltConnectionState.STATE_DISCONNECTED) {
                return;
            }
            state = BeltConnectionState.STATE_DISCONNECTED;
            connectOnFirstBeltFound = false;
        }
        pairingManager.stopPairing();
        gattController.disconnect();
        notifyState();
    }

    @Override protected @NonNull ScheduledThreadPoolExecutor getExecutor() {
        return executor;
    }

    @Override
    public BeltCommandInterface getCommandInterface() {
        return communicationController;
    }

    @Override
    public BeltCommunicationInterface getCommunicationInterface() {
        return communicationController;
    }

    @Override
    public void onGattConnectionStateChange(GattConnectionState gattState) {
        boolean handshake = false;
        synchronized (this) {
            switch (gattState) {
                case GATT_DISCONNECTED:
                    switch (this.state) {
                        case STATE_DISCONNECTED:
                        case STATE_SCANNING:
                        case STATE_PAIRING:
                            // Ignore event
                            return;
                        case STATE_CONNECTING:
                        case STATE_RECONNECTING:
                        case STATE_HANDSHAKE:
                        case STATE_CONNECTED:
                            state = BeltConnectionState.STATE_DISCONNECTED;
                            break;
                    }
                    break;

                case GATT_CONNECTING:
                    state = BeltConnectionState.STATE_CONNECTING;
                    break;

                case GATT_DISCOVERING_SERVICES:
                    state = BeltConnectionState.STATE_DISCOVERING_SERVICES;
                    break;

                case GATT_CONNECTED:
                    // Continue with handshake
                    state = BeltConnectionState.STATE_HANDSHAKE;
                    handshake = true;
                    break;

                case GATT_RECONNECTING:
                    state = BeltConnectionState.STATE_RECONNECTING;
                    break;
            }
        }
        if (handshake) {
            communicationController.startHandshake(this);
        }
        notifyState();
    }

    @Override
    public void onGattConnectionFailed() {
        synchronized (this) {
            if (state == BeltConnectionState.STATE_SCANNING ||
                    state == BeltConnectionState.STATE_DISCONNECTED ||
                    state == BeltConnectionState.STATE_PAIRING) {
                // Ignore GATT connection event
                return;
            }
            state = BeltConnectionState.STATE_DISCONNECTED;
        }
        notifyConnectionFailed();
        notifyState();
    }

    @Override
    public void onGattConnectionLost() {
        synchronized (this) {
            if (state == BeltConnectionState.STATE_SCANNING ||
                    state == BeltConnectionState.STATE_DISCONNECTED ||
                    state == BeltConnectionState.STATE_PAIRING) {
                // Ignore GATT connection event
                return;
            }
            state = BeltConnectionState.STATE_DISCONNECTED;
        }
        notifyConnectionLost();
        notifyState();
    }

    @Override
    public void onCharacteristicNotificationSet(@NonNull BluetoothGattCharacteristic characteristic,
                                                boolean enable, boolean success) {
        // Nothing to do
    }

    @Override
    public void onCharacteristicRead(@NonNull BluetoothGattCharacteristic characteristic,
                                     @Nullable byte[] value, boolean success) {
        // Nothing to do
    }

    @Override
    public void onCharacteristicWrite(@NonNull BluetoothGattCharacteristic characteristic,
                                      @Nullable byte[] value, boolean success) {
        // Nothing to do
    }

    @Override
    public void onCharacteristicChanged(@Nullable BluetoothGattCharacteristic characteristic,
                                        @Nullable byte[] value) {
        // Nothing to do
    }

    @Override
    public void onRequestCompleted(int requestId, @Nullable byte[] notifiedValue, boolean success) {
        // Nothing to do
    }

    @Override
    public void onScanStarted() {
        if (DEBUG) Log.i(DEBUG_TAG, "BeltConnectionController: Scan started.");
        synchronized (this) {
            if (state == BeltConnectionState.STATE_SCANNING) {
                return;
            }
            state = BeltConnectionState.STATE_SCANNING;
        }
        notifyState();
    }

    @Override
    public void onBeltFound(@NonNull BluetoothDevice device) {
        boolean connect = false;
        synchronized (this) {
            if (state != BeltConnectionState.STATE_SCANNING) {
                // Ignore if not scanning
                return;
            }
            if (connectOnFirstBeltFound) {
                connectOnFirstBeltFound = false;
                connect = true;
            }
        }
        if (connect) {
            try {
                pairAndConnect(device);
            } catch (Exception e) {
                // Should not happen
            }
        } else {
            notifyBeltFound(device);
        }
    }

    @Override
    public void onScanFinished() {
        if (DEBUG) Log.i(DEBUG_TAG, "BeltConnectionController: Scan finished.");
        boolean noBeltFound = false;
        synchronized (this) {
            if (state != BeltConnectionState.STATE_SCANNING) {
                // Ignore if not scanning
                return;
            }
            if (connectOnFirstBeltFound) {
                noBeltFound = true;
                connectOnFirstBeltFound = false;
            }
            state = BeltConnectionState.STATE_DISCONNECTED;
        }
        if (noBeltFound) {
            notifyNoBeltFound();
        }
        notifyState();
    }

    @Override
    public void onScanFailed() {
        if (DEBUG) Log.e(DEBUG_TAG, "BeltConnectionController: Scan failed.");
        synchronized (this) {
            if (state != BeltConnectionState.STATE_SCANNING) {
                // Ignore if not scanning
                return;
            }
            connectOnFirstBeltFound = false;
            state = BeltConnectionState.STATE_DISCONNECTED;
        }
        notifyScanFailed();
    }

    @Override
    public void onHandshakeCompleted(boolean success) {
        BluetoothDevice device;
        synchronized (this) {
            if (state != BeltConnectionState.STATE_HANDSHAKE) {
                // Ignore if not handshake ongoing
                return;
            }
            device = gattController.getDevice();
            if (device == null) {
                // Should not happen
                state = BeltConnectionState.STATE_DISCONNECTED;
            } else if (success) {
                saveDeviceAddress(applicationContext, gattController.getDevice());
                state = BeltConnectionState.STATE_CONNECTED;
            }
        }
        if (device == null) {
            // Should not happen
            notifyConnectionFailed();
            notifyState();
        } else if (success) {
            notifyState();
        } else {
            gattController.reconnect();
        }
    }

    @Override
    public void onPairingFinished(BluetoothDevice device) {
        if (DEBUG) Log.i(DEBUG_TAG, "BeltConnectionController: Pairing completed.");
        synchronized (this) {
            if (state != BeltConnectionState.STATE_PAIRING) {
                // Ignore if not pairing
                return;
            }
        }
        try {
            connect(device);
        } catch (Exception e) {
            // Should not happen
        }
    }

    @Override
    public void onPairingFailed() {
        if (DEBUG) Log.e(DEBUG_TAG, "BeltConnectionController: Pairing failed.");
        synchronized (this) {
            if (state != BeltConnectionState.STATE_PAIRING) {
                // Ignore if not pairing
                return;
            }
            state = BeltConnectionState.STATE_DISCONNECTED;
        }
        notifyPairingFailed();
    }
}
