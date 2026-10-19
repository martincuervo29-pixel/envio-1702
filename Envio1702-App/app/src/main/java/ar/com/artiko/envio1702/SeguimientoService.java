package ar.com.artiko.envio1702;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Seguimiento de la ruta: mientras el cadete tiene una ruta abierta, manda su ubicación
 * cada minuto a 1702 Logística, aunque use Google Maps o tenga la pantalla bloqueada.
 * Muestra un aviso fijo en el celular y se apaga solo al finalizar la ruta.
 */
public class SeguimientoService extends Service {

    static final String PREFS = "seguimiento";
    static final String CANAL = "ruta";
    static final int NOTI = 1702;

    FusedLocationProviderClient fused;
    LocationCallback callback;
    HandlerThread hilo;
    Handler red;
    final ArrayList<JSONObject> pendientes = new ArrayList<>();
    long ultimoEnvio = 0;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }

    public static void iniciar(Context c, String token) {
        prefs(c).edit().putString("token", token).putBoolean("activo", true).apply();
        Intent i = new Intent(c, SeguimientoService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void detener(Context c) {
        prefs(c).edit().putBoolean("activo", false).apply();
        c.stopService(new Intent(c, SeguimientoService.class));
    }

    public static boolean activo(Context c) { return prefs(c).getBoolean("activo", false); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!activo(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification n = aviso();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTI, n);
        } catch (Exception e) {
            // Android no deja arrancar el seguimiento desde segundo plano: se retoma cuando se abre la app.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (callback == null) empezar();
        return START_STICKY;
    }

    Notification aviso() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Ruta en curso", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aviso fijo mientras se comparte la ubicación de la ruta");
            nm.createNotificationChannel(ch);
        }
        Intent abrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        return b.setContentTitle("Ruta en curso · 1702 Logística")
                .setContentText("Se comparte tu ubicación hasta que toques Finalizar ruta.")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    void empezar() {
        hilo = new HandlerThread("seguimiento");
        hilo.start();
        red = new Handler(hilo.getLooper());
        fused = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60000)
                .setMinUpdateIntervalMillis(30000)
                .build();
        callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                for (Location l : r.getLocations()) agregar(l);
                if (System.currentTimeMillis() - ultimoEnvio > 55000) enviar();
            }
        };
        try {
            fused.requestLocationUpdates(req, callback, hilo.getLooper());
        } catch (SecurityException e) {
            detener(this);
        }
    }

    void agregar(Location l) {
        try {
            JSONObject p = new JSONObject();
            p.put("lat", l.getLatitude());
            p.put("lng", l.getLongitude());
            p.put("acc", l.hasAccuracy() ? Math.round(l.getAccuracy()) : 0);
            p.put("vel", l.hasSpeed() ? l.getSpeed() : 0);
            p.put("t", l.getTime());
            pendientes.add(p);
            while (pendientes.size() > 600) pendientes.remove(0);   // sin señal mucho tiempo: guarda los últimos
        } catch (Exception ignored) { }
    }

    int bateria() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) { return -1; }
    }

    /** Manda los puntos guardados. Si no hay señal, quedan para el próximo intento. */
    void enviar() {
        if (pendientes.isEmpty()) return;
        ultimoEnvio = System.currentTimeMillis();
        String token = prefs(this).getString("token", "");
        int n = Math.min(pendientes.size(), 200);
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < n; i++) arr.put(pendientes.get(i));
            JSONObject body = new JSONObject();
            body.put("accion", "posicion");
            body.put("token", token);
            body.put("puntos", arr);
            int bat = bateria();
            if (bat >= 0) body.put("bat", bat);

            HttpURLConnection c = (HttpURLConnection) new URL(MainActivity.URL_EXEC).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            o.close();
            int code = c.getResponseCode();
            String destino = c.getHeaderField("Location");
            c.disconnect();
            if (code == 200 || code == 302) {
                for (int i = 0; i < n; i++) pendientes.remove(0);
                if (destino != null && !leer(destino).contains("\"seguir\":true")) {
                    // La ruta se cerró (o la sesión ya no vale): se apaga el seguimiento.
                    detener(this);
                }
            }
        } catch (Exception ignored) {
            // sin señal: se reintenta con el próximo punto
        }
    }

    static String leer(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            in.close();
            c.disconnect();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "\"seguir\":true";   // si no se pudo leer la respuesta, se sigue
        }
    }

    @Override public void onDestroy() {
        if (fused != null && callback != null) fused.removeLocationUpdates(callback);
        callback = null;
        if (red != null) {
            red.post(this::enviar);          // último envío con lo que quedó
            hilo.quitSafely();
        }
        super.onDestroy();
    }
}
