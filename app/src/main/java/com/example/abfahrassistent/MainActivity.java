package com.example.abfahrassistent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;


public class MainActivity extends AppCompatActivity {

    //Variables (Views)
    ImageView im_ah, im_bt, im_logo, im_menu, im_symbol;
    Button btn_status, btn_licht;
    TextView txt_distanz, txt_letzterCheck, txt_meldungen;
    ImageView state_comp1, state_comp2, state_comp3, state_comp4, state_comp5, state_comp6, state_comp7;

    //Array for status views (iterating)
    public ImageView[] states = {state_comp1, state_comp2, state_comp3, state_comp4, state_comp5,
            state_comp6, state_comp7};

    //variables for connection management
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 2;
    private static final String TAG = "BLE_READ";
    private static final long SCAN_PERIOD = 5000;
    public boolean scanning = false;
    private int connectionState = STATE_DISCONNECTED;

    //Queue (scanning, filtering, connecting...)
    private final Handler handler = new Handler();
    public Timer timer = new Timer();
    public Queue<Runnable> commandQueue = new LinkedList<>();
    public boolean commandQueueBusy;
    public int nrTries = 0; //for queue
    public int MAX_TRIES = 10;
    public boolean isRetrying = false;

    Vibrator v = null;

    //BLE
    public BluetoothAdapter bluetoothAdapter = null;
    public BluetoothGatt bluetoothGatt = null;
    public BluetoothLeScanner bluetoothLeScanner = null;
    public BluetoothGattService bluetoothGattService = null;
    public BluetoothGattCharacteristic bluetoothGattCharacteristic = null;

    private ArrayList<BluetoothDevice> leDevicesList = new ArrayList<>();

    //Filters for BLE devices
    public ArrayList<ScanFilter> filters = new ArrayList<>();
    public ScanSettings settings;
    public ScanFilter filter;

