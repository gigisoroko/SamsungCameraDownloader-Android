package com.samsungcameradownloader;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String APP_VERSION = "v9 - SSDP multicast + unicast + broadcast";

    private TextView status;
    private TextView selectedCount;
    private ProgressBar progress;

    private Button discoverButton;
    private Button downloadButton;
    private Button last5Button;
    private Button last10Button;
    private Button allButton;
    private Button noneButton;

    private LinearLayout fileList;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private CameraClient camera;
    private WifiManager.MulticastLock multicastLock;
    private Network wifiNetwork;

    private final List<CheckBox> fileChecks = new ArrayList<>();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWifiPermissionIfNeeded();
        buildUi();
    }

    private void requestWifiPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                    1001
            );
        }
    }

    private void buildUi() {

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 50, 40, 30);

        TextView title = new TextView(this);
        title.setText("Samsung Camera Downloader");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText(
                "\nConecta el teléfono a la Wi-Fi de la cámara y toca Buscar cámara.\n(" +
                        APP_VERSION + ")"
        );
        hint.setTextSize(16);
        box.addView(hint);

        discoverButton = new Button(this);
        discoverButton.setText("Buscar cámara");
        box.addView(discoverButton);

        selectedCount = new TextView(this);
        selectedCount.setText("\nSeleccionadas: 0");
        selectedCount.setTextSize(16);
        box.addView(selectedCount);

        LinearLayout selectionButtons = new LinearLayout(this);
        selectionButtons.setOrientation(LinearLayout.HORIZONTAL);

        last5Button = new Button(this);
        last5Button.setText("Últimas 5");
        last5Button.setEnabled(false);

        last10Button = new Button(this);
        last10Button.setText("Últimas 10");
        last10Button.setEnabled(false);

        selectionButtons.addView(
                last5Button,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        selectionButtons.addView(
                last10Button,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        box.addView(selectionButtons);

        LinearLayout selectionButtons2 = new LinearLayout(this);
        selectionButtons2.setOrientation(LinearLayout.HORIZONTAL);

        allButton = new Button(this);
        allButton.setText("Todas");
        allButton.setEnabled(false);

        noneButton = new Button(this);
        noneButton.setText("Ninguna");
        noneButton.setEnabled(false);

        selectionButtons2.addView(
                allButton,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        selectionButtons2.addView(
                noneButton,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        box.addView(selectionButtons2);

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        box.addView(fileList);

        downloadButton = new Button(this);
        downloadButton.setText("Descargar seleccionadas");
        downloadButton.setEnabled(false);
        box.addView(downloadButton);

        progress = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );

        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);

        box.addView(
                progress,
                new LinearLayout.LayoutParams(-1, 10)
        );

        status = new TextView(this);
        status.setText("\nEstado: esperando");
        status.setTextSize(15);
        box.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        setContentView(scroll);

        discoverButton.setOnClickListener(v -> discover());
        downloadButton.setOnClickListener(v -> downloadSelected());

        last5Button.setOnClickListener(v -> selectLast(5));
        last10Button.setOnClickListener(v -> selectLast(10));
        allButton.setOnClickListener(v -> selectAll());
        noneButton.setOnClickListener(v -> selectNone());
    }

    private void discover() {

        discoverButton.setEnabled(false);
        downloadButton.setEnabled(false);

        last5Button.setEnabled(false);
        last10Button.setEnabled(false);
        allButton.setEnabled(false);
        noneButton.setEnabled(false);

        fileList.removeAllViews();
        fileChecks.clear();
        selectedCount.setText("\nSeleccionadas: 0");

        progress.setVisibility(View.VISIBLE);
        status.setText("Buscando Samsung por SSDP…");

        executor.execute(() -> {

            String diag = "";

            try {

                diag = acquireNetwork();

                final String diagF = diag;

                runOnUiThread(() ->
                        status.setText(
                                "Buscando Samsung por SSDP…\n\n" + diagF
                        )
                );

                camera = CameraClient.discover(
                        diagF,
                        getApplicationContext(),
                        wifiNetwork
                );

                List<CameraClient.FileItem> files = camera.browse();

                runOnUiThread(() -> {

                    progress.setVisibility(View.GONE);

                    camera.files = files;

                    createFileCheckboxes(files);

                    boolean hasFiles = !files.isEmpty();

                    discoverButton.setEnabled(true);

                    last5Button.setEnabled(hasFiles);
                    last10Button.setEnabled(hasFiles);
                    allButton.setEnabled(hasFiles);
                    noneButton.setEnabled(hasFiles);

                    downloadButton.setEnabled(false);

                    status.setText(
                            "Cámara encontrada: " + camera.friendlyName +
                                    "\nModelo: " + camera.modelName +
                                    "\nArchivos encontrados: " + files.size() +
                                    "\n\nSelecciona los archivos que quieres descargar."
                    );
                });

            } catch (Exception e) {

                final String diagF = diag;

                runOnUiThread(() -> {

                    progress.setVisibility(View.GONE);

                    discoverButton.setEnabled(true);

                    status.setText(
                            "No se encontró la cámara.\n\n" +
                                    diagF +
                                    "\nError: " + e.getMessage() +
                                    "\n\nAsegúrate de estar conectado a la Wi-Fi de la cámara y de activar MobileLink/AutoShare."
                    );
                });
            }
        });
    }

    private void createFileCheckboxes(List<CameraClient.FileItem> files) {

        fileList.removeAllViews();
        fileChecks.clear();

        for (CameraClient.FileItem item : files) {

            CheckBox checkBox = new CheckBox(this);

            checkBox.setText(item.title);
            checkBox.setTextSize(16);
            checkBox.setPadding(5, 8, 5, 8);

            checkBox.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> updateSelection()
            );

            fileChecks.add(checkBox);
            fileList.addView(checkBox);
        }

        updateSelection();
    }

    private void updateSelection() {

        int selected = 0;

        for (CheckBox check : fileChecks) {
            if (check.isChecked()) {
                selected++;
            }
        }

        selectedCount.setText(
                "\nSeleccionadas: " + selected +
                        " / " + fileChecks.size()
        );

        downloadButton.setEnabled(selected > 0);
    }

    private void selectLast(int amount) {

        int start = Math.max(
                0,
                fileChecks.size() - amount
        );

        for (int i = 0; i < fileChecks.size(); i++) {
            fileChecks.get(i).setChecked(i >= start);
        }

        updateSelection();
    }

    private void selectAll() {

        for (CheckBox check : fileChecks) {
            check.setChecked(true);
        }

        updateSelection();
    }

    private void selectNone() {

        for (CheckBox check : fileChecks) {
            check.setChecked(false);
        }

        updateSelection();
    }

    private void downloadSelected() {

        if (camera == null || camera.files == null) {
            return;
        }

        List<CameraClient.FileItem> selected =
                new ArrayList<>();

        for (int i = 0; i < fileChecks.size(); i++) {

            if (fileChecks.get(i).isChecked()) {
                selected.add(camera.files.get(i));
            }
        }

        if (selected.isEmpty()) {
            return;
        }

        downloadButton.setEnabled(false);

        last5Button.setEnabled(false);
        last10Button.setEnabled(false);
        allButton.setEnabled(false);
        noneButton.setEnabled(false);
        discoverButton.setEnabled(false);

        progress.setVisibility(View.VISIBLE);

        final int total = selected.size();

        status.setText(
                "Descargando seleccionadas…\n0/" + total
        );

        executor.execute(() -> {

            int ok = 0;
            int skipped = 0;
            int failed = 0;

            StringBuilder errores = new StringBuilder();

            for (CameraClient.FileItem item : selected) {

                try {

                    String name =
                            camera.downloadToMediaStore(
                                    this,
                                    item
                            );

                    if (name == null) {
                        skipped++;
                    } else {
                        ok++;
                    }

                } catch (Exception e) {

                    failed++;

                    if (errores.length() < 800) {

                        errores.append("• ")
                                .append(item.title)
                                .append(": ")
                                .append(e.getMessage())
                                .append("\n");
                    }
                }

                final int done =
                        ok + skipped + failed;

                runOnUiThread(() ->
                        status.setText(
                                "Descargando seleccionadas…\n" +
                                        done + "/" + total +
                                        "\n\nGuardado en Galería > Samsung Camera"
                        )
                );
            }

            final int a = ok;
            final int b = skipped;
            final int c = failed;
            final String erroresF = errores.toString();

            runOnUiThread(() -> {

                progress.setVisibility(View.GONE);

                discoverButton.setEnabled(true);

                last5Button.setEnabled(!fileChecks.isEmpty());
                last10Button.setEnabled(!fileChecks.isEmpty());
                allButton.setEnabled(!fileChecks.isEmpty());
                noneButton.setEnabled(!fileChecks.isEmpty());

                updateSelection();

                status.setText(
                        "Listo.\n" +
                                "Nuevos: " + a +
                                "\nYa existentes: " + b +
                                "\nFallidos: " + c +
                                (c > 0
                                        ? "\n\nErrores:\n" + erroresF
                                        : "")
                );
            });
        });
    }

    private String acquireNetwork() {

        StringBuilder diag = new StringBuilder();

        ConnectivityManager cm =
                (ConnectivityManager)
                        getSystemService(CONNECTIVITY_SERVICE);

        Network wifi = null;

        Network[] all = cm.getAllNetworks();

        diag.append("Redes detectadas: ")
                .append(all.length)
                .append("\n");

        for (Network n : all) {

            android.net.NetworkCapabilities nc =
                    cm.getNetworkCapabilities(n);

            if (nc == null) {
                continue;
            }

            boolean isWifi =
                    nc.hasTransport(
                            android.net.NetworkCapabilities.TRANSPORT_WIFI
                    );

            boolean isCell =
                    nc.hasTransport(
                            android.net.NetworkCapabilities.TRANSPORT_CELLULAR
                    );

            boolean hasInternet =
                    nc.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                    );

            boolean validated =
                    nc.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    );

            diag.append("  - ")
                    .append(
                            isWifi
                                    ? "WIFI"
                                    : isCell
                                    ? "CELULAR"
                                    : "otra"
                    )
                    .append(" internet=")
                    .append(hasInternet)
                    .append(" validada=")
                    .append(validated)
                    .append("\n");

            if (isWifi) {
                wifi = n;
            }
        }

        wifiNetwork = wifi;

        if (wifi != null) {

            boolean bound =
                    cm.bindProcessToNetwork(wifi);

            diag.append("Bind a Wi-Fi: ")
                    .append(bound ? "OK" : "FALLÓ")
                    .append("\n");

        } else {

            diag.append(
                    "¡No se detectó ninguna red Wi-Fi activa!\n"
            );
        }

        WifiManager wm =
                (WifiManager)
                        getApplicationContext()
                                .getSystemService(WIFI_SERVICE);

        multicastLock =
                wm.createMulticastLock(
                        "SamsungCameraDownloader"
                );

        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        diag.append("MulticastLock: ")
                .append(multicastLock.isHeld())
                .append("\n");

        return diag.toString();
    }

    @Override
    protected void onDestroy() {

        if (multicastLock != null &&
                multicastLock.isHeld()) {

            multicastLock.release();
        }

        executor.shutdownNow();

        super.onDestroy();
    }
}
