package com.ffps.fcam;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity {

    private EditText eThr, eGap, eCnt, eInt, ePort, ePass;
    private CheckBox cbAuto;
    private Spinner spCam;
    private TextView tvStatus, tvHint, tvVer;
    private Button btn;
    private final Handler h = new Handler();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        eThr = (EditText) findViewById(R.id.eThr);
        eGap = (EditText) findViewById(R.id.eGap);
        eCnt = (EditText) findViewById(R.id.eCnt);
        eInt = (EditText) findViewById(R.id.eInt);
        ePort = (EditText) findViewById(R.id.ePort);
        ePass = (EditText) findViewById(R.id.ePass);
        cbAuto = (CheckBox) findViewById(R.id.cbAuto);
        spCam = (Spinner) findViewById(R.id.spCam);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvHint = (TextView) findViewById(R.id.tvHint);
        tvVer = (TextView) findViewById(R.id.tvVer);
        btn = (Button) findViewById(R.id.btnSave);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        tvVer.setText("fCam " + ver);

        int n = 0;
        try {
            n = Camera.getNumberOfCameras();
        } catch (Exception ignored) {
        }
        String[] names = new String[Math.max(n, 1)];
        if (n == 0) names[0] = "Камер не найдено";
        for (int i = 0; i < n; i++) {
            String kind = "";
            try {
                Camera.CameraInfo ci = new Camera.CameraInfo();
                Camera.getCameraInfo(i, ci);
                kind = ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? " (фронтальная)" : " (задняя)";
            } catch (Exception ignored) {
            }
            names[i] = "Камера " + i + kind;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCam.setAdapter(ad);

        Prefs p = Prefs.load(this);
        cbAuto.setChecked(p.autostart);
        if (p.camera < names.length) spCam.setSelection(p.camera);
        eThr.setText(fmt(p.threshold));
        eGap.setText(String.valueOf(p.minGap));
        eCnt.setText(String.valueOf(p.count));
        eInt.setText(fmt(p.interval));
        ePort.setText(String.valueOf(p.port));
        ePass.setText(p.password);
        tvHint.setText("Фото сохраняются в DCIM/fCam/");

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Prefs.load(MainActivity.this).enabled) stopService();
                else startService();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        h.postDelayed(tick, 1500);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    private static String fmt(float f) {
        if (f == (int) f) return String.valueOf((int) f);
        return String.valueOf(f);
    }

    private static String localIp() {
        String any = null;
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ad = a.nextElement();
                    if (ad.isLoopbackAddress() || !(ad instanceof Inet4Address)) continue;
                    if (ad.isSiteLocalAddress()) return ad.getHostAddress();
                    if (any == null) any = ad.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return any != null ? any : "адрес";
    }

    private void refresh() {
        Prefs p = Prefs.load(this);
        boolean on = p.enabled;
        boolean e = !on;
        cbAuto.setEnabled(e);
        spCam.setEnabled(e);
        eThr.setEnabled(e);
        eGap.setEnabled(e);
        eCnt.setEnabled(e);
        eInt.setEnabled(e);
        ePort.setEnabled(e);
        ePass.setEnabled(e);
        btn.setText(on ? "STOP SERVICE" : "START SERVICE");

        StringBuilder sb = new StringBuilder();
        if (on && CamService.running) {
            sb.append("Состояние: работает");
            if (!CamService.camOk) sb.append(" (камера недоступна)");
            String base = "http://" + localIp() + ":" + p.port;
            String pw = p.password.length() > 0 ? "password=" + p.password : "";
            sb.append('\n').append(base).append("/api/shot").append(pw.length() > 0 ? "?" + pw : "");
            sb.append('\n').append(base).append("/api/list").append(pw.length() > 0 ? "?" + pw : "");
            sb.append('\n').append(base).append("/api/photo?").append(pw.length() > 0 ? pw + "&" : "").append("name=файл.jpg");
        } else if (on) {
            sb.append("Состояние: запускается или не запустилось");
        } else {
            sb.append("Состояние: остановлено");
        }
        if (CamService.lastError != null) sb.append("\nОшибка: ").append(CamService.lastError);
        tvStatus.setText(sb.toString());
    }

    private void startService() {
        Prefs p = new Prefs();
        try {
            p.autostart = cbAuto.isChecked();
            p.camera = Math.max(0, spCam.getSelectedItemPosition());
            p.threshold = Float.parseFloat(eThr.getText().toString().trim().replace(',', '.'));
            p.minGap = Integer.parseInt(eGap.getText().toString().trim());
            p.count = Integer.parseInt(eCnt.getText().toString().trim());
            p.interval = Float.parseFloat(eInt.getText().toString().trim().replace(',', '.'));
            p.port = Integer.parseInt(ePort.getText().toString().trim());
            p.password = ePass.getText().toString();
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Проверьте числовые поля", Toast.LENGTH_LONG).show();
            return;
        }
        if (p.threshold <= 0 || p.minGap < 0 || p.count < 1 || p.interval < 0 || p.port < 1 || p.port > 65535) {
            Toast.makeText(this, "Некорректное значение в настройках", Toast.LENGTH_LONG).show();
            return;
        }
        p.enabled = true;
        p.save(this);
        if (Build.VERSION.SDK_INT >= 23
                && (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            refresh();
            return;
        }
        CamService.start(this);
        refresh();
    }

    private void stopService() {
        Prefs p = Prefs.load(this);
        p.enabled = false;
        p.save(this);
        CamService.stop(this);
        CamService.lastError = null;
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean cam = false;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.CAMERA.equals(perms[i]) && res[i] == PackageManager.PERMISSION_GRANTED) cam = true;
        }
        if (cam) {
            CamService.start(this);
        } else {
            Prefs p = Prefs.load(this);
            p.enabled = false;
            p.save(this);
            Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show();
        }
        refresh();
    }
}
