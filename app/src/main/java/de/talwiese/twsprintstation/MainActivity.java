package de.talwiese.twsprintstation;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.*;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String BASE_URL = "https://pos.talwiesenschaenke.de";
    private static final String TOKEN = "TWS8115PRINT";
    private static final String JOBS_URL = BASE_URL + "/tws_directprint/jobs?token=" + TOKEN;
    private static final String DONE_URL_PREFIX = BASE_URL + "/tws_directprint/done/";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView logView;
    private Spinner printerSpinner;
    private Button startButton, stopButton, testButton, refreshButton;
    private Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = false;
    private boolean pollingNow = false;

    private ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private String selectedMac = "";
    private SharedPreferences prefs;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!running) return;

            if (!pollingNow) {
                pollingNow = true;
                new Thread(() -> {
                    try {
                        pollPrintJobs();
                    } finally {
                        pollingNow = false;
                    }
                }).start();
            }

            handler.postDelayed(this, 4000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = getSharedPreferences("twsps", MODE_PRIVATE);
        selectedMac = prefs.getString("printer_mac", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("TWS Print Station\nOdoo Rechnung → 80mm Bluetooth");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        printerSpinner = new Spinner(this);
        root.addView(printerSpinner);

        refreshButton = new Button(this);
        refreshButton.setText("Bluetooth Drucker neu laden");
        root.addView(refreshButton);

        testButton = new Button(this);
        testButton.setText("Testdruck 80mm");
        root.addView(testButton);

        startButton = new Button(this);
        startButton.setText("Auto-Druck starten");
        root.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("Auto-Druck stoppen");
        root.addView(stopButton);

        logView = new TextView(this);
        logView.setTextSize(14);
        logView.setMovementMethod(new ScrollingMovementMethod());
        root.addView(logView, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        requestPerms();
        loadPrinters();

        refreshButton.setOnClickListener(v -> loadPrinters());

        testButton.setOnClickListener(v -> {
            log("Testdruck Button gedrueckt.");
            Toast.makeText(this, "Testdruck wird gesendet...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean ok = printText(
                        "              TESTDRUCK\n" +
                        "------------------------------------------\n" +
                        "TWS Print Station\n" +
                        "80mm Bluetooth Drucker\n" +
                        "------------------------------------------\n" +
                        "Wenn dieser Text lesbar ist,\n" +
                        "funktioniert der Direktdruck.\n\n\n"
                );
                log(ok ? "Testdruck OK" : "Testdruck FEHLER");
            }).start();
        });

        startButton.setOnClickListener(v -> startPolling());
        stopButton.setOnClickListener(v -> stopPolling());

        printerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < devices.size()) {
                    selectedMac = devices.get(pos).getAddress();
                    prefs.edit().putString("printer_mac", selectedMac).apply();
                    String name = devices.get(pos).getName() == null ? "(ohne Name)" : devices.get(pos).getName();
                    log("Drucker gewaehlt: " + name + " / " + selectedMac);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void requestPerms() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ArrayList<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 10);
            }
        }
    }

    private void loadPrinters() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

            if (adapter == null) {
                log("Kein Bluetooth Adapter gefunden.");
                return;
            }

            if (!adapter.isEnabled()) {
                log("Bluetooth ist ausgeschaltet.");
                return;
            }

            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            devices.clear();

            ArrayList<String> names = new ArrayList<>();
            int selectedIndex = 0;
            int idx = 0;

            for (BluetoothDevice d : bonded) {
                devices.add(d);
                String name = d.getName() == null ? "(ohne Name)" : d.getName();
                names.add(name + " - " + d.getAddress());

                if (d.getAddress().equals(selectedMac)) {
                    selectedIndex = idx;
                }

                idx++;
            }

            ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
            printerSpinner.setAdapter(aa);

            if (!devices.isEmpty()) {
                printerSpinner.setSelection(selectedIndex);
            }

            log("Gekoppelte Bluetooth-Geraete geladen: " + devices.size());

        } catch (Exception e) {
            log("Fehler beim Laden der Drucker: " + e.getMessage());
        }
    }

    private void startPolling() {
        if (running) {
            log("Auto-Druck laeuft bereits.");
            return;
        }

        if (selectedMac == null || selectedMac.isEmpty()) {
            log("Bitte zuerst Drucker auswaehlen.");
            return;
        }

        running = true;
        pollingNow = false;
        log("Auto-Druck gestartet.");
        handler.post(poller);
    }

    private void stopPolling() {
        running = false;
        pollingNow = false;
        log("Auto-Druck gestoppt.");
    }

    private void pollPrintJobs() {
        try {
            String json = httpGet(JOBS_URL);
            JSONArray arr = new JSONArray(json);

            if (arr.length() == 0) {
                return;
            }

            log("Druckauftraege: " + arr.length());

            for (int i = 0; i < arr.length(); i++) {
                JSONObject job = arr.getJSONObject(i);

                int id = job.getInt("id");
                String type = job.optString("type", "invoice");

                if (!"invoice".equals(type)) {
                    log("Ueberspringe unbekannten Job #" + id + " Typ: " + type);
                    continue;
                }

                String text = job.getString("text");
                boolean ok = printText(text + "\n\n\n");

                if (ok) {
                    httpPost(DONE_URL_PREFIX + id + "?token=" + TOKEN);
                    log("Gedruckt Job #" + id + " / Zeichen: " + text.length());
                } else {
                    log("Job NICHT als gedruckt markiert: #" + id);
                }

                try {
                    Thread.sleep(1200);
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            log("Polling Fehler: " + e.getMessage());
        }
    }

    private boolean printText(String text) {
        BluetoothSocket socket = null;
        OutputStream out = null;
        boolean dataSent = false;

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

            if (adapter == null) {
                log("Bluetooth Adapter fehlt.");
                return false;
            }

            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
                Thread.sleep(500);
            }

            BluetoothDevice device = adapter.getRemoteDevice(selectedMac);

            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();

            out = socket.getOutputStream();

            String safe = sanitize(text);

            out.write(new byte[]{0x1B, 0x40});          // init
            out.write(new byte[]{0x1B, 0x74, 0x10});    // CP850
            out.write(new byte[]{0x1D, 0x21, 0x00});    // normal size
            out.write(safe.getBytes(Charset.forName("US-ASCII")));
            out.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A});
            out.flush();

            dataSent = true;
            log("Daten an Drucker gesendet / Zeichen: " + text.length());

            try {
                Thread.sleep(1200);
            } catch (Exception ignored) {}

            return true;

        } catch (Exception e) {
            log("Druckerfehler: " + e.getMessage());
            return dataSent;

        } finally {
            try {
                if (out != null) out.flush();
            } catch (Exception ignored) {}

            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}

            try {
                Thread.sleep(700);
            } catch (Exception ignored) {}
        }
    }

    private String sanitize(String s) {
        return s
                .replace("€", "EUR")
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("Ä", "Ae")
                .replace("Ö", "Oe")
                .replace("Ü", "Ue")
                .replace("ß", "ss")
                .replace("–", "-")
                .replace("—", "-")
                .replace("„", "\"")
                .replace("“", "\"")
                .replace("’", "'");
    }

    private String httpGet(String u) throws Exception {
        URL url = new URL(u);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestMethod("GET");

        int code = con.getResponseCode();

        java.io.InputStream is;
        if (code >= 200 && code < 300) {
            is = con.getInputStream();
        } else {
            throw new RuntimeException("HTTP " + code);
        }

        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void httpPost(String u) throws Exception {
        URL url = new URL(u);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.getOutputStream().write(new byte[0]);

        int code = con.getResponseCode();

        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code);
        }

        con.getInputStream().close();
    }

    private void log(String m) {
        runOnUiThread(() -> {
            logView.append(m + "\n");

            if (logView.getLayout() != null) {
                int scrollAmount = logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
                if (scrollAmount > 0) {
                    logView.scrollTo(0, scrollAmount);
                }
            }
        });
    }
}
