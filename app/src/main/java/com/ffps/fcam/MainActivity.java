package com.ffps.fcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
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

    /** Признак «открыть настройки» (ставится в уведомлении сервиса). */
    public static final String EXTRA_SETTINGS = "fcam.settings";

    private EditText ePoll, eThr, eGap, eCnt, eInt, eKeep, eReboot, ePort, ePass;
    private CheckBox cbAuto;
    private Spinner spCam;
    private TextView tvStatus, tvHint, tvVer;
    private Button btn, btnPerm, btnOwner, btnRoot;
    private String lastRebootKey = "";
    private final Handler h = new Handler();
    private Boolean lastOn = null;      // last applied state of the controls
    private String lastStatus = "";
    private boolean silent = false;     // приложение только запустило сервис и сразу закрылось, экрана нет

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
        // Если включён автозапуск, сервис ещё не работает и нас открыли не из уведомления: просто запускаем сервис
        // без окна настроек. Если сервис уже работает (или открыли из уведомления) - показываем настройки.
        Prefs p0 = Prefs.load(this);
        boolean forceUi = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SETTINGS, false);
        boolean permOk = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!forceUi && p0.autostart && !CamService.running && permOk) {
            silent = true;
            p0.enabled = true;
            p0.save(this);
            CamService.start(this);
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        setContentView(R.layout.activity_main);
        ePoll = (EditText) findViewById(R.id.ePoll);
        eThr = (EditText) findViewById(R.id.eThr);
        eGap = (EditText) findViewById(R.id.eGap);
        eCnt = (EditText) findViewById(R.id.eCnt);
        eInt = (EditText) findViewById(R.id.eInt);
        eKeep = (EditText) findViewById(R.id.eKeep);
        eReboot = (EditText) findViewById(R.id.eReboot);
        btnPerm = (Button) findViewById(R.id.btnRebootPerm);
        btnOwner = (Button) findViewById(R.id.btnRebootOwner);
        btnRoot = (Button) findViewById(R.id.btnRebootRoot);
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
        cbAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                Prefs cur = Prefs.load(MainActivity.this);
                cur.autostart = checked;
                cur.save(MainActivity.this);
            }
        });
        if (p.camera < names.length) spCam.setSelection(p.camera);
        ePoll.setText(String.valueOf(p.pollMs));
        eThr.setText(fmt(p.threshold));
        eGap.setText(String.valueOf(p.minGap));
        eCnt.setText(String.valueOf(p.count));
        eInt.setText(fmt(p.interval));
        eKeep.setText(String.valueOf(p.keepDays));
        eReboot.setText(p.rebootCron);
        ePort.setText(String.valueOf(p.port));
        ePass.setText(p.password);
        tvHint.setText("Фото сохраняются в DCIM/fCam/");

        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRebootDialog();
            }
        });
        btnRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Подтвердите запрос root на экране устройства", Toast.LENGTH_LONG).show();
                RebootControl.checkRootAsync(MainActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, RebootControl.rootGranted(MainActivity.this)
                                ? "Root: доступ есть" : "Root: доступа нет", Toast.LENGTH_LONG).show();
                        lastRebootKey = "";
                        refresh();
                    }
                });
                lastRebootKey = "";
                refresh();
            }
        });
        btnOwner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearOwner();
            }
        });

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
        if (silent) return;
        refresh();
        h.postDelayed(tick, 1500);
    }

    @Override
    protected void onPause() {
        if (silent) {
            super.onPause();
            return;
        }
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
        // touch the controls only when the state changes: any setText/setEnabled can relayout
        // the ScrollView and make it jump to the focused view on old Android versions
        if (lastOn == null || lastOn != on) {
            lastOn = on;
            boolean e = !on;
            spCam.setEnabled(e);
            ePoll.setEnabled(e);
            eThr.setEnabled(e);
            eGap.setEnabled(e);
            eCnt.setEnabled(e);
            eInt.setEnabled(e);
            eKeep.setEnabled(e);
            ePort.setEnabled(e);
            ePass.setEnabled(e);
            btn.setText(on ? "STOP SERVICE" : "START SERVICE");
            btn.setBackgroundResource(on ? R.drawable.btn_stop : R.drawable.btn_start);
        }
        refreshRebootUi(on);

        StringBuilder sb = new StringBuilder();
        if (on && CamService.running) {
            sb.append("Состояние: работает");
            if (!CamService.camOk) sb.append(" (камера недоступна)");
            sb.append('\n').append("http://").append(localIp()).append(':').append(p.port).append("/api");
        } else if (on) {
            sb.append("Состояние: запускается или не запустилось");
        } else {
            sb.append("Состояние: остановлено");
        }
        if (CamService.lastError != null) sb.append("\nОшибка: ").append(CamService.lastError);
        String text = sb.toString();
        if (!text.equals(lastStatus)) {
            lastStatus = text;
            tvStatus.setText(text);
        }
    }

    /** Подпись кнопки прав, доступность поля crontab и кнопка снятия прав. Трогаем виджеты только при изменении. */
    private void refreshRebootUi(boolean on) {
        String m = RebootControl.method(this);
        boolean a11yOn = RebootControl.accessibilityEnabled(this);
        boolean su = RebootControl.suPresent();
        boolean rootOn = RebootControl.rootGranted(this);
        boolean checking = RebootControl.rootChecking;
        String key = on + "|" + m + "|" + a11yOn + "|" + Build.VERSION.SDK_INT + "|" + su + "|" + rootOn + "|" + checking;
        if (key.equals(lastRebootKey)) return;
        lastRebootKey = key;

        String label;
        boolean canClick = true;
        if ("root".equals(m)) {
            label = "Права на перезагрузку: есть (root)";
        } else if (Build.VERSION.SDK_INT < 21) {
            label = su ? "Android 4.x: без root перезагрузка недоступна. Root найден, проверьте доступ кнопкой ниже"
                    : "Перезагрузка недоступна на Android 4.x (нужен root)";
            canClick = false;
        } else if ("deviceOwner".equals(m)) {
            label = "Права на перезагрузку: есть (владелец устройства)";
        } else if ("accessibility".equals(m)) {
            label = "Права на перезагрузку: есть (спец. возможности)";
        } else if (a11yOn) {
            label = "Права на перезагрузку: служба включена, ждём подключения…";
        } else {
            label = "Права на перезагрузку: нет. Нажмите, чтобы получить";
        }
        btnPerm.setText(label);
        btnPerm.setEnabled(canClick);
        eReboot.setEnabled(!on && !"none".equals(m));
        btnOwner.setVisibility("deviceOwner".equals(m) ? View.VISIBLE : View.GONE);
        btnRoot.setVisibility(su ? View.VISIBLE : View.GONE);
        btnRoot.setEnabled(!checking);
        btnRoot.setText(checking ? "Root: проверка… подтвердите запрос на экране устройства"
                : rootOn ? "Root: доступ есть (проверить заново)"
                : "Root: найден su. Нажмите, чтобы проверить доступ");
    }

    private void showRebootDialog() {
        String m = RebootControl.method(this);
        StringBuilder msg = new StringBuilder();
        msg.append("Сейчас: ");
        msg.append("deviceOwner".equals(m) ? "есть права владельца устройства"
                : "accessibility".equals(m) ? "работает служба специальных возможностей" : "прав нет");
        msg.append("\n\n");
        final String cmd = RebootControl.adbCommand(this);
        if (Build.VERSION.SDK_INT >= 24) {
            msg.append("Способ 1 (надёжный, Android 7+): сделать fCam владельцем устройства. ")
                    .append("На устройстве не должно быть аккаунтов. Выполните на компьютере:\n\n")
                    .append(cmd).append("\n\n");
        }
        msg.append("Способ ").append(Build.VERSION.SDK_INT >= 24 ? "2" : "1")
                .append(": если на устройстве есть root, появится кнопка проверки root (запрос подтверждается на экране).");
        if (Build.VERSION.SDK_INT >= 21) {
            msg.append("\n\nСпособ ").append(Build.VERSION.SDK_INT >= 24 ? "3" : "2")
                    .append(": включить службу специальных возможностей «fCam». Она открывает меню питания и нажимает «Перезагрузка».");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            msg.append("\nНа Android 13+ для приложения не из магазина сначала разрешите ограниченные настройки: ")
                    .append("Настройки → Приложения → fCam → меню ⋮ → «Разрешить ограниченные настройки».");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Права на перезагрузку")
                .setMessage(msg.toString())
                .setNegativeButton("Закрыть", null);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setPositiveButton("Спец. возможности", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Не удалось открыть настройки", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
        if (Build.VERSION.SDK_INT >= 24) {
            b.setNeutralButton("Копировать команду", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
                    Toast.makeText(MainActivity.this, "Команда скопирована", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    private void confirmClearOwner() {
        new AlertDialog.Builder(this)
                .setTitle("Снять права владельца устройства?")
                .setMessage("Перезагрузка через владельца устройства перестанет работать, зато приложение можно будет удалить.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Снять", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = RebootControl.clearOwner(MainActivity.this);
                        Toast.makeText(MainActivity.this, ok ? "Права сняты" : "Не удалось снять права", Toast.LENGTH_LONG).show();
                        lastRebootKey = "";
                        refresh();
                    }
                })
                .show();
    }

    private void startService() {
        Prefs p = new Prefs();
        try {
            p.autostart = cbAuto.isChecked();
            p.camera = Math.max(0, spCam.getSelectedItemPosition());
            p.pollMs = Integer.parseInt(ePoll.getText().toString().trim());
            p.threshold = Float.parseFloat(eThr.getText().toString().trim().replace(',', '.'));
            p.minGap = Integer.parseInt(eGap.getText().toString().trim());
            p.count = Integer.parseInt(eCnt.getText().toString().trim());
            p.interval = Float.parseFloat(eInt.getText().toString().trim().replace(',', '.'));
            p.keepDays = Integer.parseInt(eKeep.getText().toString().trim());
            p.rebootCron = eReboot.getText().toString().trim();
            p.port = Integer.parseInt(ePort.getText().toString().trim());
            p.password = ePass.getText().toString();
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Проверьте числовые поля", Toast.LENGTH_LONG).show();
            return;
        }
        if (p.pollMs != 0 && (p.pollMs < 50 || p.pollMs > 60000)) {
            Toast.makeText(this, "Период опроса: 0 (не анализировать видео) или от 50 до 60000 мс", Toast.LENGTH_LONG).show();
            return;
        }
        if (p.rebootCron.length() > 0 && !Schedule.isValid(p.rebootCron)) {
            Toast.makeText(this, "Неверное выражение crontab: нужно 5 полей (минута час день месяц день_недели)", Toast.LENGTH_LONG).show();
            return;
        }
        if (p.port < 1024 || p.port > 65535) {
            Toast.makeText(this, "Порт должен быть от 1024 до 65535", Toast.LENGTH_LONG).show();
            return;
        }
        if (p.threshold <= 0 || p.minGap < 0 || p.count < 1 || p.interval < 0 || p.keepDays < 0 || p.port < 1 || p.port > 65535) {
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
