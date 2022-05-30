package com.hiddenramblings.tagmo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.nfc.TagLostException;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;
import com.hiddenramblings.tagmo.adapter.BluupFlaskAdapter;
import com.hiddenramblings.tagmo.amiibo.Amiibo;
import com.hiddenramblings.tagmo.amiibo.AmiiboManager;
import com.hiddenramblings.tagmo.eightbit.io.Debug;
import com.hiddenramblings.tagmo.eightbit.material.IconifiedSnackbar;
import com.hiddenramblings.tagmo.nfctech.TagUtils;
import com.hiddenramblings.tagmo.settings.BrowserSettings;
import com.hiddenramblings.tagmo.widget.Toasty;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
@SuppressLint("MissingPermission")
public class BluupFlaskActivity extends AppCompatActivity implements
        BluupFlaskAdapter.OnAmiiboClickListener {

    private CardView amiiboTile;
    private CardView amiiboCard;
    private RecyclerView flaskDetails;
    private TextView flaskStats;
    private ProgressBar progressBar;
    private Snackbar statusBar;
    private BrowserSettings settings;

    private BottomSheetBehavior<View> bottomSheetBehavior;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothAdapter.LeScanCallback scanCallback;
    private BluetoothLeService flaskService;
    private String flaskProfile;
    private String flaskAddress;

    private int currentCount;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    ActivityResultLauncher<String[]> onRequestLocationQ = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> { boolean isLocationAvailable = false;
        for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
            if (entry.getKey().equals(Manifest.permission.ACCESS_FINE_LOCATION)
                    && entry.getValue()) isLocationAvailable = true;
        }
        if (isLocationAvailable) {
            activateBluetooth();
        } else {
            new Toasty(this).Long(R.string.flask_permissions);
            finish();
        }
    });

    @RequiresApi(api = Build.VERSION_CODES.Q)
    ActivityResultLauncher<String> onRequestBackgroundQ = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), permission -> {});

    ActivityResultLauncher<String[]> onRequestBluetoothS = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> { boolean isBluetoothAvailable = false;
        for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
            if (entry.getValue()) isBluetoothAvailable = true;
        }
        if (isBluetoothAvailable) {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onRequestBackgroundQ.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                }
                selectBluetoothDevice();
            } else {
                new Toasty(this).Long(R.string.flask_bluetooth);
                finish();
            }
        } else {
            new Toasty(this).Long(R.string.flask_bluetooth);
            finish();
        }
    });
    ActivityResultLauncher<Intent> onRequestBluetooth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        mBluetoothAdapter = getBluetoothAdapter();
        if (null != mBluetoothAdapter) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                onRequestBackgroundQ.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
            selectBluetoothDevice();
        } else {
            new Toasty(this).Long(R.string.flask_bluetooth);
           finish();
        }
    });
    ActivityResultLauncher<String[]> onRequestLocation = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> { boolean isLocationAvailable = false;
        for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
            if (entry.getValue()) isLocationAvailable = true;
        }
        if (isLocationAvailable) {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter)
                selectBluetoothDevice();
            else
                onRequestBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } else {
            new Toasty(this).Long(R.string.flask_permissions);
            finish();
        }
    });
    protected ServiceConnection mServerConn = new ServiceConnection() {
        boolean isServiceDiscovered = false;

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BluetoothLeService.LocalBinder localBinder = (BluetoothLeService.LocalBinder) binder;
            flaskService = localBinder.getService();
            if (flaskService.initialize()) {
                if (flaskService.connect(flaskAddress)) {
                    flaskService.setListener(new BluetoothLeService.BluetoothGattListener() {
                        @Override
                        public void onServicesDiscovered() {
                            isServiceDiscovered = true;
                            runOnUiThread(() -> ((TextView) findViewById(
                                    R.id.hardware_info)).setText(flaskProfile));
                            try {
                                flaskService.setFlaskCharacteristicRX();
                                dismissConnectionNotice();
                                flaskService.delayedWriteCharacteristic("tag.getList()");
                            } catch (TagLostException tle) {
                                stopFlaskService();
                                new Toasty(BluupFlaskActivity.this).Short(R.string.flask_invalid);
                            }
                        }

                        @Override
                        public void onFlaskActiveChanged(JSONObject jsonObject) {
                            try {
                                getActiveAmiibo(getAmiiboByName(
                                        jsonObject.getString("name")
                                ), amiiboTile);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onFlaskActiveDeleted(JSONObject jsonObject) {
                            amiiboTile.setVisibility(View.INVISIBLE);
                        }

                        @Override
                        public void onFlaskListRetrieved(JSONArray jsonArray) {
                            currentCount = jsonArray.length();
                            ArrayList<Amiibo> amiibo = new ArrayList<>();
                            for (int i = 0; i < currentCount; i++) {
                                try {
                                    amiibo.add(getAmiiboByName(jsonArray.getString(i)));
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                            BluupFlaskAdapter adapter = new BluupFlaskAdapter(
                                    settings, BluupFlaskActivity.this);
                            adapter.setAmiibos(amiibo);
                            runOnUiThread(() -> flaskDetails.setAdapter(adapter));
                            flaskService.delayedWriteCharacteristic("tag.get()");
                        }

                        @Override
                        public void onFlaskActiveLocated(JSONObject jsonObject) {
                            try {
                                getActiveAmiibo(getAmiiboByName(
                                        jsonObject.getString("name")
                                ), amiiboTile);
                                String index = jsonObject.getString("index");
                                runOnUiThread(() -> flaskStats.setText(getString(
                                        R.string.flask_count, index, currentCount)));
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                } else {
                    stopFlaskService();
                    new Toasty(BluupFlaskActivity.this).Short(R.string.flask_invalid);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            unbindService(mServerConn);
            stopService(new Intent(BluupFlaskActivity.this, BluetoothLeService.class));
            if (!isServiceDiscovered) {
                new Toasty(BluupFlaskActivity.this).Short(R.string.flask_missing);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_bluup_flask);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        amiiboTile = findViewById(R.id.active_tile_layout);
        amiiboTile.setVisibility(View.INVISIBLE);
        progressBar = findViewById(R.id.scanner_progress);

        amiiboCard = findViewById(R.id.active_card_layout);
        amiiboCard.findViewById(R.id.txtError).setVisibility(View.GONE);
        amiiboCard.findViewById(R.id.txtPath).setVisibility(View.GONE);

        flaskDetails = findViewById(R.id.flask_details);
        flaskDetails.setLayoutManager(new LinearLayoutManager(this));

        flaskStats = findViewById(R.id.flask_stats);

        settings = new BrowserSettings().initialize();

        AppCompatImageView toggle = findViewById(R.id.toggle);
        this.bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet));
        this.bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        this.bottomSheetBehavior.addBottomSheetCallback(
                new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {
                        if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                            toggle.setImageResource(R.drawable.ic_expand_less_white_24dp);
                        } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                            toggle.setImageResource(R.drawable.ic_expand_more_white_24dp);
                        }
                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        ViewGroup mainLayout = findViewById(R.id.flask_details);
                        if (mainLayout.getBottom() >= bottomSheet.getTop()) {
                            int bottomHeight = bottomSheet.getMeasuredHeight()
                                    - bottomSheetBehavior.getPeekHeight();
                            mainLayout.setPadding(0, 0, 0, slideOffset > 0
                                    ? (int) (bottomHeight * slideOffset) : 0);
                        }
                    }
                });

        toggle.setOnClickListener(view -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            } else {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });

        verifyPermissions();
    }

    void setAmiiboInfoText(TextView textView, CharSequence text) {
            textView.setVisibility(View.VISIBLE);
            if (text.length() == 0) {
                textView.setText(R.string.unknown);
                textView.setEnabled(false);
            } else {
                textView.setText(text);
                textView.setEnabled(true);
            }
    }

    private void getActiveAmiibo(Amiibo active, View amiiboView) {
        TextView txtName = amiiboView.findViewById(R.id.txtName);
        TextView txtTagId = amiiboView.findViewById(R.id.txtTagId);
        TextView txtAmiiboSeries = amiiboView.findViewById(R.id.txtAmiiboSeries);
        TextView txtAmiiboType = amiiboView.findViewById(R.id.txtAmiiboType);
        TextView txtGameSeries = amiiboView.findViewById(R.id.txtGameSeries);
        AppCompatImageView imageAmiibo = amiiboView.findViewById(R.id.imageAmiibo);

        CustomTarget<Bitmap> target = new CustomTarget<>() {
            @Override
            public void onLoadStarted(@Nullable Drawable placeholder) {
                imageAmiibo.setImageResource(0);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                imageAmiibo.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                imageAmiibo.setVisibility(View.VISIBLE);
            }

            @Override
            public void onResourceReady(@NonNull Bitmap resource, Transition transition) {
                imageAmiibo.setImageBitmap(resource);
                imageAmiibo.setVisibility(View.VISIBLE);
            }
        };

        runOnUiThread(() -> {
            String amiiboHexId;
            String amiiboName = "";
            String amiiboSeries = "";
            String amiiboType = "";
            String gameSeries = "";
            String amiiboImageUrl;

            if (null != active) {
                amiiboView.setVisibility(View.VISIBLE);
                amiiboHexId = TagUtils.amiiboIdToHex(active.id);
                amiiboName = active.name;
                amiiboImageUrl = active.getImageUrl();
                if (null != active.getAmiiboSeries())
                    amiiboSeries = active.getAmiiboSeries().name;
                if (null != active.getAmiiboType())
                    amiiboType = active.getAmiiboType().name;
                if (null != active.getGameSeries())
                    gameSeries = active.getGameSeries().name;

                setAmiiboInfoText(txtName, amiiboName);
                setAmiiboInfoText(txtTagId, amiiboHexId);
                setAmiiboInfoText(txtAmiiboSeries, amiiboSeries);
                setAmiiboInfoText(txtAmiiboType, amiiboType);
                setAmiiboInfoText(txtGameSeries, gameSeries);

                if (null != imageAmiibo) {
                    GlideApp.with(this).clear(target);
                    if (null != amiiboImageUrl) {
                        GlideApp.with(this).asBitmap().load(amiiboImageUrl).into(target);
                    }
                }
                if (amiiboHexId.endsWith("00000002") && !amiiboHexId.startsWith("00000000")) {
                    txtTagId.setEnabled(false);
                }
            }
        });
    }

    private Amiibo getAmiiboByName(String name) {
            AmiiboManager amiiboManager;
            try {
                amiiboManager = AmiiboManager.getAmiiboManager(getApplicationContext());
            } catch (IOException | JSONException | ParseException e) {
                Debug.Log(e);
                amiiboManager = null;
                new Toasty(this).Short(R.string.amiibo_info_parse_error);
            }

            if (Thread.currentThread().isInterrupted()) return null;

            Amiibo selectedAmiibo = null;
            if (null != amiiboManager) {
                String amiiboName = name.split("\\|")[0];
                for (Amiibo amiibo : amiiboManager.amiibos.values()) {
                    if (amiibo.name.equals(amiiboName)) {
                        selectedAmiibo = amiibo;
                    }
                }
                if (null == selectedAmiibo) {
                    for (Amiibo amiibo : amiiboManager.amiibos.values()) {
                        if (amiibo.name.startsWith(amiiboName)) {
                            selectedAmiibo = amiibo;
                        }
                    }
                }
            }
            return selectedAmiibo;

    }

    private void verifyPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
                activateBluetooth();
            } else {
                final String[] PERMISSIONS_LOCATION = {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                };
                onRequestLocationQ.launch(PERMISSIONS_LOCATION);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final String[] PERMISSIONS_LOCATION = {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
            onRequestLocation.launch(PERMISSIONS_LOCATION);
        } else {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter)
                selectBluetoothDevice();
            else
                onRequestBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    private void activateBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final String[] PERMISSIONS_BLUETOOTH = {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            };
            onRequestBluetoothS.launch(PERMISSIONS_BLUETOOTH);
        } else {
            mBluetoothAdapter = getBluetoothAdapter();
            if (null != mBluetoothAdapter) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onRequestBackgroundQ.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                }
                selectBluetoothDevice();
            } else {
                onRequestBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            }
        }
    }

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothAdapter mBluetoothAdapter;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mBluetoothAdapter = ((BluetoothManager)
                    getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            if (null != mBluetoothAdapter) {
                if (!mBluetoothAdapter.isEnabled()) mBluetoothAdapter.enable();
                return mBluetoothAdapter;
            }
        } else {
            //noinspection deprecation
            return BluetoothAdapter.getDefaultAdapter();
        }
        return null;
    }

    private void scanBluetoothServices() {
        progressBar.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
            ParcelUuid FlaskUUID = new ParcelUuid(BluetoothLeService.FlaskNUS);
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(FlaskUUID).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    flaskProfile = result.getDevice().getName();
                    flaskAddress = result.getDevice().getAddress();
                    dismissFlaskDiscovery();
                    showConnectionNotice();
                    startFlaskService();
                }
            };
            scanner.startScan(Collections.singletonList(filter), settings, callback);
        } else {
            scanCallback = (bluetoothDevice, i, bytes) -> {
                flaskProfile = bluetoothDevice.getName();
                flaskAddress = bluetoothDevice.getAddress();
                dismissFlaskDiscovery();
                showConnectionNotice();
                startFlaskService();
            };
            mBluetoothAdapter.startLeScan(new UUID[]{ BluetoothLeService.FlaskNUS }, scanCallback);
        }
    }

    ActivityResultLauncher<Intent> onRequestPairing = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> scanBluetoothServices());

    private void selectBluetoothDevice() {
        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            if (device.getName().toLowerCase(Locale.ROOT).startsWith("flask")) {
                new Toasty(this).Long(R.string.flask_paired);
                dismissFlaskDiscovery();
                try {
                    onRequestPairing.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (ActivityNotFoundException anf) {
                    scanBluetoothServices();
                }
            }
        }
        scanBluetoothServices();
    }

    private void showConnectionNotice() {
        statusBar = new IconifiedSnackbar(this).buildSnackbar(
                R.string.flask_located, R.drawable.ic_bluup_flask_24dp, Snackbar.LENGTH_INDEFINITE
        );
        statusBar.show();
    }

    private void dismissConnectionNotice() {
        if (null != statusBar && statusBar.isShown()) statusBar.dismiss();
    }

    public void startFlaskService() {
        Intent service = new Intent(this, BluetoothLeService.class);
        startService(service);
        bindService(service, mServerConn, Context.BIND_AUTO_CREATE);
    }

    public void stopFlaskService() {
        dismissConnectionNotice();
        if (null != flaskService) flaskService.disconnect();
    }

    private void dismissFlaskDiscovery() {
        if (null != mBluetoothAdapter) {
            if (null != scanCallback)
                mBluetoothAdapter.stopLeScan(scanCallback);
            progressBar.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dismissFlaskDiscovery();
        stopFlaskService();
    }

    @Override
    public void onAmiiboClicked(Amiibo amiibo) {
        if (null != amiibo) {
            getActiveAmiibo(amiibo, amiiboCard);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    @Override
    public void onAmiiboImageClicked(Amiibo amiibo) {
        if (null != amiibo) {
            Bundle bundle = new Bundle();
            bundle.putLong(NFCIntent.EXTRA_AMIIBO_ID, amiibo.id);

            Intent intent = new Intent(this, ImageActivity.class);
            intent.putExtras(bundle);

            startActivity(intent);
        }
    }
}