    UUID myServUUID = UUID.fromString("0000183B-0000-1000-8000-00805F9B34FB");  //Server UUID of the ESP32
    public byte[] byteArray = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}; //stores the states of trailer components 0/1
    public String currentTime = "00:00:00";
    public String timeLastCheck = "Kein aktuelles Ergebnis vorhanden, bitte erneut prüfen";

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //assign variables to views (of the main-activity)
        im_ah = findViewById(R.id.ah_bild);
        im_bt = findViewById(R.id.bluetooth_state);
        im_logo = findViewById(R.id.logo_alko);
        im_menu = findViewById(R.id.menu_bar);
        im_symbol = findViewById(R.id.img_symbol);
        im_symbol.setVisibility(View.GONE);

        btn_status = findViewById(R.id.Status);
        btn_licht = findViewById(R.id.licht_button);

        txt_distanz = findViewById(R.id.distanz);
        txt_letzterCheck = findViewById(R.id.letzter_check);
        txt_meldungen = findViewById(R.id.Meldungen);

        state_comp1 = findViewById(R.id.img1);
        state_comp2 = findViewById(R.id.img2);
        state_comp3 = findViewById(R.id.img3);
        state_comp4 = findViewById(R.id.img4);
        state_comp5 = findViewById(R.id.img5);
        state_comp6 = findViewById(R.id.img6);
        state_comp7 = findViewById(R.id.img7);

        states[0] = state_comp1;
        states[1] = state_comp2;
        states[2] = state_comp3;
        states[3] = state_comp4;
        states[4] = state_comp5;
        states[5] = state_comp6;
        states[6] = state_comp7;

        v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);  // Get instance of Vibrator from current Context

        startTime();    //start getting the current time periodically

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE); //get Location Service
        permissionCheck(locationManager);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE); //get Bluetooth Ser

        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        setScanParams();    //set Filters and scan for devices
        scanLeDevice();

        startBtIconHandler();   //shows current connection state

        //Button (bluetooth symbol) for new BLE scan (scan & connect)
        im_bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanLeDevice();
            }
        });

        //Button (Abfahrcheck) for checking the current states of the trailer components
        btn_status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStatus();
            }
        });

        //PopUp for detailled information on lights
        btn_licht.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createPopUpLight();
            }
        });
    }


    @SuppressLint("MissingPermission")
    private void scanLeDevice() {
        if (!scanning) {
            // Stops scanning after a predefined scan period.
            handler.postDelayed(new Runnable() {
                @SuppressLint("MissingPermission")
                @Override
                public void run() {
                    scanning = false;
                    bluetoothLeScanner.stopScan(leScanCallback);
                    Log.i(TAG, "scan stopped");
                }
            }, SCAN_PERIOD);

            scanning = true;
            bluetoothLeScanner.startScan(filters, settings, leScanCallback);
            Log.i(TAG, "... Started scanning");
        }
        else {
            scanning = false;
            bluetoothLeScanner.stopScan(leScanCallback);
            Log.i(TAG, "scan stopped");
        }
    }

    private ScanCallback leScanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            Log.i(TAG, "onScanResult");
            leDevicesList.add(0,result.getDevice());
            myConnect(leDevicesList.get(0).toString());       //connect to first device with matching UUID
        }
        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.i(TAG, "onScanFailed");
        }
    };


    @SuppressLint("MissingPermission")
    public boolean myConnect(final String address) {

        if (bluetoothAdapter == null || address == null) {
            Log.i(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }
        try {
            final BluetoothDevice myDevice = bluetoothAdapter.getRemoteDevice(address);
            bluetoothGatt = myDevice.connectGatt(getApplicationContext(), true, bluetoothGattCallback);     // connect to the GATT server on the device -> bluetoothGattCallback if successful
            return true;
        } catch (IllegalArgumentException exception) {
            Log.i(TAG, "Device not found with provided address.  Unable to connect.");
            return false;
        }
    }

    public void startBtIconHandler(){
        Runnable runnableBt = new Runnable() {
            @Override
            public void run() {
                if(connectionState == STATE_CONNECTED){
                    im_bt.setImageResource(R.drawable.vector_bt_connected);
                }
                else if(connectionState == STATE_DISCONNECTED && bluetoothAdapter.isEnabled()){
                    im_bt.setImageResource(R.drawable.vector_bt_nc);
                }
                else if(!bluetoothAdapter.isEnabled()){
                    im_bt.setImageResource(R.drawable.vector_bt_disabled);
                }
                handler.postDelayed(this, 1000);
            }
        };

        handler.post(runnableBt);
    }


    public void myReadCharacteristic(final BluetoothGattCharacteristic characteristic) {
        Log.i(TAG, "myReadCharacteristics called");
        if (bluetoothGatt == null) {
            Log.i(TAG, "ERROR: Gatt is 'null', ignoring read request");
        }

        // Check if characteristic is valid
        if (characteristic == null) {
            Log.i(TAG, "ERROR: Characteristic is 'null', ignoring read request");
        }

        // Enqueue the read command now that all checks have been passed
        boolean success = commandQueue.add(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                if (bluetoothGatt != null) {
                    bluetoothGatt.readCharacteristic(characteristic);
/*                    if (!bluetoothGatt.readCharacteristic(bluetoothGattCharacteristic)) {
                        Log.i(TAG, "readCharacteristics failed!!!");
                    } else {
                        Log.i(TAG, "readCharacteristics runnable OK");

                    }*/
                }
            }
        });

        //if(success){
        //    nextCommand();}

    }

    public void myDiscoverServices() {
        if(bluetoothGatt == null) {
            Log.i(TAG, "ERROR: Gatt is 'null', ignoring read request");
        }
        // Enqueue the read command now that all checks have been passed
        commandQueue.add(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                if (bluetoothGatt != null) {
                    if (!bluetoothGatt.discoverServices()) {
                        Log.i(TAG, String.format("ERROR: discoverService failed"));
                        completedCommand();
                    } else {
                        Log.i(TAG, String.format("discovering started"));
                        nrTries++;
                    }
                }
            }
        });
        nextCommand();
    }

    public void myShowStates(){
        Log.i(TAG, "showStates added");
        commandQueue.add(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "showStates run");
                        for(int i = 0; i < 7; i++){
                            if(byteArray[i+1] == 0x01){
                                states[i].setImageResource(R.drawable.ic_baseline_radio_button_checked_ok);
                                Log.i(TAG, String.format("0x%20x", byteArray[i+1]));
                            }
                            else if(byteArray[i+1] == 0x00){
                                states[i].setImageResource(R.drawable.ic_baseline_radio_button_checked_not_ok);
                                Log.i(TAG, String.format("0x%20x", byteArray[i+1]));
                            }
                        }

                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                im_symbol.setVisibility(View.GONE);
                            }
                        }, 3000);

                        if(byteArray[17] == 0x00){
                            txt_meldungen.setText("  Sie sind abfahrbereit, gute Fahrt!");
                            txt_meldungen.setTextColor(getColor(R.color.colorPrimaryVariant));
                            im_symbol.setImageResource(R.drawable.ic_baseline_check_24);
                            im_symbol.setVisibility(View.VISIBLE);

                        }
                        else{
                            txt_meldungen.setText("  Sie sind noch nicht abfahrbereit");
                            txt_meldungen.setTextColor(getColor(R.color.colorPrimaryVariant));
                            im_symbol.setImageResource(R.drawable.ic_baseline_close_24);
                            im_symbol.setVisibility(View.VISIBLE);
                            v.vibrate(750);

                        }

                        txt_letzterCheck.setText("zuletzt geprüft: "+ timeLastCheck);

                    }
                });

        nextCommand();
    }


    //gets time every second (period = 1000)
    public void startTime(){
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Calendar calendar = Calendar.getInstance();
                        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                        currentTime = simpleDateFormat.format(calendar.getTime());
                    }
                });
            }
        }, 0, 1000);
    }

    private void nextCommand() {
        // If there is still a command being executed then bail out
        if(commandQueueBusy) {
            return;
        }

        // Check if we still have a valid gatt object
        if (bluetoothGatt == null) {
            Log.i(TAG, String.format("ERROR: GATT is 'null' for peripheral clearing command queue"));
            commandQueue.clear();
            commandQueueBusy = false;
            return;
        }

        // Execute the next command in the queue
        if (commandQueue.size() > 0) {
            Log.i(TAG, "NEXT COMMAND");
            final Runnable bluetoothCommand = commandQueue.peek();
            commandQueueBusy = true;
            nrTries = 0;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        bluetoothCommand.run();
                    } catch (Exception ex) {
                        Log.i(TAG, String.format("ERROR: Command exception for device"));
                    }
                }
            });
        }
    }

    private void completedCommand() {
        Log.i(TAG, "Command completed");
        commandQueueBusy = false;
        isRetrying = false;
        commandQueue.poll();
        nextCommand();
    }

    private void retryCommand() { //unused yet
        commandQueueBusy = false;
        Runnable currentCommand = commandQueue.peek();
        if(currentCommand != null) {
            if (nrTries >= MAX_TRIES) {
                // Max retries reached, give up on this one and proceed
                Log.v(TAG, "Max number of tries reached");
                commandQueue.poll();
            } else {
                isRetrying = true;
            }
        }
        nextCommand();
    }

    @SuppressLint("MissingPermission")
    private void checkStatus(){
        if(bluetoothGatt != null && bluetoothGattService != null){
            myReadCharacteristic(bluetoothGattCharacteristic);
        }
        else {
            Log.i(TAG, "check not possible");
        }
        completedCommand();
    }

    private void setScanParams(){
        //filter & settings für Scan
        filter = new ScanFilter.Builder()
                .setDeviceName("ESP32")
                .build();
        filters.add(filter);

        settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
    }

    @SuppressLint("MissingPermission")
    private void permissionCheck(LocationManager locationManager){
        //check bluetooth available & turned on
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            int REQUEST_ENABLE_BT = 1;
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }



        //location only inform the user
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Bitte aktivieren Sie den Standort", Toast.LENGTH_LONG);
        }

    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == requestCode) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "oAResult");
                BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                bluetoothAdapter = bluetoothManager.getAdapter();
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    private BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                connectionState = STATE_CONNECTED;
                Log.i(TAG, "connected to GATT Server");
                myDiscoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                connectionState = STATE_DISCONNECTED;
                Log.i(TAG, "disconnected from GATT Server");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.i(TAG, "onServiceDiscovered");

            if (status == BluetoothGatt.GATT_SUCCESS){

                List<BluetoothGattService> gattServices;
                gattServices = bluetoothGatt.getServices();

                for(BluetoothGattService gattService : gattServices){
                    Log.i(TAG, "Service found: " + gattService.getUuid().toString());
                }

                bluetoothGattService = bluetoothGatt.getService(myServUUID);

                if (bluetoothGattService != null) {
                    Log.i(TAG, "Service UUID found: " + bluetoothGattService.getUuid().toString());
                } else {
                    Log.i(TAG, "Service not found for UUID: " + myServUUID);
                }


                List<BluetoothGattCharacteristic> gattCharacteristics;
                gattCharacteristics = bluetoothGattService.getCharacteristics();

                for(BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics){
                    Log.i(TAG, "Characteristic found: " + gattCharacteristic.getUuid().toString());
                }

                bluetoothGattCharacteristic = gattCharacteristics.get(0);


                if(bluetoothGattCharacteristic != null) {
                    Log.i(TAG, "Characteristics ok: : " + bluetoothGattCharacteristic.getUuid().toString());
                }
                else {
                    Log.i(TAG, "Characteristics not found");
                }
                //completedCommand();
            }
        }


        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status){
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.i(TAG, "OnCharacteristicsRead");
            byteArray = characteristic.getValue();
            Log.i(TAG, Arrays.toString(byteArray));
            timeLastCheck = currentTime;
            myShowStates();
            completedCommand();
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            super.onCharacteristicChanged(gatt, characteristic, value);
            Log.i(TAG, "onCharacteristicsChanged");
        }

        @Override
        public void onServiceChanged(@NonNull BluetoothGatt gatt) {
            super.onServiceChanged(gatt);
        }

        @NonNull
        @Override
        public String toString() {
            return super.toString();
        }
    };

    //start up screen (didnt work -> ignore)
    public void createPopUpStart(){
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View popUpStart = inflater.inflate(R.layout.startup, null);

        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.MATCH_PARENT;
        boolean focusable = true;

        PopupWindow popUpWindowStart = new PopupWindow(popUpStart,width,height,focusable);

        handler.post(new Runnable() {
            @Override
            public void run() {
                popUpWindowStart.showAtLocation(findViewById(R.id.ConstraintLayout), Gravity.CENTER,0,0 );
            }
        });

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                popUpWindowStart.dismiss();
            }
        },2000);
    }

    //create popUp for trailer lights
    public void createPopUpLight() {
        //layout
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View popUpViewLight = inflater.inflate(R.layout.activity_light, null);

        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.MATCH_PARENT;
        boolean focusable = true;

        PopupWindow popUpLight = new PopupWindow(popUpViewLight,width,height,focusable);

        handler.post(new Runnable() {
            @Override
            public void run() {
                popUpLight.showAtLocation(findViewById(R.id.ConstraintLayout), Gravity.CENTER,0,0 );
            }
        });

        //create variables and assign views of the popUp
        ImageView status_blL, status_blR, status_nebel, status_rueckL, status_rueckR, status_rueckS, status_brems;
        TextView txt_letzterCheck;
        Button btnLightBack;

        btnLightBack = popUpViewLight.findViewById(R.id.btnLightBack);
        status_blL = popUpViewLight.findViewById(R.id.status_blL);
        status_blR = popUpViewLight.findViewById(R.id.status_blR);
        status_brems = popUpViewLight.findViewById(R.id.status_brems);
        status_nebel = popUpViewLight.findViewById(R.id.status_nebel);
        status_rueckL = popUpViewLight.findViewById(R.id.status_rueckL);
        status_rueckR = popUpViewLight.findViewById(R.id.status_rueckR);
        status_rueckS = popUpViewLight.findViewById(R.id.status_rueckS);
        txt_letzterCheck = popUpViewLight.findViewById(R.id.zuletztLight);

        ImageView[] arrLightImgs = {status_blL, status_nebel, status_blR, status_rueckR, status_brems, status_rueckL, status_rueckS};

        for(int i = 0; i < 7; i++){
            if(byteArray[i+9] == 1){
                arrLightImgs[i].setImageResource(R.drawable.ic_baseline_check_24);
            }
            else if(byteArray[i+9] == 0){
                arrLightImgs[i].setImageResource(R.drawable.ic_baseline_close_24);
            }
        }
        txt_letzterCheck.setText("zuletzt geprüft: "+ timeLastCheck);

        btnLightBack.setOnClickListener(new View.OnClickListener() {    //button for closing the Pop Up window
            @Override
            public void onClick(View v) {
                popUpLight.dismiss();
            }
        });
    }


}
